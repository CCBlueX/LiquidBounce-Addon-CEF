package net.ccbluex.liquidbounce.cef.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManagerKt;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.MCEFAccelerationSupport;
import net.minecraft.util.TimeSource;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem {

    /**
     * Accelerated paint needs an EGL context, but SDL creates GLX contexts on X11 and picks GLX or EGL when its
     * video subsystem starts, before it is known whether it runs on X11 at all. On Wayland it always uses EGL.
     * An SDL_VIDEO_FORCE_EGL environment variable wins.
     */
    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void hookForceEgl(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!BrowserBackendManagerKt.isBrowserSkipped()
                && !BrowserBackendManagerKt.isBrowserAccelerationDisabled()
                && MCEFAccelerationSupport.isX11AcceleratedPaintPossible()) {
            MCEF.INSTANCE.LOGGER.info("Forcing EGL for accelerated paint");
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_FORCE_EGL, "1");
        }
    }

}
