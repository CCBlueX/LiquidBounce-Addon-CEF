package net.ccbluex.liquidbounce.cef

import net.ccbluex.liquidbounce.cef.backend.AcceleratedPaint
import net.ccbluex.liquidbounce.cef.backend.CefBrowserBackend
import net.ccbluex.liquidbounce.cef.error.CefQuickFixes
import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendProvider

/**
 * Offers Chromium, through CEF, as the browser of the client's interface. LiquidBounce comes with it.
 */
class CefAddon : LiquidBounceAddon() {

    override fun onInitialize() {
        registerQuickFix(CefQuickFixes.JCEF_ISNT_COMPATIBLE_WITH_THAT_SYSTEM)
        registerQuickFix(CefQuickFixes.D3D11_UNSATISFIED_LINK)
        registerQuickFix(CefQuickFixes.JCEF_UNSATISFIED_LINK)

        registerBrowserBackend(
            BrowserBackendProvider(
                "cef",
                "Chromium",
                "The browser LiquidBounce comes with (Chromium).",
                create = ::CefBrowserBackend
            )
        )

        // Its setting has to exist before the global settings load, which happens ahead of the first browser
        AcceleratedPaint
    }

}
