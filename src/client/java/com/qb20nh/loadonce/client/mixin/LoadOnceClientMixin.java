package com.qb20nh.loadonce.client.mixin;

import com.qb20nh.loadonce.target.fabric.FabricMainMenuProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class LoadOnceClientMixin {
	@Inject(at = @At("HEAD"), method = "renderFrame")
	private void loadonceFrameStarted(boolean renderLevel, CallbackInfo info) {
		FabricMainMenuProbe.frameStarted((Minecraft) (Object) this);
	}

	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"), method = "renderFrame")
	private void loadonceBeforeExtraction(boolean renderLevel, CallbackInfo info) {
		FabricMainMenuProbe.beforeExtraction((Minecraft) (Object) this);
	}

	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER), method = "renderFrame")
	private void loadonceAfterExtraction(boolean renderLevel, CallbackInfo info) {
		FabricMainMenuProbe.afterExtraction((Minecraft) (Object) this);
	}

	@Inject(at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER), method = "renderFrame")
	private void loadonceAfterPresent(boolean renderLevel, CallbackInfo info) {
		FabricMainMenuProbe.afterPresent((Minecraft) (Object) this);
	}

	@Inject(at = @At("RETURN"), method = "renderFrame")
	private void loadonceFrameFinished(boolean renderLevel, CallbackInfo info) {
		FabricMainMenuProbe.frameFinished((Minecraft) (Object) this);
	}
}
