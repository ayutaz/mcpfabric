package dev.mcpfabric.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirEscapeTest {
	/** Stone with a stone pickaxe: 12 ticks; deepslate: 23. */
	private static final NavCell STONE = NavCell.solid(12, false);
	private static final NavCell DEEPSLATE = NavCell.solid(23, false);
	private static final NavCell GRAVEL = NavCell.solid(9, true);

	/** Stone everywhere unless set. */
	private static final class Grid implements NavWorld {
		final Map<Long, NavCell> cells = new HashMap<>();

		@Override
		public NavCell cell(int x, int y, int z) {
			if (Math.abs(x) > 60 || Math.abs(z) > 60 || Math.abs(y) > 60) return NavCell.UNKNOWN;
			return cells.getOrDefault(NavPos.key(x, y, z), STONE);
		}

		Grid fill(int x0, int y0, int z0, int x1, int y1, int z1, NavCell c) {
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) cells.put(NavPos.key(x, y, z), c);
				}
			}
			return this;
		}
	}

	private static final NavPos FEET = new NavPos(0, 0, 0);

	private static AirEscape.Way find(Grid g) {
		return AirEscape.find(g, FEET, 2000, 30);
	}

	@Test
	void swimsStraightUpOutOfAnOpenShaft() {
		Grid g = new Grid().fill(0, 0, 0, 0, 3, 0, NavCell.WATER).fill(0, 4, 0, 0, 8, 0, NavCell.AIR);
		AirEscape.Way way = find(g);
		assertNull(way.dig());
		assertEquals(List.of(new NavPos(0, 1, 0), new NavPos(0, 2, 0), new NavPos(0, 3, 0)), way.cells(),
				"feet in the top water cell, head in the air above it");
	}

	@Test
	void swimsSidewaysUnderTheCeilingToTheNearestAir() {
		// A flooded tunnel two high from x=0 to x=4 under stone; at x=4 a shaft rises into air.
		Grid g = new Grid().fill(0, 0, 0, 4, 1, 0, NavCell.WATER).fill(4, 2, 0, 4, 2, 0, NavCell.WATER)
				.fill(4, 3, 0, 4, 6, 0, NavCell.AIR);
		// The ceiling touches water (the shaft) only at x=3, so x=0..2 could be dug: swimming wins here.
		AirEscape.Way way = find(g);
		assertNull(way.dig());
		assertEquals(new NavPos(4, 2, 0), way.cells().get(way.cells().size() - 1));
		assertTrue(way.cells().stream().allMatch(p -> AirEscape.fits(g, p)));
	}

	@Test
	void digsAnAirPocketIntoTheRockOverASealedFloodedCave() {
		Grid g = new Grid().fill(-2, -2, -2, 2, 1, 2, NavCell.WATER);
		AirEscape.Way way = find(g);
		assertEquals(new NavPos(0, 2, 0), way.dig(), "the block right over the head");
		assertEquals(List.of(), way.cells(), "already under it");
	}

	@Test
	void digsWhereNothingFlowsOrFallsIntoTheHole() {
		Grid g = new Grid().fill(-2, -2, -2, 2, 1, 2, NavCell.WATER)
				.fill(0, 3, 0, 0, 3, 0, GRAVEL) // would fall into a hole dug at (0,2,0)
				.fill(1, 2, 0, 1, 2, 0, NavCell.WATER); // would flow into holes dug beside it
		AirEscape.Way way = find(g);
		NavPos dig = way.dig();
		assertTrue(!dig.equals(new NavPos(0, 2, 0)) && !dig.equals(new NavPos(1, 2, 0)) && !dig.equals(new NavPos(2, 2, 0)),
				"not under gravel, not next to water: " + dig);
		NavPos last = way.cells().isEmpty() ? FEET : way.cells().get(way.cells().size() - 1);
		assertEquals(last.above(2), dig);
	}

	@Test
	void slowBlocksAreNotDugAndLavaIsNeverEntered() {
		Grid g = new Grid().fill(-2, -2, -2, 2, 1, 2, NavCell.WATER).fill(-3, -3, -3, 3, 3, 3, DEEPSLATE)
				.fill(-2, -2, -2, 2, 1, 2, NavCell.WATER)
				.fill(3, 0, 0, 3, 1, 0, NavCell.LAVA).fill(4, 0, 0, 4, 6, 0, NavCell.AIR);
		assertNull(AirEscape.find(g, FEET, 2000, 20), "deepslate too slow under water, air only past lava");
		assertEquals(new NavPos(0, 2, 0), AirEscape.find(g, FEET, 2000, 30).dig());
	}

	@Test
	void farAirLosesToAQuickDig() {
		// A long flooded tunnel to air 41 cells away.
		Grid g = new Grid().fill(-1, 0, 0, 40, 1, 0, NavCell.WATER).fill(40, 2, 0, 40, 4, 0, NavCell.AIR);
		AirEscape.Way way = find(g);
		assertEquals(new NavPos(0, 2, 0), way.dig(), "12 ticks x 25 = 300 < 41 cells x 10");
		g.fill(20, 0, 0, 40, 1, 0, NavCell.WATER).fill(20, 2, 0, 20, 4, 0, NavCell.AIR);
		assertNull(find(g).dig(), "air 21 cells away: 210 < 300");
	}
}
