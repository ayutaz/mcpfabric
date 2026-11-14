package dev.mcpfabric.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterSiteTest {
	/** Dirt by hand: about 15 ticks. */
	private static final NavCell DIRT = NavCell.solid(15, false);
	private static final NavCell STONE_BY_HAND = NavCell.solid(150, false);

	/** Dirt up to y=0 over stone from y=-2 down, air above, loaded within 10 blocks of the origin. */
	private static final class Grid implements NavWorld {
		final Map<Long, NavCell> cells = new HashMap<>();

		@Override
		public NavCell cell(int x, int y, int z) {
			if (Math.abs(x) > 10 || Math.abs(z) > 10 || y < -20 || y > 40) return NavCell.UNKNOWN;
			NavCell c = cells.get(NavPos.key(x, y, z));
			if (c != null) return c;
			if (y > 0) return NavCell.AIR;
			return y >= -1 ? DIRT : STONE_BY_HAND;
		}

		Grid set(int x, int y, int z, NavCell c) {
			cells.put(NavPos.key(x, y, z), c);
			return this;
		}

		/** A pond: water from y=-1 to y=0 in the square |x-cx|,|z-cz| <= r. */
		Grid pond(int cx, int cz, int r) {
			for (int x = cx - r; x <= cx + r; x++) {
				for (int z = cz - r; z <= cz + r; z++) {
					set(x, 0, z, NavCell.WATER);
					set(x, -1, z, NavCell.WATER);
				}
			}
			return this;
		}
	}

	private static final NavPos ORIGIN = new NavPos(0, 1, 0);

	private static List<ShelterSite.Site> find(Grid g, NavPos from) {
		return ShelterSite.find(g, from, 8, 3, 3, false, p -> g.cell(p.x(), p.y(), p.z()) == DIRT);
	}

	@Test
	void flatGroundGivesTheSpotUnderTheFeet() {
		List<ShelterSite.Site> sites = find(new Grid(), ORIGIN);
		assertFalse(sites.isEmpty());
		assertEquals(ORIGIN, sites.get(0).feet());
	}

	@Test
	void standingInAPondLeadsToDryGroundAwayFromTheWater() {
		Grid g = new Grid().pond(0, 0, 2);
		List<ShelterSite.Site> sites = find(g, new NavPos(0, 0, 0));
		assertFalse(sites.isEmpty());
		// Not in the pond, and no water beside the hole (it would run in).
		for (ShelterSite.Site s : sites) {
			NavPos f = s.feet();
			assertTrue(Math.max(Math.abs(f.x()), Math.abs(f.z())) >= 3, "site in the pond " + f);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				for (int y = f.y() - ShelterSite.DEPTH; y <= f.y(); y++) {
					assertTrue(g.cell(f.x() + d[0], y, f.z() + d[1]).kind() != NavCell.Kind.WATER, "water beside " + f);
				}
			}
		}
	}

	@Test
	void aCaveNextToTheColumnRulesItOut() {
		Grid g = new Grid().set(1, -1, 0, NavCell.AIR);
		NavPos f = find(g, ORIGIN).get(0).feet();
		assertFalse(f.x() == 0 && f.z() == 0);
		assertFalse(f.x() == 1 && f.z() == 0);
	}

	@Test
	void aColumnWithoutADiggableBlockToSealItIsSkippedUnlessTheBodyHasOne() {
		Grid g = new Grid();
		for (int y = -1; y <= 0; y++) g.set(0, y, 0, STONE_BY_HAND);
		NavPos f = find(g, ORIGIN).get(0).feet();
		assertFalse(f.x() == 0 && f.z() == 0);
		List<ShelterSite.Site> withBlock = ShelterSite.find(g, ORIGIN, 8, 3, 3, true, p -> false);
		assertTrue(withBlock.stream().anyMatch(s -> s.feet().equals(ORIGIN)));
	}

	@Test
	void undiggableOrUnloadedColumnsAreSkipped() {
		Grid g = new Grid().set(0, -2, 0, NavCell.WALL);
		NavPos f = find(g, ORIGIN).get(0).feet();
		assertFalse(f.x() == 0 && f.z() == 0);
		// Next to the unloaded edge: no walls known, so no site there.
		for (ShelterSite.Site s : ShelterSite.find(g, new NavPos(9, 1, 0), 3, 2, 2, false, p -> true)) {
			assertTrue(s.feet().x() <= 9, "site at the edge " + s.feet());
		}
	}

	@Test
	void lavaUnderOrBesideTheShelterRulesItOut() {
		Grid under = new Grid().set(0, -3, 0, NavCell.LAVA);
		NavPos f = find(under, ORIGIN).get(0).feet();
		assertFalse(f.x() == 0 && f.z() == 0);
		Grid beside = new Grid().set(1, -2, 0, NavCell.LAVA);
		for (ShelterSite.Site s : find(beside, ORIGIN)) {
			NavPos p = s.feet();
			assertFalse(Math.abs(p.x() - 1) + Math.abs(p.z()) <= 1, "touches the lava " + p);
		}
	}

	@Test
	void fromATreeCanopyTheGroundBelowIsFound() {
		Grid g = new Grid();
		// A leaf roof at y=9..10 over the whole area, the body standing on it.
		NavCell leaves = NavCell.solid(6, false);
		for (int x = -10; x <= 10; x++) {
			for (int z = -10; z <= 10; z++) {
				g.set(x, 9, z, leaves);
				g.set(x, 10, z, leaves);
			}
		}
		NavPos onRoof = new NavPos(0, 11, 0);
		assertTrue(ShelterSite.find(g, onRoof, 8, 3, 3, false, p -> g.cell(p.x(), p.y(), p.z()) == DIRT).isEmpty());
		List<ShelterSite.Site> sites = ShelterSite.find(g, onRoof, 8, 3, 14, false, p -> g.cell(p.x(), p.y(), p.z()) == DIRT);
		assertFalse(sites.isEmpty());
		assertEquals(1, sites.get(0).feet().y());
	}
}
