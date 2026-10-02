package net.ccbluex.liquidbounce.cef

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
    }

}
