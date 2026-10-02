/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.cef;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import net.ccbluex.liquidbounce.cef.utils.EglUtils;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EXTDeviceDRM;
import org.lwjgl.egl.EXTDeviceQuery;
import org.lwjgl.egl.KHRPlatformX11;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import oshi.SystemInfo;

/**
 * Check if the current platform supports GPU acceleration for CEF.
 */
public final class CefAccelerationSupport {

    public enum Support {
        UNSUPPORTED,
        /**
         * Works on some setups of this kind, so it stays off until the player turns it on.
         */
        OPT_IN,
        DEFAULT
    }

    private static final Pattern DRIVER_VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");

    private static volatile @Nullable Support cachedSupport;

    private CefAccelerationSupport() {
    }

    /**
     * Checks and returns the acceleration support for the current platform.
     * Result is cached after the first computation.
     */
    public static Support getAccelerationSupport() {
        var support = cachedSupport;
        if (support != null) {
            return support;
        }

        var backendName = RenderSystem.getDevice().getDeviceInfo().backendName();
        if (!"OpenGL".equals(backendName)) {
            return cachedSupport = result(Support.UNSUPPORTED, "the " + backendName + " backend");
        }

        return cachedSupport = switch (CefPlatform.getPlatform()) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> checkWindowsSupport();
            case LINUX_AMD64, LINUX_ARM64 -> checkLinuxSupport();
            case MACOS_AMD64, MACOS_ARM64 -> checkMacOSSupport();
        };
    }

    private static Support result(Support support, String reason) {
        CefRuntime.INSTANCE.LOGGER.info("Accelerated paint: {} ({})", support, reason);
        return support;
    }

    private static Support checkWindowsSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            var vendor = glString(GL11.GL_VENDOR);
            var renderer = glString(GL11.GL_RENDERER);
            CefRuntime.INSTANCE.LOGGER.info("GPU: {} ({})", renderer, vendor);

            var capabilities = GL.getCapabilities();
            if (!capabilities.GL_EXT_memory_object
                || !capabilities.GL_EXT_memory_object_win32
                || capabilities.glImportMemoryWin32HandleEXT == 0L) {
                return result(Support.UNSUPPORTED, "no GL_EXT_memory_object_win32");
            }

            if (isNvidiaGpu(vendor, renderer)) {
                return result(Support.DEFAULT, "NVIDIA");
            }

            if (isAmdGpu(vendor, renderer)) {
                var driverVersion = findAmdDriverVersion(renderer);
                if (driverVersion == null) {
                    return result(Support.OPT_IN, "AMD driver version unknown");
                }

                return isAmdLeakFixed(driverVersion)
                    ? result(Support.DEFAULT, "AMD driver " + driverVersion)
                    : result(Support.OPT_IN, "AMD driver " + driverVersion + " leaks shared textures");
            }

            // Intel's Windows driver cannot import D3D11 images:
            // https://github.com/IGCIT/Intel-GPU-Community-Issue-Tracker-IGCIT/issues/1143
            return result(Support.UNSUPPORTED, vendor);
        } catch (Exception e) {
            CefRuntime.INSTANCE.LOGGER.warn("Failed to check GPU acceleration support: {}", e.getMessage());
            return Support.UNSUPPORTED;
        }
    }

    /**
     * AMD drivers keep D3D11 shared textures alive until the device that created them flushes, and Chromium's
     * never does (https://github.com/CCBlueX/LiquidBounce/issues/6404). Adrenalin 26.1.1 fixed it, as 32.0.21041
     * on the RDNA1/2 branch and 32.0.23017 on the main one. Polaris and Vega stay on 31.0.219xx.
     */
    static boolean isAmdLeakFixed(String driverVersion) {
        var matcher = DRIVER_VERSION.matcher(driverVersion);
        if (!matcher.find()) {
            return false;
        }

        var major = Integer.parseInt(matcher.group(1));
        var build = Integer.parseInt(matcher.group(3));
        return major > 32 || major == 32 && (build >= 23017 || build >= 21041 && build < 22000);
    }

    private static @Nullable String findAmdDriverVersion(String renderer) {
        String version = null;
        for (var card : new SystemInfo().getHardware().getGraphicsCards()) {
            if (!isAmdGpu(card.getVendor(), card.getName())) {
                continue;
            }

            if (card.getName().equalsIgnoreCase(renderer)) {
                return card.getVersionInfo();
            }

            if (version == null) {
                version = card.getVersionInfo();
            }
        }
        return version;
    }

    private static Support checkLinuxSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            EglUtils.load();
            if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
                return result(Support.UNSUPPORTED, "the game has no EGL context");
            }

            var eglDisplay = EglUtils.getDisplay();
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                return result(Support.UNSUPPORTED, "no EGL display");
            }

            var eglCapabilities = EglUtils.getCapabilities();
            if (!eglCapabilities.EGL_EXT_image_dma_buf_import || !eglCapabilities.EGL_KHR_image_base) {
                return result(Support.UNSUPPORTED, "EGL cannot import dmabufs");
            }

            var vendor = glString(GL11.GL_VENDOR);
            var renderer = glString(GL11.GL_RENDERER);
            CefRuntime.INSTANCE.LOGGER.info("GPU: {} ({})", renderer, vendor);

            if (vendor.toLowerCase(Locale.ENGLISH).contains("nvidia")) {
                return result(Support.OPT_IN, "NVIDIA's driver");
            }

            if (isOnOtherGpuThanDisplay(eglDisplay)) {
                return result(Support.OPT_IN, "the game runs on another GPU than the display");
            }

            return result(Support.DEFAULT, renderer);
        } catch (Exception e) {
            CefRuntime.INSTANCE.LOGGER.warn("Failed to check Linux GPU acceleration support: {}", e.getMessage());
            return Support.UNSUPPORTED;
        }
    }

    /**
     * Chromium's X11 platform allocates the shared texture through DRI3 on the X server's GPU, which a game
     * offloaded to another GPU cannot import (https://github.com/CCBlueX/LiquidBounce/issues/8080).
     */
    private static boolean isOnOtherGpuThanDisplay(long eglDisplay) {
        var capabilities = EGL.getCapabilities();
        if (capabilities.eglQueryDisplayAttribEXT == MemoryUtil.NULL
                || capabilities.eglQueryDeviceStringEXT == MemoryUtil.NULL) {
            return false;
        }

        String deviceFile;
        try (var stack = MemoryStack.stackPush()) {
            var device = stack.mallocPointer(1);
            if (!EXTDeviceQuery.eglQueryDisplayAttribEXT(eglDisplay, EXTDeviceQuery.EGL_DEVICE_EXT, device)) {
                return false;
            }

            deviceFile = EXTDeviceQuery.eglQueryDeviceStringEXT(device.get(0), EXTDeviceDRM.EGL_DRM_DEVICE_FILE_EXT);
        }

        if (deviceFile == null) {
            return false;
        }

        var drm = Path.of("/sys/class/drm");
        if (!"0".equals(readBootVga(drm.resolve(Path.of(deviceFile).getFileName())))) {
            return false;
        }

        try (var cards = Files.list(drm)) {
            return cards.filter(card -> card.getFileName().toString().matches("card\\d+"))
                .anyMatch(card -> "1".equals(readBootVga(card)));
        } catch (IOException e) {
            return false;
        }
    }

    private static @Nullable String readBootVga(Path card) {
        try {
            return Files.readString(card.resolve("device/boot_vga")).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Accelerated paint imports dmabufs through EGL, so on X11 the game's GL context has to be an EGL one.
     * This decides it before that context exists, against a display of its own on the same X server.
     */
    public static boolean isX11AcceleratedPaintPossible() {
        if (Util.getPlatform() != Util.OS.LINUX) {
            return false;
        }

        try {
            EglUtils.load();

            var getPlatformDisplay = EGL.getCapabilities().eglGetPlatformDisplay;
            if (getPlatformDisplay == MemoryUtil.NULL) {
                return false;
            }

            // LWJGL refuses EGL_DEFAULT_DISPLAY, with which EGL opens the default X display itself
            long display = JNI.callPPP(KHRPlatformX11.EGL_PLATFORM_X11_KHR, EGL14.EGL_DEFAULT_DISPLAY,
                    MemoryUtil.NULL, getPlatformDisplay);
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, (IntBuffer) null, null)) {
                return false;
            }

            try {
                var capabilities = EGL.createDisplayCapabilities(display);
                return capabilities.EGL_EXT_image_dma_buf_import && capabilities.EGL_KHR_image_base;
            } finally {
                EGL14.eglTerminate(display);
            }
        } catch (Throwable e) {
            CefRuntime.INSTANCE.LOGGER.warn("Failed to check X11 accelerated paint support: {}", e.getMessage());
            return false;
        }
    }

    private static Support checkMacOSSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            if (CGL.CGLGetCurrentContext() == 0L) {
                return result(Support.UNSUPPORTED, "no current CGL context");
            }

            var capabilities = GL.getCapabilities();
            if (!capabilities.OpenGL31 && !capabilities.GL_ARB_texture_rectangle) {
                return result(Support.UNSUPPORTED, "no GL_TEXTURE_RECTANGLE");
            }

            return result(Support.OPT_IN, "macOS");
        } catch (Throwable e) {
            CefRuntime.INSTANCE.LOGGER.warn("Failed to check macOS GPU acceleration support: {}", e.getMessage());
            return Support.UNSUPPORTED;
        }
    }

    private static String glString(int name) {
        return Objects.requireNonNullElse(GL11.glGetString(name), "");
    }

    private static boolean isNvidiaGpu(String vendor, String renderer) {
        var vendorLower = vendor.toLowerCase(Locale.ENGLISH);
        var rendererLower = renderer.toLowerCase(Locale.ENGLISH);
        return vendorLower.contains("nvidia")
            || rendererLower.contains("geforce")
            || rendererLower.contains("quadro");
    }

    private static boolean isAmdGpu(String vendor, String renderer) {
        var vendorLower = vendor.toLowerCase(Locale.ENGLISH);
        var rendererLower = renderer.toLowerCase(Locale.ENGLISH);
        return vendorLower.contains("amd")
            || vendorLower.contains("ati technologies")
            || vendorLower.contains("advanced micro devices")
            || rendererLower.contains("radeon");
    }
}
