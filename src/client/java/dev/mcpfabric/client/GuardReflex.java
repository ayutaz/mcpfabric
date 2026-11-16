package dev.mcpfabric.client;

import dev.mcpfabric.client.handlers.InteractHandlers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Reflex (config {@code reflexes}): whenever the attack is fully charged, hits the nearest hostile mob
 * within melee reach that the player can see, with a sword or axe from the hotbar if there is one (the
 * held slot is restored right after). Agent jobs fight back only while they walk or mine; between model
 * turns and inside scripts the player stood still while zombies killed it. Never hits creepers (a hit
 * does not stop the fuse) nor endermen and piglins, which a hit turns hostile. It also runs while the
 * shelter reflex holds the player in a hole: zombies reaching in killed agents there. Ticked by
 * {@link BotController} under its lock.
 */
final class GuardReflex {
	/** Blocks beyond the player's interaction range still counted as reachable: none. */
	private static final double REACH_BUFFER = 0.0;
	/** Attack charge (0..1) a hit waits for: a weak hit does barely any damage. */
	private static final float CHARGE = 0.9F;

	private long hits;

	long hits() {
		return hits;
	}

	void tick(Minecraft mc, LocalPlayer p) {
		if (mc.level == null || mc.gameMode == null || mc.screen != null || p.isDeadOrDying() || p.isSpectator() || p.isSleeping()) return;
		if (p.getAttackStrengthScale(0.5F) < CHARGE) return;
		AABB box = p.getBoundingBox().inflate(5.0);
		Entity target = null;
		double best = Double.MAX_VALUE;
		for (Entity e : mc.level.getEntities(p, box, GuardReflex::hostile)) {
			if (!ClientMc.canReachEntity(p, e, REACH_BUFFER) || !p.hasLineOfSight(e)) continue;
			double d = p.distanceToSqr(e);
			if (d < best) {
				best = d;
				target = e;
			}
		}
		if (target == null) return;
		int held = BotController.selectedSlot(p);
		int weapon = weapon(p, held);
		if (weapon != held) BotController.selectSlot(p, weapon);
		mc.gameMode.attack(p, target);
		InteractHandlers.swingAttack(p);
		if (weapon != held) BotController.selectSlot(p, held);
		hits++;
	}

	private static boolean hostile(Entity e) {
		return e instanceof Enemy && e instanceof LivingEntity living && living.isAlive()
				&& !(e instanceof Creeper) && !(e instanceof EnderMan) && !(e instanceof ZombifiedPiglin)
				&& !(e instanceof AbstractPiglin);
	}

	/** A hotbar sword, else an axe, else the held slot. */
	private static int weapon(LocalPlayer p, int held) {
		int axe = -1;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = p.getInventory().getItem(slot);
			if (stack.is(ItemTags.SWORDS)) return slot;
			if (axe < 0 && stack.is(ItemTags.AXES)) axe = slot;
		}
		return axe >= 0 ? axe : held;
	}
}
