package dev.mcpfabric.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where to dig an emergency shelter: a column of dry ground the body can stand on, three diggable
 * blocks deep, closed on every side, with a floor under it and no water or lava touching it. The body
 * digs the three blocks under its feet, drops to the bottom and seals the top cell with a dug block,
 * which leaves a closed two-block space. Used by the respawn shelter reflex (config {@code reflexes}).
 */
public final class ShelterSite {
	/** Blocks dug under the feet. The body ends up standing {@code DEPTH} blocks lower. */
	public static final int DEPTH = 3;
	/** A dug cell may take this long to break (bare hands on stone take about 150 ticks). */
	public static final float MAX_BREAK_TICKS = 160;
	/**
	 * Time of day (0..23999) from which a respawn digs in: shortly before dusk, since a body that
	 * respawns then is still out in the open when the monsters come (night starts at 12 500).
	 */
	public static final long START_TIME = 11_000;
	/** The shelter is held at least until this time of day after dawn (monsters linger). */
	public static final long DAWN_HOLD = 1_000;
	/** ... and while monsters are still close, at most until this time. */
	public static final long DAWN_MAX = 2_500;

	private ShelterSite() {}

	/** Whether a respawn at this time of day (any long; taken modulo a day) digs in. */
	public static boolean startsAt(long dayTime) {
		long t = Math.floorMod(dayTime, 24_000L);
		return t >= START_TIME || t < DAWN_HOLD;
	}

	/** Whether a sealed shelter is kept at this time of day, given monsters near the player or not. */
	public static boolean holdsAt(long dayTime, boolean enemyNear) {
		long t = Math.floorMod(dayTime, 24_000L);
		if (t >= START_TIME || t < DAWN_HOLD) return true;
		return enemyNear && t < DAWN_MAX;
	}

	/**
	 * A daytime respawn digs in too when a monster is this close: the benchmark world spawns in a dark
	 * oak forest, where skeletons and zombies live in the shade all day (three daytime respawns in a row
	 * were shot there within two minutes).
	 */
	public static final double DAY_ENEMY_RADIUS = 16;
	/** A daytime shelter is held at most this long (ticks) while monsters stay close ... */
	public static final int DAY_HOLD_MAX = 2_400;
	/** ... and at least this long, so a monster stepping out of sight for a moment does not end it. */
	public static final int DAY_HOLD_MIN = 100;

	/** Whether a respawn digs in: at night, or in the day with a monster close (see {@link #DAY_ENEMY_RADIUS}). */
	public static boolean startsAt(long dayTime, boolean enemyNear) {
		return startsAt(dayTime) || enemyNear;
	}

	/**
	 * Whether a shelter dug in after a daytime respawn is kept, {@code heldTicks} after it was sealed:
	 * while monsters are close, between {@link #DAY_HOLD_MIN} and {@link #DAY_HOLD_MAX}. Once
	 * {@link #startsAt(long)} turns true during the wait (dusk), the reflex holds it by {@link #holdsAt} instead.
	 */
	public static boolean holdsInDay(boolean enemyNear, int heldTicks) {
		if (heldTicks < DAY_HOLD_MIN) return true;
		return enemyNear && heldTicks < DAY_HOLD_MAX;
	}

	/** A candidate: where to stand before digging, and how costly it is (distance plus digging time). */
	public record Site(NavPos feet, double cost) {}

	/**
	 * Candidate sites around {@code from}, cheapest first: columns within {@code radius} blocks
	 * horizontally, from {@code up} blocks above to {@code down} blocks below (a body on a tree canopy
	 * has its ground far below). {@code givesBlock} says whether breaking a cell drops a
	 * block the body can place (dirt from grass); unless {@code haveBlock}, a site needs one such cell.
	 */
	public static List<Site> find(NavWorld w, NavPos from, int radius, int up, int down, boolean haveBlock, Predicate<NavPos> givesBlock) {
		List<Site> out = new ArrayList<>();
		for (int x = from.x() - radius; x <= from.x() + radius; x++) {
			for (int z = from.z() - radius; z <= from.z() + radius; z++) {
				double hd = Math.hypot(x - from.x(), z - from.z());
				if (hd > radius) continue;
				for (int y = from.y() + up; y >= from.y() - down; y--) {
					double dig = digTicks(w, x, y, z, haveBlock, givesBlock);
					if (dig < 0) continue;
					out.add(new Site(new NavPos(x, y, z), hd + Math.abs(y - from.y()) + dig / 20.0));
					break; // one standing height per column: the highest that works
				}
			}
		}
		out.sort(Comparator.comparingDouble(Site::cost));
		return out;
	}

	/**
	 * Total ticks to dig the shelter with the body's feet at (x, y, z), or -1 when the column does not
	 * qualify.
	 */
	static double digTicks(NavWorld w, int x, int y, int z, boolean haveBlock, Predicate<NavPos> givesBlock) {
		double[] ticks = new double[1];
		return reject(w, x, y, z, haveBlock, givesBlock, ticks) == null ? ticks[0] : -1;
	}

	/** Why the column with the body's feet at (x, y, z) does not qualify, or null if it does (for diagnostics). */
	public static String reject(NavWorld w, int x, int y, int z, boolean haveBlock, Predicate<NavPos> givesBlock) {
		return reject(w, x, y, z, haveBlock, givesBlock, new double[1]);
	}

	private static String reject(NavWorld w, int x, int y, int z, boolean haveBlock, Predicate<NavPos> givesBlock, double[] ticks) {
		NavCell feet = w.cell(x, y, z);
		NavCell head = w.cell(x, y + 1, z);
		if (feet.kind() != NavCell.Kind.OPEN) return "feet " + feet.kind();
		if (head.kind() != NavCell.Kind.OPEN) return "head " + head.kind();
		NavCell floor = w.cell(x, y - DEPTH - 1, z);
		if (!floor.ground()) return "floor " + floor.kind();
		boolean block = haveBlock;
		ticks[0] = 0;
		for (int i = 1; i <= DEPTH; i++) {
			NavCell c = w.cell(x, y - i, z);
			if (c.kind() != NavCell.Kind.SOLID || c.breakTicks() < 0 || c.breakTicks() > MAX_BREAK_TICKS) {
				return "dig -" + i + " " + c.kind() + " " + c.breakTicks();
			}
			ticks[0] += c.breakTicks();
			if (!block && givesBlock.test(new NavPos(x, y - i, z))) block = true;
		}
		if (!block) return "no block to seal";
		// Walls: closed on every side of the dug cells, so nothing walks in and nothing flows in.
		for (int i = 1; i <= DEPTH; i++) {
			for (int[] d : SIDES) {
				NavCell c = w.cell(x + d[0], y - i, z + d[1]);
				if (c.passable() || c.kind() == NavCell.Kind.LAVA || c.kind() == NavCell.Kind.UNKNOWN
						|| c.kind() == NavCell.Kind.DANGER) {
					return "wall -" + i + " " + c.kind();
				}
			}
		}
		// No fluid next to the opening, where it would run in before the top is sealed.
		for (int[] d : SIDES) {
			if (fluid(w.cell(x + d[0], y, z + d[1]))) return "fluid beside";
		}
		return null;
	}

	private static boolean fluid(NavCell c) {
		return c.kind() == NavCell.Kind.WATER || c.kind() == NavCell.Kind.LAVA;
	}

	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
}
