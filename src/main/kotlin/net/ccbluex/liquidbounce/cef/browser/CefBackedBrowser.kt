package net.ccbluex.liquidbounce.cef.browser

import net.ccbluex.liquidbounce.cef.CefRuntime
import net.ccbluex.liquidbounce.cef.backend.CefBrowserBackend
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.integration.backend.BrowserTexture
import net.ccbluex.liquidbounce.integration.backend.browser.Browser
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserRenderer
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.browser.GlobalBrowserSettings
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.backend.input.InputHandler
import net.ccbluex.liquidbounce.integration.backend.input.InputListener
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.cef.browser.CefRequestContext
import org.joml.component1
import org.joml.component2

@Suppress("TooManyFunctions")
class CefBackedBrowser(
    private val backend: CefBrowserBackend,
    url: String,
    viewport: BrowserViewport,
    val settings: BrowserSettings,
    override var priority: Short = 0,
    override val isIncognito: Boolean = false,
    inputAcceptor: InputAcceptor? = null
) : Browser, InputHandler, MinecraftShortcuts {

    internal val browserApi: CefOffscreenBrowser
    private val logger: Logger

    /**
     * Request context of an incognito browser, disposed along with it.
     *
     * A context created this way has no cache path, so CEF keeps its cookies, local storage and cache
     * in memory and throws all of it away with the context. The global context, which every other
     * browser shares, persists them to disk instead.
     */
    private val requestContext: CefRequestContext? =
        if (isIncognito) CefRequestContext.createContext(null) else null

    init {
        require(url.isNotEmpty()) { "URL cannot be empty." }
        val quality = GlobalBrowserSettings.quality
        val (width, height) = viewport.getScaledDimensions(quality)
        browserApi = CefRuntime.INSTANCE.createBrowser(
            url,
            true,
            width,
            height,
            CefOffscreenBrowserSettings(
                settings.currentFps,
                GlobalBrowserSettings.accelerated?.get() == true
            ),
            requestContext
        ).apply {
            addOnPaintListener {
                comparePaintWithViewpoint(it.width, it.height)
            }
            addOnAcceleratedPaintListener {
                comparePaintWithViewpoint(it.width, it.height)
            }
        }

        logger = LogManager.getLogger("LiquidBounce/CefBackedBrowser/${browserApi.hashCode()}")
        logger.info("Initializing Browser API (url='$url')")
    }

    override var isInitialized: Boolean = false
        internal set(value) {
            require(!field) { "Browser $this is already initialized." }
            require(value) { "Cannot uninitialize browser $this." }

            // https://magpcss.org/ceforum/viewtopic.php?f=17&t=17702
            browserApi.loadURL(url)

            val quality = GlobalBrowserSettings.quality
            browserApi.zoomLevel = viewport.getZoomLevel(quality)
            field = true

            logger.info("Initialized Browser API")
        }

    override var state: BrowserState = BrowserState.Idle
        internal set(value) {
            field = value

            when (value) {
                is BrowserState.Loading ->
                    logger.info("Started loading (url='${url}')")
                is BrowserState.Success ->
                    logger.info("Finished loading (url='${url}', httpStatusCode=${value.httpStatusCode})")
                is BrowserState.Failure ->
                    logger.warn("Failed to load " +
                        "(url='${value.failedUrl}', errorCode=${value.errorCode}, errorText=${value.errorText})")
                else -> { /* Idle state, do nothing */ }
            }
        }

    override var viewport: BrowserViewport = viewport
        set(value) {
            field = value

            val quality = GlobalBrowserSettings.quality
            val (scaledWidth, scaledHeight) = value.getScaledDimensions(quality)
            val zoomLevel = value.getZoomLevel(quality)

            val viewRect = browserApi.getViewRect(null)
            // Check if the browser dimensions have changed
            if (viewRect.width == scaledWidth && viewRect.height == scaledHeight) {
                return
            }

            // TODO: CEF is suffering from a bug where resizing the browser,
            //   does not call [wasResized] and thus does not update the renderer.
            //   See: https://github.com/chromiumembedded/cef/issues/3826
            browserApi.resize(scaledWidth, scaledHeight)
            browserApi.zoomLevel = zoomLevel

            // To ensure the texture is updated, we clear the renderer. This call invalidates the
            // current UI.
            browserApi.clear()

            logger.debug(
                "Browser {} viewport updated: {}, scaled to {} x {} at zoom level {}",
                this,
                value,
                scaledWidth,
                scaledHeight,
                zoomLevel
            )
        }
    override var visible = true

    private val renderer = BrowserRenderer(this)
    private val inputListener: InputListener? = inputAcceptor?.let { _ ->
        InputListener(this, this, inputAcceptor)
    }

    override var url: String
        get() = browserApi.url
        set(value) {
            if (!isInitialized) {
                logger.warn("Cannot set URL of uninitialized browser $this.")
                // We continue anyway, because the browser API might accept it anyway.
            }

            state = BrowserState.Idle
            browserApi.loadURL(value)
        }

    override val texture: BrowserTexture?
        get() {
            if (!browserApi.renderer.isTextureReady || browserApi.renderer.isUnpainted) {
                return null
            }

            return BrowserTexture(
                browserApi.renderer.textureSetup!!,
                viewport.width,
                viewport.height,
                browserApi.renderer.isBGRA,
            )
        }

    override fun forceReload() {
        browserApi.reloadIgnoreCache()
    }

    override fun reload() {
        browserApi.reload()
    }

    override fun goForward() {
        browserApi.goForward()
    }

    override fun goBack() {
        browserApi.goBack()
    }

    override fun close() {
        renderer.close()
        inputListener?.close()
        backend.removeBrowser(this)
        browserApi.close()

        // Only after the browser is gone, since the context outlives nothing else.
        requestContext?.dispose()
    }

    override fun update(width: Int, height: Int) {
        if (!viewport.fullScreen) {
            return
        }

        viewport = viewport.copy(width = width, height = height)
    }

    override fun invalidate() {
        browserApi.clear()
    }

    override fun toString() = "CefBackedBrowser(" +
        "hash='${browserApi.hashCode()}', " +
        "id='${browserApi.identifier}', " +
        "url='$url', " +
        "incognito=$isIncognito, " +
        "visible=$visible, " +
        "priority=$priority" +
        ")"

    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int) {
        browserApi.setFocus(true)
        val (scaledX, scaledY) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        browserApi.sendMousePress(scaledX, scaledY, mouseButton)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int) {
        browserApi.setFocus(true)
        val (scaledX, scaledY) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        browserApi.sendMouseRelease(scaledX, scaledY, mouseButton)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        val (scaledX, scaledY) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        browserApi.sendMouseMove(scaledX, scaledY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double) {
        val (scaledX, scaledY) = viewport.transformMouse(mouseX, mouseY, GlobalBrowserSettings.quality)
        browserApi.sendMouseWheel(scaledX, scaledY, delta)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) {
        browserApi.setFocus(true)
        browserApi.sendKeyPress(scanCode, keyCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int) {
        browserApi.setFocus(true)
        browserApi.sendKeyRelease(scanCode, keyCode, modifiers)
    }

    override fun charTyped(codepoint: Int) {
        browserApi.setFocus(true)
        browserApi.sendKeyTyped(codepoint)
    }

    private fun comparePaintWithViewpoint(width: Int, height: Int) {
        val (scaledWidth, scaledHeight) = viewport.getScaledDimensions(GlobalBrowserSettings.quality)

        if (scaledWidth != width || scaledHeight != height) {
            logger.warn("Browser $this viewport size mismatch: " +
                "expected $scaledWidth x $scaledHeight, but got $width x $height. ")
            invalidate()
        }
    }

}
