package net.ccbluex.liquidbounce.cef.backend

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.cef.CefAccelerationSupport
import net.ccbluex.liquidbounce.cef.CefAccelerationSupport.Support
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.WindowTitleEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManager
import net.ccbluex.liquidbounce.integration.backend.browser.GlobalBrowserSettings
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc

val isBrowserAccelerationDisabled = env("LB_BROWSER_DISABLE_ACCELERATION",
    "net.ccbluex.liquidbounce.browser.disableAcceleration")?.toBoolean() ?: false

/**
 * Whether Chromium hands its frames over as GPU textures instead of pixels.
 */
object AcceleratedPaint : EventListener {

    private val mode = GlobalBrowserSettings.enumChoice("AcceleratedPaint", Mode.AUTO).onChanged {
        mc.execute(::reload)
    }

    var support = Support.UNSUPPORTED
        private set

    val isEnabled: Boolean
        get() = when (mode.get()) {
            Mode.AUTO -> support == Support.DEFAULT
            Mode.ON -> support != Support.UNSUPPORTED
            Mode.OFF -> false
        }

    private val isActive: Boolean
        get() = BrowserBackendManager.backend is CefBrowserBackend && BrowserBackendManager.isInitialized

    internal fun detect() {
        if (isBrowserAccelerationDisabled) {
            logger.warn("Environment variable 'LB_BROWSER_DISABLE_ACCELERATION' is set to 'true'.")
            return
        }

        support = CefAccelerationSupport.getAccelerationSupport()
    }

    private fun reload() {
        if (isActive) {
            ScreenManager.restart()
            mc.updateTitle()
        }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (!event.isPressed || event.scanCode != InputConstants.KEY_F12 || inGame || !isActive) {
            return@handler
        }

        if (support == Support.UNSUPPORTED) {
            logger.warn("Accelerated paint is not supported on this system.")
            return@handler
        }

        mode.set(if (isEnabled) Mode.OFF else Mode.ON)
        logger.info("Accelerated paint is now ${if (isEnabled) "on" else "off"}.")
    }

    @Suppress("unused")
    private val titleHandler = handler<WindowTitleEvent> { event ->
        if (!isActive || !isEnabled) {
            return@handler
        }

        event.title.append(" | Accelerated Paint is ON")
        if (!inGame) {
            event.title.append(" [Hotkey: F12]")
        }
    }

    enum class Mode(override val tag: String) : Tagged {
        AUTO("Auto"),
        ON("On"),
        OFF("Off")
    }

}
