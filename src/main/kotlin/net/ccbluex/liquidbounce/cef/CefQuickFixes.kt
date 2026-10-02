package net.ccbluex.liquidbounce.cef

import net.ccbluex.liquidbounce.utils.client.error.Instructions
import net.ccbluex.liquidbounce.utils.client.error.QuickFix

object CefQuickFixes {

    val JCEF_ISNT_COMPATIBLE_WITH_THAT_SYSTEM = QuickFix(
        description = "Your system isn't compatible with JCEF",
        testError = { it is JcefIsntCompatible },
        whatYouNeed = Instructions(false) { _ ->
            arrayOf(
                "A 64-bit computer",
                "Windows 10 or newer, macOS 10.15 or newer, or a Linux system"
            )
        },
        whatToDo = Instructions(false) { _ ->
            arrayOf(
                "Please update your operating system to a never version"
            )
        }
    )

    val DOWNLOAD_JCEF_FAILED = QuickFix(
        description = "A fatal error occurred while loading libraries required for JCEF to work",
        whatYouNeed = Instructions(true) { _ ->
            arrayOf(
                "Stable internet connection",
                "Free space on the disk"
            )
        },
        whatToDo = Instructions(true) { _ ->
            arrayOf(
                "Check your internet connection",
                "Use a VPN such as Cloudflare Warp or another one",
                "Check if there is free space on the disk",
                "Make sure that the client folder is not blocked by the file system"
            )
        }
    )

    val D3D11_UNSATISFIED_LINK = QuickFix(
        description = "D3D11 not installed",
        testError = { throwable ->
            throwable is UnsatisfiedLinkError && throwable.message?.contains("d3dcompiler_47.dll") == true
        },
        whatToDo = Instructions(true) {
            // Tracking issue: https://github.com/CCBlueX/LiquidBounce/issues/6841
            // For some reason, this seems to always happen for Russian users.
            // We were never able to reproduce this on a clean Windows install.
            arrayOf(
                "Install Windows Updates",
                "Install DirectX End-User Runtime",
                "Install C++ Redistributable for Visual Studio 2017–2026",
                "Restart LiquidBounce and try again."
            )
        }
    )

    val JCEF_UNSATISFIED_LINK = QuickFix(
        description = "Windows Application control policy is blocking JCEF",
        testError = { throwable ->
            // This is not an accurate check, since there can be other causes to fail on jcef.dll; however,
            //   we found that this issue happens with "An Application Control policy has blocked this file"
            //   the most.
            throwable is UnsatisfiedLinkError && throwable.message?.contains("jcef.dll") == true
        },
        whatToDo = Instructions(true) {
            arrayOf(
                "Open Windows Security",
                "Navigate to App & browser control.",
                "Click on Smart App Control settings.",
                "Set Smart App Control to 'Off' and confirm if asked.",
                "Restart LiquidBounce and try again."
            )
        }
    )

}
