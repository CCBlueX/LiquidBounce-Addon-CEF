package net.ccbluex.liquidbounce.cef.backend

import net.ccbluex.liquidbounce.api.core.HttpClient
import net.ccbluex.liquidbounce.api.interceptors.DefaultHeaderInterceptor
import net.ccbluex.liquidbounce.cef.CefAccelerationSupport
import net.ccbluex.liquidbounce.cef.CefRuntime
import net.ccbluex.liquidbounce.cef.browser.CefBackedBrowser
import net.ccbluex.liquidbounce.cef.download.CefNativesProgressForwarder
import net.ccbluex.liquidbounce.cef.download.HashValidator
import net.ccbluex.liquidbounce.cef.error.CefQuickFixes
import net.ccbluex.liquidbounce.cef.error.JcefIsntCompatible
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.integration.backend.BrowserAccelerationFlags
import net.ccbluex.liquidbounce.integration.backend.BrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserState
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserViewport
import net.ccbluex.liquidbounce.integration.backend.input.InputAcceptor
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.error.ErrorHandler
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.sortedInsert
import net.ccbluex.liquidbounce.utils.text.formatAsCapacity
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.io.File

/**
 * The time threshold for cleaning up old cache directories.
 */
private const val CACHE_CLEANUP_THRESHOLD = 1000 * 60 * 60 * 24 * 7 // 7 days

/**
 * Uses a modified fork of the JCEF library browser backend made for Minecraft.
 * This browser backend is based on Chromium and is the most advanced browser backend.
 * JCEF is available through [CefRuntime], which makes it work inside Minecraft.
 *
 * @see <a href="https://github.com/CCBlueX/java-cef/">JCEF</a>
 *
 * @author Izuna <izuna.seikatsu@ccbluex.net>
 */
@Suppress("TooManyFunctions")
class CefBrowserBackend : BrowserBackend, EventListener {

    private val cefFolder = ConfigSystem.rootFolder.resolve("mcef")
    // The game tests keep it outside the game directory, which they wipe before every run
    private val librariesFolder = env("LB_BROWSER_LIBRARIES", "net.ccbluex.liquidbounce.browser.libraries")
        ?.let(::File) ?: cefFolder.resolve("libraries")
    private val cacheFolder = cefFolder.resolve("cache")

    override val isInitialized: Boolean
        get() = CefRuntime.INSTANCE.isInitialized
    override var browsers = mutableListOf<CefBackedBrowser>()
    override var accelerationFlags = BrowserAccelerationFlags.UNSUPPORTED

    @Suppress("ThrowingExceptionsWithoutMessageOrCause")
    override fun makeDependenciesAvailable(taskManager: TaskManager, whenAvailable: () -> Unit) {
        // Clean up old cache directories
        cleanup()

        if (!CefRuntime.INSTANCE.isInitialized) {
            CefRuntime.INSTANCE.settings.apply {
                userAgent = HttpClient.DEFAULT_AGENT
                // The natives download reads the status of its range probes itself, the API client would throw on them
                okHttpClient = HttpClient.client.newBuilder()
                    .apply { interceptors().removeAll { it !is DefaultHeaderInterceptor } }
                    .build()
                cacheDirectory = cacheFolder.resolve(System.currentTimeMillis().toString(16)).apply {
                    deleteOnExit()
                }
                librariesDirectory = librariesFolder

                // CEF Switches
                appendCefSwitches("--no-proxy-server")
            }

            val resourceManager = CefRuntime.INSTANCE.newResourceManager()

            // Check if system is compatible with JCEF
            if (!resourceManager.isSystemCompatible) {
                throw JcefIsntCompatible()
            }

            HashValidator.validateFolder(resourceManager.commitDirectory)

            if (resourceManager.requiresDownload()) {
                taskManager.launch("CEF") { task ->
                    resourceManager.registerProgressListener(CefNativesProgressForwarder(task))

                    runCatching {
                        resourceManager.downloadJcef()
                        mc.execute(whenAvailable)
                    }.onFailure {
                        ErrorHandler.fatal(
                            error = it,
                            quickFix = CefQuickFixes.DOWNLOAD_JCEF_FAILED,
                            additionalMessage = "Downloading jcef"
                        )
                    }
                }
            } else {
                whenAvailable()
            }
        }
    }

    /**
     * Cleans up old cache directories.
     *
     * TODO: Check if we have an active PID using the cache directory, if so, check if the LiquidBounce
     *   process attached to the JCEF PID is still running or not. If not, we could kill the JCEF process
     *   and clean up the cache directory.
     */
    fun cleanup() {
        if (cacheFolder.exists()) {
            runCatching {
                cacheFolder.listFiles { file ->
                    file.isDirectory && System.currentTimeMillis() - file.lastModified() > CACHE_CLEANUP_THRESHOLD
                }?.sumOf { file ->
                    try {
                        val fileSize = file.walkTopDown().sumOf { uFile -> uFile.length() }
                        file.deleteRecursively()
                        fileSize
                    } catch (e: Exception) {
                        logger.error("Failed to clean up old cache directory", e)
                        0
                    }
                } ?: 0
            }.onFailure {
                // Not a big deal, not fatal.
                logger.error("Failed to clean up old JCEF cache directories", it)
            }.onSuccess { size ->
                if (size > 0) {
                    logger.info("Cleaned up ${size.formatAsCapacity()} JCEF cache directories")
                }
            }
        }
    }

    override fun start() {
        if (!CefRuntime.INSTANCE.isInitialized) {
            CefRuntime.INSTANCE.initialize()

            CefRuntime.INSTANCE.client.handle.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(cefBrowser: CefBrowser) {
                    markInitialized(cefBrowser)
                    super.onAfterCreated(cefBrowser)
                }
            })

            CefRuntime.INSTANCE.client.addLoadHandler(object : CefLoadHandlerAdapter() {

                override fun onLoadStart(
                    cefBrowser: CefBrowser, frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?
                ) {
                    updateStateForBrowser(cefBrowser, BrowserState.Loading)
                    super.onLoadStart(cefBrowser, frame, transitionType)
                }

                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
                    updateStateForBrowser(cefBrowser, BrowserState.Success(httpStatusCode))
                    super.onLoadEnd(cefBrowser, frame, httpStatusCode)
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser, frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?
                ) {
                    updateStateForBrowser(
                        cefBrowser,
                        BrowserState.Failure(
                            errorCode?.code ?: -1,
                            errorText ?: "Unknown Error",
                            failedUrl ?: "Unknown URL"
                        )
                    )
                    super.onLoadError(cefBrowser, frame, errorCode, errorText, failedUrl)
                }

            })
        }

        accelerationFlags = when (CefAccelerationSupport.getAccelerationSupport()) {
            CefAccelerationSupport.Support.UNSUPPORTED -> BrowserAccelerationFlags.UNSUPPORTED
            CefAccelerationSupport.Support.OPT_IN -> BrowserAccelerationFlags(isSupported = true, isBeta = true)
            CefAccelerationSupport.Support.DEFAULT -> BrowserAccelerationFlags(isSupported = true, isBeta = false)
        }
    }

    override fun stop() {
        CefRuntime.INSTANCE.shutdown()
        CefRuntime.INSTANCE.settings.cacheDirectory?.deleteRecursively()
    }

    override fun update() {
        if (CefRuntime.INSTANCE.isInitialized) {
            try {
                CefRuntime.INSTANCE.app.handle.N_DoMessageLoopWork()
            } catch (e: Exception) {
                logger.error("Failed to draw browser globally", e)
            }
        }
    }

    override val supportsIncognito = true

    override fun createBrowser(
        url: String,
        position: BrowserViewport,
        settings: BrowserSettings,
        priority: Short,
        incognito: Boolean,
        inputAcceptor: InputAcceptor?
    ) = CefBackedBrowser(this, url, position, settings, priority, incognito, inputAcceptor)
        .apply(::addBrowser)

    private fun addBrowser(browser: CefBackedBrowser) {
        browsers.sortedInsert(browser, CefBackedBrowser::priority)
    }

    internal fun removeBrowser(browser: CefBackedBrowser) {
        browsers.remove(browser)
    }

    fun getBrowserByApi(apiInstance: CefBrowser) = browsers.find { it.browserApi == apiInstance }

    private fun markInitialized(apiInstance: CefBrowser) {
        val browser = getBrowserByApi(apiInstance)
        if (browser != null) {
            if (!browser.isInitialized) {
                browser.isInitialized = true
            }
        } else {
            logger.warn("[CefBrowser-${apiInstance.hashCode()}] Browser Instance not present in BrowserManager")
        }
    }

    private fun updateStateForBrowser(apiInstance: CefBrowser, state: BrowserState) {
        val browser = getBrowserByApi(apiInstance)
        if (browser != null) {
            browser.state = state
        } else {
            logger.warn("[CefBrowser-${apiInstance.hashCode()}] Browser Instance not present in BrowserManager")
        }
    }

}
