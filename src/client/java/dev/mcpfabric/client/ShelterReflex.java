package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.client.handlers.InteractHandlers;
import dev.mcpfabric.client.nav.LevelNavWorld;
import dev.mcpfabric.nav.NavPlanner;
import dev.mcpfabric.nav.NavPos;
import dev.mcpfabric.nav.ShelterSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Respawn shelter reflex (config {@code reflexes}): after a respawn at night the bot walks to dry
 * ground nearby (never next to water), digs three blocks straight down, seals the top with a dug block
 * and waits there until morning, without the model. Survival runs showed most deaths right after a
 * respawn at night, often in the pond at the world spawn, before the model could react. It starts from
 * shortly before dusk ({@link ShelterSite#startsAt}) and prefers sites away from monsters. A daytime respawn
 * digs in as well when a monster is close (the world spawn is a dark forest), and comes out once none is
 * left nearby or after a minute or so ({@link ShelterSite#holdsInDay}). While it runs,
 * movement commands are refused ({@code reflex_shelter}); it ends after dawn once no monster is close
 * ({@link ShelterSite#holdsAt}), when the player is hurt down to low health, or when a step fails. Driven by {@link BotController} on the client tick, under its lock.
 */
final class ShelterReflex {
	enum Phase { IDLE, ARMED, DRY, DIG, SEAL, HOLD }

	/** Ticks to wait after the respawn for the world to load around the player. */
	private static final int ARM_TICKS = 20;
	/** Gives up waiting for the ground after the respawn (and tries anyway) after this long. */
	private static final int ARM_MAX_TICKS = 200;
	private static final int SEARCH_RADIUS = 12;
	private static final int SEARCH_UP = 3;
	/** Down to the ground from a tree canopy (the benchmark world spawns on a dark oak forest roof). */
	private static final int SEARCH_DOWN = 14;
	/** Candidate sites to plan a path to before giving up. */
	private static final int MAX_CANDIDATES = 6;
	/** Times the search starts again after the player was pushed off the site. */
	private static final int MAX_RELOCATIONS = 4;
	private static final int PLAN_NODES = 6000;
	private static final int DRY_TICKS = 40 * 20;
	private static final int DIG_TICKS = 60 * 20;
	private static final int SEAL_TICKS = 10 * 20;
	/** From before dusk to past dawn is about 14 500 ticks; this bounds the wait if the day-time never reads as morning. */
	private static final int HOLD_TICKS = 16_000;
	/** Monsters this close to a candidate site push it to the back of the list. */
	private static final double SITE_ENEMY_RADIUS = 6;
	/** After dawn the shelter is kept while a monster is this close. */
	private static final double DAWN_ENEMY_RADIUS = 12;
	/** Ticks at the bottom for the dug blocks to be picked up before sealing. */
	private static final int PICKUP_TICKS = 15;
	/** Hurt in the sealed hole and down to this health, the reflex lets the model act. */
	private static final float LOW_HEALTH = 8.0f;
	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private final BotController bot;
	private Phase phase = Phase.IDLE;
	private int phaseTicks;
	private List<ShelterSite.Site> candidates = List.of();
	private int candidate;
	private BlockPos site;
	private int digFailures;
	private int bottomTicks;
	private int placeAttempts;
	private int placeCooldown;
	private int reseals;
	private float lastHealth;
	private long sealed;
	private long dayStarts;
	private String lastResult;
	/** Diagnostics for the end event: why the spot under the feet did not do, and paths planned. */
	private String here;
	private int planned;
	private int relocations;
	/** Dug in after a daytime respawn with a monster close: held by {@link ShelterSite#holdsInDay}. */
	private boolean daytime;

	ShelterReflex(BotController bot) {
		this.bot = bot;
	}

	void onRespawn() {
		enter(Phase.ARMED);
		lastResult = null;
		candidates = List.of();
		here = null;
		planned = 0;
	}

	/** Movement commands from the bridge are refused meanwhile. */
	boolean active() {
		return phase != Phase.IDLE;
	}

	/** For {@code player.getState}: {@code shelter_<phase>} while running, else null. */
	String state() {
		return phase == Phase.IDLE ? null : "shelter_" + phase.name().toLowerCase(Locale.ROOT);
	}

	long sealedCount() {
		return sealed;
	}

	long dayStartCount() {
		return dayStarts;
	}

	String lastResult() {
		return lastResult;
	}

	String refusal() {
		if (daytime) {
			return "The respawn shelter reflex is running (" + state() + "): the player respawned next to monsters and"
					+ " is digging in or waiting in the sealed hole until they leave (about a minute at most). Wait (e.g. a"
					+ " script with mc.sleep) and try again; it also ends when the player is hurt down to low health, or if"
					+ " a step fails.";
		}
		return "The respawn shelter reflex is running (" + state() + "): the player is digging in for the night or"
				+ " waiting in the sealed hole for morning. Wait (e.g. a script with mc.sleep) and try again; it ends after"
				+ " dawn once no monster is close, when the player is hurt down to low health, or if a step fails.";
	}

	/** Ends the reflex if it runs (the config was switched off, the player left the world). */
	void cancel(String why) {
		if (phase != Phase.IDLE) finish(why);
	}

	void tick(Minecraft mc, LocalPlayer p) {
		if (phase == Phase.IDLE) return;
		ClientLevel level = mc.level;
		if (level == null || mc.gameMode == null) return;
		if (p.isDeadOrDying()) {
			finish("died");
			return;
		}
		if (mc.gameMode.getPlayerMode() != GameType.SURVIVAL) {
			finish("not_survival");
			return;
		}
		phaseTicks++;
		switch (phase) {
			case ARMED -> armed(mc, level, p);
			case DRY -> dry(level, p);
			case DIG -> dig(mc, level, p);
			case SEAL -> seal(mc, level, p);
			case HOLD -> hold(level, p);
			default -> {}
		}
	}

	private void armed(Minecraft mc, ClientLevel level, LocalPlayer p) {
		if (mc.screen != null || phaseTicks < ARM_TICKS) return;
		// Right after a respawn the chunks around the player are sent again: wait until it stands on them.
		boolean landed = (p.onGround() || p.isInWater()) && !(level.getChunk(p.blockPosition()) instanceof EmptyLevelChunk);
		if (!landed && phaseTicks < ARM_MAX_TICKS) return;
		boolean night = ShelterSite.startsAt(dayTime(level));
		if (!ShelterSite.startsAt(dayTime(level),
				!night && enemyNear(level, p.getX(), p.getY(), p.getZ(), ShelterSite.DAY_ENEMY_RADIUS))) {
			phase = Phase.IDLE; // daytime respawn with no monster close: nothing to do, the model plays on
			lastResult = "day";
			return;
		}
		daytime = !night;
		if (daytime) dayStarts++;
		emit(daytime ? "start_day" : "start", p.blockPosition());
		relocations = 0;
		planned = 0;
		search(level, p);
		here = diagnose(level, p);
		enter(Phase.DRY);
		if (!planNext(level, p)) finish("no_site");
	}

	/** Candidate sites around where the player is now, cheapest first (its own column if that will do). */
	private void search(ClientLevel level, LocalPlayer p) {
		List<ShelterSite.Site> found = ShelterSite.find(new LevelNavWorld(level, p), pos(p.blockPosition()), SEARCH_RADIUS,
				SEARCH_UP, SEARCH_DOWN, BotController.throwawaySlot(p) >= 0, q -> givesBlock(level, q));
		// Digging takes seconds: sites next to a monster (often waiting at the spawn) go last.
		List<ShelterSite.Site> sorted = new ArrayList<>(found);
		sorted.sort(Comparator.comparing((ShelterSite.Site s) -> enemyNear(level, s.feet().x() + 0.5, s.feet().y(),
				s.feet().z() + 0.5, SITE_ENEMY_RADIUS)));
		candidates = sorted;
		candidate = 0;
	}

	/** For the end event: why the column under the feet would not do, with the cells in it. */
	private static String diagnose(ClientLevel level, LocalPlayer p) {
		NavPos from = pos(p.blockPosition());
		LevelNavWorld world = new LevelNavWorld(level, p);
		String why = ShelterSite.reject(world, from.x(), from.y(), from.z(), BotController.throwawaySlot(p) >= 0,
				q -> givesBlock(level, q));
		if (why == null) return null;
		StringBuilder col = new StringBuilder(why).append(" |");
		for (int dy = 1; dy >= -ShelterSite.DEPTH - 1; dy--) {
			col.append(' ').append(dy).append('=').append(world.cell(from.x(), from.y() + dy, from.z()).kind());
		}
		return col.toString();
	}

	/** Knocked off the site (a mob hit it): look again from here, a few times. */
	private void relocate(ClientLevel level, LocalPlayer p) {
		if (++relocations > MAX_RELOCATIONS) {
			finish("left_site");
			return;
		}
		bot.reflexHold(p);
		search(level, p);
		enter(Phase.DRY);
		if (!planNext(level, p)) finish("no_site");
	}

	/** Walks to the next candidate that has a path; false when none is left. */
	private boolean planNext(ClientLevel level, LocalPlayer p) {
		BlockPos start = p.blockPosition();
		// Digging allowed: down through leaves from a canopy, by hand.
		NavPlanner planner = new NavPlanner(new LevelNavWorld(level, p), new NavPlanner.Options(true, false, true, 0, PLAN_NODES));
		while (candidate < Math.min(candidates.size(), MAX_CANDIDATES)) {
			NavPos feet = candidates.get(candidate++).feet();
			site = new BlockPos(feet.x(), feet.y(), feet.z());
			if (start.equals(site)) return true; // already there
			planned++;
			List<NavPlanner.Step> path = planner.findPath(pos(start), feet, 0.35);
			if (path != null && !path.isEmpty()) {
				bot.reflexNavigate(path, start, site, System.currentTimeMillis() + DRY_TICKS * 50L);
				return true;
			}
		}
		return false;
	}

	private void dry(ClientLevel level, LocalPlayer p) {
		if (bot.navigating()) {
			if (phaseTicks > DRY_TICKS) finish("dry_timeout");
			return;
		}
		if (p.blockPosition().equals(site) && p.onGround() && !p.isInWater()) {
			enter(Phase.DIG);
			return;
		}
		if (!p.onGround() && !p.isInWater()) return; // still landing
		if (phaseTicks > DRY_TICKS || !planNext(level, p)) finish("no_site");
	}

	private void dig(Minecraft mc, ClientLevel level, LocalPlayer p) {
		if (phaseTicks > DIG_TICKS) {
			finish("dig_timeout");
			return;
		}
		if (bot.reflexMiningBusy()) return;
		String result = bot.takeReflexMiningResult();
		if (result != null && !"broken".equals(result) && ++digFailures > 3) {
			finish("dig_failed");
			return;
		}
		BlockPos feet = p.blockPosition();
		if (feet.getX() != site.getX() || feet.getZ() != site.getZ() || feet.getY() > site.getY()) {
			relocate(level, p);
			return;
		}
		if (!p.onGround()) {
			bot.reflexHold(p); // falling into the hole
			return;
		}
		double dx = site.getX() + 0.5 - p.getX();
		double dz = site.getZ() + 0.5 - p.getZ();
		if (feet.getY() == site.getY() && Math.hypot(dx, dz) > 0.2) {
			bot.reflexWalk(p, dx, dz); // centre over the column, or the body does not drop in
			return;
		}
		bot.reflexHold(p);
		if (feet.getY() <= site.getY() - ShelterSite.DEPTH) {
			if (++bottomTicks >= PICKUP_TICKS) enter(Phase.SEAL);
			return;
		}
		BlockPos below = feet.below();
		BlockState state = level.getBlockState(below);
		if (state.isAir()) return;
		if (!state.getFluidState().isEmpty() || fluidNext(level, below)) {
			finish("fluid");
			return;
		}
		bot.reflexMine(mc, p, below);
	}

	private void seal(Minecraft mc, ClientLevel level, LocalPlayer p) {
		bot.reflexHold(p);
		BlockPos roof = site.below();
		if (!level.getBlockState(roof).canBeReplaced()) {
			if (reseals == 0) sealed++;
			lastHealth = p.getHealth();
			emit(reseals == 0 ? "sealed" : "resealed", roof);
			enter(Phase.HOLD);
			return;
		}
		if (phaseTicks > SEAL_TICKS) {
			finish("seal_failed");
			return;
		}
		if (placeCooldown > 0) {
			placeCooldown--;
			return;
		}
		int slot = BotController.throwawaySlot(p);
		if (slot < 0) {
			if (phaseTicks > 60) finish("no_blocks");
			return;
		}
		Direction side = SIDES[placeAttempts++ % SIDES.length];
		BlockPos wall = roof.relative(side);
		BlockState ws = level.getBlockState(wall);
		if (ws.canBeReplaced() || ws.hasBlockEntity()) return; // try the next side
		Direction face = side.getOpposite();
		Vec3 hit = Vec3.atCenterOf(wall).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		BotController.selectSlot(p, slot);
		BotController.lookAt(p, hit);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, wall, false));
		InteractHandlers.swingUse(p);
		placeCooldown = 4;
	}

	private void hold(ClientLevel level, LocalPlayer p) {
		bot.reflexHold(p);
		float health = p.getHealth();
		if (health < lastHealth - 0.01f) {
			// A hit right after sealing is usually from before (an arrow in flight, a zombie at the open
			// top): the closed hole is still the safest place. Re-seal an open top; give up only when low.
			if (level.getBlockState(site.below()).canBeReplaced() && reseals++ < 2) {
				enter(Phase.SEAL);
				return;
			}
			if (health <= LOW_HEALTH) {
				finish("damaged");
				return;
			}
		}
		lastHealth = health;
		boolean enemy = enemyNear(level, p.getX(), p.getY(), p.getZ(), DAWN_ENEMY_RADIUS);
		if (daytime && ShelterSite.startsAt(dayTime(level))) {
			daytime = false; // dusk came during a daytime hold: keep it for the night
			phaseTicks = 0;
		}
		if (daytime) {
			if (!ShelterSite.holdsInDay(enemy, phaseTicks)) finish(enemy ? "day_timeout" : "clear");
		} else if (!ShelterSite.holdsAt(dayTime(level), enemy)) {
			finish("morning");
		} else if (phaseTicks > HOLD_TICKS) {
			finish("hold_timeout");
		}
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		if (next == Phase.ARMED) {
			reseals = 0;
		} else if (next == Phase.DIG) {
			digFailures = 0;
			bottomTicks = 0;
		} else if (next == Phase.SEAL) {
			placeAttempts = 0;
			placeCooldown = 0;
		}
	}

	private void finish(String result) {
		Phase was = phase;
		phase = Phase.IDLE;
		lastResult = result;
		bot.reflexRelease();
		JsonObject d = new JsonObject();
		d.addProperty("result", result);
		d.addProperty("phase", was.name().toLowerCase(Locale.ROOT));
		d.addProperty("candidates", candidates.size());
		d.addProperty("planned", planned);
		d.addProperty("relocations", relocations);
		if (here != null) d.addProperty("here", here);
		McpFabric.events().emit("reflex_shelter", d);
	}

	private void emit(String what, BlockPos at) {
		JsonObject d = new JsonObject();
		d.addProperty("result", what);
		d.addProperty("x", at.getX());
		d.addProperty("y", at.getY());
		d.addProperty("z", at.getZ());
		McpFabric.events().emit("reflex_shelter", d);
	}

	/** Water or lava next to the block (it would run into the hole). */
	private static boolean fluidNext(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d != Direction.UP && !level.getFluidState(pos.relative(d)).isEmpty()) return true;
		}
		return false;
	}

	private static boolean givesBlock(ClientLevel level, NavPos q) {
		return givesBlock(level.getBlockState(new BlockPos(q.x(), q.y(), q.z())));
	}

	/** Breaking it by hand drops a block the body can place (dirt). */
	private static boolean givesBlock(BlockState s) {
		return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
				|| s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND);
	}

	/** A living monster within {@code radius} blocks of the point. */
	private static boolean enemyNear(ClientLevel level, double x, double y, double z, double radius) {
		AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
		return !level.getEntitiesOfClass(LivingEntity.class, box,
				e -> e instanceof Enemy && e.isAlive() && e.distanceToSqr(x, y, z) <= radius * radius).isEmpty();
	}

	private static long dayTime(ClientLevel level) {
		//? if <26.1 {
		return level.getDayTime();
		//?} else
		/*return level.getDefaultClockTime();*/
	}

	private static NavPos pos(BlockPos p) {
		return new NavPos(p.getX(), p.getY(), p.getZ());
	}
}
