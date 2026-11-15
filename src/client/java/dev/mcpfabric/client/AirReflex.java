package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.client.nav.LevelNavWorld;
import dev.mcpfabric.nav.AirEscape;
import dev.mcpfabric.nav.NavPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Reflex (config {@code reflexes}): under water with half of the air gone, swims to the nearest cell
 * where the head is out of the water, or digs an air pocket into the rock over a sealed flooded cave
 * ({@link AirEscape}). Holding jump alone drowns the player under a ceiling, and the walking planner
 * finds no way out of a flooded cave at all. Reports its start and end as {@code reflex_air} events.
 * Ticked by {@link BotController} under its lock.
 */
final class AirReflex {
	private static final int SEARCH_NODES = 1500;
	/** Break ticks (best hotbar item, dry ground) of a ceiling block worth digging; under water it takes about 25 times that. */
	private static final float MAX_DIG_TICKS = 30;
	/** Ticks per swum cell before the way is searched again. */
	private static final int STEP_TICKS = 40;
	private static final int MAX_REPLANS = 3;
	/** Ticks after a search that found nothing, or a give-up, before the next search. */
	private static final int RETRY_TICKS = 60;

	private final BotController bot;
	private AirEscape.Way way;
	private int index;
	private int stepTicks;
	private int replans;
	private int cooldown;
	private boolean mining;

	AirReflex(BotController bot) {
		this.bot = bot;
	}

	boolean active() {
		return way != null;
	}

	/** @param underWater the head is under water (and the player rides nothing) */
	void tick(Minecraft mc, LocalPlayer p, boolean underWater) {
		if (cooldown > 0) cooldown--;
		if (!underWater || p.isDeadOrDying() || mc.level == null) {
			if (way != null) end(p, underWater ? "stopped" : "surfaced", 0);
			return;
		}
		if (way == null) {
			if (cooldown > 0 || p.getAirSupply() >= p.getMaxAirSupply() / 2) return;
			replans = 0;
			if (!search(mc, p, "start")) return;
		}
		drive(mc, p);
	}

	/** Stops without a report (reflexes switched off, the shelter reflex took over). */
	void cancel() {
		if (way != null) bot.airRelease();
		way = null;
		mining = false;
	}

	private boolean search(Minecraft mc, LocalPlayer p, String result) {
		AirEscape.Way w = AirEscape.find(new LevelNavWorld(mc.level, p), feet(p), SEARCH_NODES, MAX_DIG_TICKS);
		if (w == null || (w.cells().isEmpty() && w.dig() == null)) {
			// Nothing found, or the grid already puts the head in air (swimming pose): jump is held anyway.
			boolean was = way != null;
			cancel();
			cooldown = RETRY_TICKS;
			if (w == null) emit(p, was ? "no_way" : "no_way_start", null);
			return false;
		}
		way = w;
		index = 0;
		stepTicks = 0;
		mining = false;
		emit(p, result, w);
		return true;
	}

	private void drive(Minecraft mc, LocalPlayer p) {
		List<NavPos> cells = way.cells();
		if (index < cells.size()) {
			NavPos c = cells.get(index);
			if (feet(p).equals(c)) {
				index++;
				stepTicks = 0;
			} else if (++stepTicks > STEP_TICKS) {
				replan(mc, p);
				return;
			}
			if (index < cells.size()) {
				NavPos next = cells.get(index);
				double dx = next.x() + 0.5 - p.getX();
				double dz = next.z() + 0.5 - p.getZ();
				bot.airSwim(p, dx, dz, Math.sqrt(dx * dx + dz * dz) > 0.2, next.y() < feet(p).y());
				return;
			}
		}
		if (way.dig() != null) {
			BlockPos d = new BlockPos(way.dig().x(), way.dig().y(), way.dig().z());
			bot.airHold(p); // the swim reflex holds jump: up against the ceiling
			if (!mining) {
				if (!mc.level.getBlockState(d).isAir()) {
					bot.reflexMine(mc, p, d);
					mining = true;
					return;
				}
			} else if (bot.reflexMiningBusy()) {
				return;
			} else {
				String r = bot.takeReflexMiningResult();
				if (!"broken".equals(r)) {
					end(p, "dig_" + r, RETRY_TICKS);
					return;
				}
			}
			emit(p, "dug", way);
			way = new AirEscape.Way(List.of(), null);
			mining = false;
			stepTicks = 0;
			return;
		}
		// Under the air or the dug pocket: rise with the swim reflex.
		bot.airHold(p);
		if (++stepTicks > STEP_TICKS * 2) replan(mc, p);
	}

	private void replan(Minecraft mc, LocalPlayer p) {
		if (++replans > MAX_REPLANS) {
			end(p, "stuck", RETRY_TICKS);
		} else {
			search(mc, p, "replan");
		}
	}

	private void end(LocalPlayer p, String result, int retry) {
		cancel();
		cooldown = retry;
		emit(p, result, null);
	}

	private static NavPos feet(LocalPlayer p) {
		BlockPos b = p.blockPosition();
		return new NavPos(b.getX(), b.getY(), b.getZ());
	}

	private static void emit(LocalPlayer p, String result, AirEscape.Way w) {
		JsonObject d = new JsonObject();
		d.addProperty("result", result);
		BlockPos b = p.blockPosition();
		d.addProperty("x", b.getX());
		d.addProperty("y", b.getY());
		d.addProperty("z", b.getZ());
		d.addProperty("air", p.getAirSupply());
		if (w != null) {
			d.addProperty("cells", w.cells().size());
			if (w.dig() != null) {
				d.addProperty("digX", w.dig().x());
				d.addProperty("digY", w.dig().y());
				d.addProperty("digZ", w.dig().z());
			}
		}
		McpFabric.events().emit("reflex_air", d);
	}
}
