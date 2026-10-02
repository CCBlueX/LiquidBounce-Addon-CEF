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

import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.NullMarked;

/**
 * TextureManager adapter for the renderer-owned browser texture.
 */
@NullMarked
final class CefGpuTexture extends AbstractTexture {
    private final CefTextureRenderer renderer;

    CefGpuTexture(CefTextureRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public GpuTexture getTexture() {
        var texture = renderer.getTexture();
        if (texture == null) {
            throw new IllegalStateException("CEF texture is not initialized");
        }

        return texture;
    }

    @Override
    public GpuTextureView getTextureView() {
        var textureView = renderer.getTextureView();
        if (textureView == null) {
            throw new IllegalStateException("CEF texture view is not initialized");
        }

        return textureView;
    }

    @Override
    public GpuSampler getSampler() {
        var sampler = renderer.getSampler();
        if (sampler == null) {
            throw new IllegalStateException("CEF sampler is not initialized");
        }

        return sampler;
    }

    @Override
    public void close() {
        // The renderer owns and closes the underlying GPU resources.
    }
}
