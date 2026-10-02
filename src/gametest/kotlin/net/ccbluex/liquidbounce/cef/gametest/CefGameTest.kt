package net.ccbluex.liquidbounce.cef.gametest

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.cef.backend.CefBrowserBackend
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManager
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.theme.ThemeManager
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen

/**
 * Starts the client with the add-on and checks that Chromium shows the client's pages and takes clicks.
 */
class CefGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.input.resizeWindow(1600, 900)

        // Only set once the page loaded. Its state goes back to Idle when the client moves to another route.
        context.waitFor({ ScreenManager.mainBrowser != null }, 20 * 300)
        check(BrowserBackendManager.backend is CefBrowserBackend) {
            "The client uses ${BrowserBackendManager.backend} instead of Chromium"
        }
        context.waitFor({ ScreenManager.mainBrowser?.texture != null }, 20 * 60)
        context.waitTicks(60)
        context.takeScreenshot("Title")

        // The page's own Singleplayer button. Minecraft drops the first move of the cursor.
        context.input.setCursorPos(270.0, 220.0)
        context.input.setCursorPos(275.0, 225.0)
        context.waitTicks(5)
        context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
        context.waitFor({ ScreenManager.screen?.type == CustomScreenType.SINGLEPLAYER }, 20 * 30)
        context.waitTicks(60)
        context.takeScreenshot("Singleplayer")

        context.client { client ->
            ThemeManager.basicMode = true
            client.gui.setScreen(TitleScreen())
        }
        context.waitFor { it.gui.screen() is TitleScreen }
    }

    private fun ClientGameTestContext.client(block: (Minecraft) -> Unit) = runOnClient<RuntimeException> { block(it) }

}
