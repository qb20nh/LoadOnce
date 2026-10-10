package io.loadonce.fabric.client.mixin;

import io.loadonce.runtime.Readiness;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes an actual rendered menu frame, never a mod-initialization callback. */
@Mixin(Minecraft.class)
public abstract class MinecraftReadinessMixin {
    @Unique
    private boolean loadonce$menuReady;

    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V",
            shift = At.Shift.AFTER))
    private void loadonce$afterPresentedFrame(boolean renderLevel, CallbackInfo callback) {
        if (loadonce$menuReady) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.isGameLoadFinished()
                && minecraft.gui.screen() instanceof TitleScreen title
                && minecraft.gui.overlay() == null
                && !((TitleScreenAccessor) title).loadonce$isFading()
                && minecraft.level == null
                && !minecraft.hasSingleplayerServer()
                && minecraft.getConnection() == null) {
            loadonce$menuReady = true;
            Readiness.markClientMenuReady();
        }
    }
}
