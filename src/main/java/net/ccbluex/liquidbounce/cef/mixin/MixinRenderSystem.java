package net.ccbluex.liquidbounce.cef.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManagerKt;
import net.minecraft.util.TimeSource;
import net.minecraft.util.Util;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem {

    /**
     * Accelerated paint needs an EGL context, but SDL creates GLX contexts on X11.
     * An SDL_VIDEO_FORCE_EGL environment variable still takes priority over this.
     */
    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void hookForceEgl(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (Util.getPlatform() == Util.OS.LINUX && !BrowserBackendManagerKt.isBrowserSkipped()) {
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "1");
        }
    }

}
