package dev.mcpfabric.client.fabric.mixin;

import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla ticks the client at most at 20 tps ({@code max(50 ms, server ms per tick)}), so a server sped up
 * with {@code /tick rate} runs the world fast while the local player still moves at normal speed. With
 * {@code syncClientTickRate} the client follows the server's rate both ways (test rigs only; off by default).
 */
@Mixin(Minecraft.class)
public abstract class TickRateSyncMixin {
	@Shadow
	public ClientLevel level;

	@Inject(method = "getTickTargetMillis", at = @At("RETURN"), cancellable = true)
	private void mcpfabric$followServerTickRate(float defaultMs, CallbackInfoReturnable<Float> cir) {
		if (level == null || !McpFabric.config().syncClientTickRate) return;
		TickRateManager rate = level.tickRateManager();
		if (rate.runsNormally()) cir.setReturnValue(rate.millisecondsPerTick());
	}
}
