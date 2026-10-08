/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package net.ccbluex.liquidbounce.cef.browser;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.ccbluex.liquidbounce.cef.CefRuntime;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.handler.CefAcceleratedPaintInfoWin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;

@NullMarked
final class WindowsAcceleratedPaintBackend implements AcceleratedPaintBackend {
    private static final long SHARED_TEXTURE_IMPORT_SIZE = 0L;
    private static final long COPY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

    @Override
    public boolean accepts(CefAcceleratedPaintInfo info) {
        return info instanceof CefAcceleratedPaintInfoWin;
    }

    @Override
    public @Nullable AcceleratedPaintFrame importFrame(CefAcceleratedPaintInfo info, int width, int height) {
        var winInfo = (CefAcceleratedPaintInfoWin) info;
        if (winInfo.shared_texture_handle == 0) {
            CefRuntime.INSTANCE.LOGGER.warn("Accelerated paint shared texture handle is invalid.");
            return null;
        }

        return importSharedTexture(winInfo.shared_texture_handle, width, height);
    }

    @Override
    public void close() {
    }

    private @Nullable AcceleratedPaintFrame importSharedTexture(long sharedTextureHandle, int width, int height) {
        var sharedTextureId = glGenTextures();

        var memoryObject = glCreateMemoryObjectsEXT();
        if (memoryObject == 0) {
            CefRuntime.INSTANCE.LOGGER.error("Failed to create memory object for shared texture.");
            glDeleteTextures(sharedTextureId);
            return null;
        }

        // A D3D11 texture is a dedicated allocation, which EXT_external_objects requires the import to say
        glMemoryObjectParameteriEXT(memoryObject, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
        glImportMemoryWin32HandleEXT(
                memoryObject,
                SHARED_TEXTURE_IMPORT_SIZE,
                GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                sharedTextureHandle
        );

        var error = glGetError();
        if (error != GL_NO_ERROR) {
            CefRuntime.INSTANCE.LOGGER.error("glImportMemoryWin32HandleEXT failed with error: {}", error);
            glDeleteTextures(sharedTextureId);
            glDeleteMemoryObjectsEXT(memoryObject);
            return null;
        }

        GlStateManager._bindTexture(sharedTextureId);
        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,
                1,
                GL_RGBA8,
                width,
                height,
                memoryObject,
                0
        );

        error = glGetError();
        if (error != GL_NO_ERROR) {
            CefRuntime.INSTANCE.LOGGER.error("glTexStorageMem2DEXT failed with error: {}", error);
            glDeleteTextures(sharedTextureId);
            glDeleteMemoryObjectsEXT(memoryObject);
            GlStateManager._bindTexture(0);
            return null;
        }

        GlStateManager._bindTexture(0);

        var directTexture = new CefDirectTexture();
        directTexture.setOwnedDirectTextureId(sharedTextureId, width, height);
        return new AcceleratedPaintFrame(directTexture.getTexture(), true, () -> {
            // Chromium draws into this texture again once the paint callback returns, and nothing orders that
            // after our copy, so it has to be done by then (chromiumembedded/cef#3755). The memory object goes
            // last: AMD's driver faults reading a texture whose memory object is already deleted.
            var fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, COPY_TIMEOUT_NANOS);
            glDeleteSync(fence);
            directTexture.close();
            glDeleteMemoryObjectsEXT(memoryObject);
        });
    }

}
