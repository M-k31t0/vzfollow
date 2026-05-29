package com.vz.follow.mixin;

import com.vz.follow.VzFollowClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales the rendered field of view while the zoom key is held, the same way OptiFine does.
 * Going through the renderer (instead of the FOV option) lets us drop below the option's
 * 30-degree floor, giving a real ~4x zoom.
 */
@Mixin(GameRenderer.class)
public class GameRendererZoomMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void vzfollow$applyZoom(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (VzFollowClient.isZooming()) {
            cir.setReturnValue((float) (cir.getReturnValueF() * VzFollowClient.zoomFovMultiplier()));
        }
    }
}
