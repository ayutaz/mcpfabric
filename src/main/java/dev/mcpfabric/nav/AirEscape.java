package dev.mcpfabric.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A way out for a player under water whose air runs low. A breadth-first search through water and air
 * (never lava or other hazards) from the feet finds the nearest cell where the head is out of the water.
 * The walking planner never swims under water, so in a flooded cave it finds no way at all; this search
 * does. Where no air is reachable, as in a pocket of water under a rock ceiling, or where digging is
 * quicker, it picks a ceiling block to dig instead: water does not flow up, so the dug cell stays air
 * when no water or lava touches it from the sides or above, and the player floats up into it.
 */
public final class AirEscape {
	/** Ticks one swum cell takes, against which digging time is weighed. */
	static final int SWIM_STEP_TICKS = 10;
	/** Mining under water is five times slower, and five times again while not standing on ground. */
	static final int UNDERWATER_DIG_FACTOR = 25;

	/**
	 * @param cells feet cells to swim through, first step first (empty when the player is there already)
	 * @param dig   the block to break once there, for an air pocket above the head; null when the head
	 *              is out of the water at the last cell
	 */
	public record Way(List<NavPos> cells, NavPos dig) {}

	private AirEscape() {}

	/**
	 * @param maxDigTicks highest break time (in ticks, with the best hotbar item on dry ground) of a
	 *                    ceiling block worth digging
	 * @return the way, or null when no air and no diggable ceiling is within {@code maxNodes} cells
	 */
	public static Way find(NavWorld w, NavPos feet, int maxNodes, float maxDigTicks) {
		Map<Long, Integer> steps = new HashMap<>();
		Map<Long, NavPos> from = new HashMap<>();
		ArrayDeque<NavPos> queue = new ArrayDeque<>();
		queue.add(feet);
		steps.put(feet.key(), 0);
		NavPos digFrom = null;
		double digCost = Double.MAX_VALUE;
		int expanded = 0;
		while (!queue.isEmpty() && expanded++ < maxNodes) {
			NavPos p = queue.poll();
			int s = steps.get(p.key());
			if ((double) s * SWIM_STEP_TICKS >= digCost) break; // no air from here on beats the dig
			if (breathes(w, p)) return new Way(path(from, feet, p), null);
			double cost = s * SWIM_STEP_TICKS + ceilingTicks(w, p, maxDigTicks) * UNDERWATER_DIG_FACTOR;
			if (cost < digCost) {
				digCost = cost;
				digFrom = p;
			}
			for (NavPos n : List.of(p.above(), p.offset(1, 0, 0), p.offset(-1, 0, 0), p.offset(0, 0, 1),
					p.offset(0, 0, -1), p.below())) {
				if (steps.containsKey(n.key()) || !fits(w, n)) continue;
				steps.put(n.key(), s + 1);
				from.put(n.key(), p);
				queue.add(n);
			}
		}
		return digFrom == null ? null : new Way(path(from, feet, digFrom), digFrom.above(2));
	}

	/** The body fits in water or air (no lava, fire, cobweb...). */
	static boolean fits(NavWorld w, NavPos feet) {
		return w.cell(feet).passable() && w.cell(feet.above()).passable();
	}

	/** The head is out of the water: the player breathes here. */
	static boolean breathes(NavWorld w, NavPos feet) {
		return fits(w, feet) && w.cell(feet.above()).kind() != NavCell.Kind.WATER;
	}

	/**
	 * Break ticks of the block over the head when digging it opens an air pocket there (the head is in
	 * water, nothing would flow or fall into the hole), else infinity.
	 */
	private static double ceilingTicks(NavWorld w, NavPos feet, float maxDigTicks) {
		NavPos c = feet.above(2);
		NavCell cell = w.cell(c);
		if (w.cell(feet.above()).kind() != NavCell.Kind.WATER || !cell.breakable() || cell.gravity()
				|| cell.breakTicks() > maxDigTicks || w.cell(c.above()).gravity()) {
			return Double.POSITIVE_INFINITY;
		}
		for (NavPos n : List.of(c.above(), c.offset(1, 0, 0), c.offset(-1, 0, 0), c.offset(0, 0, 1), c.offset(0, 0, -1))) {
			NavCell.Kind k = w.cell(n).kind();
			if (k == NavCell.Kind.WATER || k == NavCell.Kind.LAVA) return Double.POSITIVE_INFINITY;
		}
		return cell.breakTicks();
	}

	private static List<NavPos> path(Map<Long, NavPos> from, NavPos start, NavPos end) {
		List<NavPos> cells = new ArrayList<>();
		for (NavPos p = end; !p.equals(start); p = from.get(p.key())) cells.add(p);
		Collections.reverse(cells);
		return cells;
	}
}
