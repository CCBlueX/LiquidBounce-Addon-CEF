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

import net.ccbluex.liquidbounce.cef.browser.*;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefRequestContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * An API to create Chromium web browsers in Minecraft. Uses
 * a modified version of java-cef (Java Chromium Embedded Framework).
 */
@NullMarked
public enum CefRuntime {

    INSTANCE;

    public final Logger LOGGER = LoggerFactory.getLogger("LiquidBounce/CEF");
    private @Nullable CefRuntimeSettings settings;
    private @Nullable CefAppWrapper app;
    private @Nullable CefClientWrapper client;
    private @Nullable CefNativesManager resourceManager;
    
    public Logger getLogger() {
        return LOGGER;
    }

    public static final Minecraft mc = Minecraft.getInstance();

    /**
     * Get access to various settings for CefRuntime.
     * @return Returns the existing {@link CefRuntimeSettings} or creates a new {@link CefRuntimeSettings} and loads from disk (blocking)
     */
    public CefRuntimeSettings getSettings() {
        if (settings == null) {
            settings = new CefRuntimeSettings();
        }

        return settings;
    }

    public CefNativesManager newResourceManager() throws IOException {
        return resourceManager = CefNativesManager.newResourceManager();
    }

    public boolean initialize() {
        LOGGER.info("Initializing CEF on " + CefPlatform.getPlatform().getNormalizedName() + "...");

        if (CefHelper.init()) {
            app = new CefAppWrapper(CefHelper.getCefApp());
            client = new CefClientWrapper(CefHelper.getCefClient());

            LOGGER.info("Chromium Embedded Framework initialized");

            // Handle shutdown events, macOS is special
            // These are important; the jcef process will linger around if not done
            CefPlatform platform = CefPlatform.getPlatform();
            if (platform.isLinux() || platform.isWindows()) {
                Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "CEF-Shutdown"));
            } else if (platform.isMacOS()) {
                CefHelper.getCefApp().macOSTerminationRequestRunnable = () -> {
                    shutdown();
                    Minecraft.getInstance().stop();
                };
            }

            return true;
        }

        LOGGER.info("Could not initialize Chromium Embedded Framework");
        shutdown();
        return false;
    }

    /**
     * Will assert that CEF has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link CefAppWrapper} instance
     */
    public CefAppWrapper getApp() {
        assertInitialized();
        assert app != null;
        return app;
    }

    /**
     * Will assert that CEF has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link CefClientWrapper} instance
     */
    public CefClientWrapper getClient() {
        assertInitialized();
        assert client != null;
        return client;
    }

    public @Nullable CefNativesManager getResourceManager() {
        return resourceManager;
    }

    /**
     * Will assert that CEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL. Can set it to be transparent rendering.
     * @return the {@link CefOffscreenBrowser} web browser instance
     */
    public CefOffscreenBrowser createBrowser(String url, boolean transparent, @Nullable CefOffscreenBrowserSettings browserSettings) {
        return createBrowser(url, transparent, browserSettings, null);
    }

    /**
     * Will assert that CEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser in the given request context.
     * @param requestContext the request context to load the browser in, or null for the global one.
     *                       A context from {@link CefRequestContext#createContext} keeps its cookies
     *                       and storage in memory only, which is how a private session is made.
     * @return the {@link CefOffscreenBrowser} web browser instance
     */
    public CefOffscreenBrowser createBrowser(String url, boolean transparent, @Nullable CefOffscreenBrowserSettings browserSettings,
                                     @Nullable CefRequestContext requestContext) {
        assertInitialized();
        assert client != null;
        if (browserSettings == null) {
            browserSettings = new CefOffscreenBrowserSettings(60, false);
        }
        CefOffscreenBrowser browser = new CefOffscreenBrowser(client, url, transparent, browserSettings, requestContext);
        browser.setCloseAllowed();
        browser.createImmediately();
        return browser;
    }

    /**
     * Will assert that CEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL, width, and height.
     * Can set it to be transparent rendering.
     * @return the {@link CefOffscreenBrowser} web browser instance
     */
    public CefOffscreenBrowser createBrowser(String url, boolean transparent, int width, int height,
                                     @Nullable CefOffscreenBrowserSettings browserSettings) {
        return createBrowser(url, transparent, width, height, browserSettings, null);
    }

    /**
     * Will assert that CEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL, width, and height, in the given
     * request context.
     * @return the {@link CefOffscreenBrowser} web browser instance
     */
    public CefOffscreenBrowser createBrowser(String url, boolean transparent, int width, int height,
                                     @Nullable CefOffscreenBrowserSettings browserSettings,
                                     @Nullable CefRequestContext requestContext) {
        var browser = createBrowser(url, transparent, browserSettings, requestContext);
        browser.resize(width, height);
        return browser;
    }

    /**
     * Check if CEF is initialized.
     * @return true if CEF is initialized correctly, false if not
     */
    public boolean isInitialized() {
        return client != null;
    }

    /**
     * Request a shutdown of CEF. Nothing will happen if not initialized.
     */
    public void shutdown() {
        if (isInitialized()) {
            CefHelper.shutdown();
            client = null;
            app = null;
        }
    }

    /**
     * Check if CEF has been initialized, throws a {@link RuntimeException} if not.
     */
    private void assertInitialized() {
        if (!isInitialized()) {
            throw new RuntimeException("Chromium Embedded Framework was never initialized.");
        }
    }

    /**
     * Get the git commit hash of the java-cef code (either from MANIFEST.MF or from the git repo on-disk if in a
     * development environment). Used for downloading the java-cef release.
     * @return The git commit hash of java-cef
     * @throws IOException
     */
    public @Nullable String getJavaCefCommit() throws IOException {
        // Find jcef.commit file in the JAR root
        var commitResource = CefRuntime.class.getClassLoader().getResource("jcef.commit");
        if (commitResource != null) {
            return new BufferedReader(new InputStreamReader(commitResource.openStream())).readLine();
        }

        return null;
    }

}
