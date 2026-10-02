package net.ccbluex.liquidbounce.cef.download;

import net.ccbluex.liquidbounce.cef.CefNativesManager;
import net.ccbluex.liquidbounce.cef.CefPlatform;

import java.io.File;

public class CefProvidedNativesManager extends CefNativesManager {
    private final File path;

    public CefProvidedNativesManager(File path, String[] hosts, String javaCefCommitHash, CefPlatform platform, File directory) {
        super(hosts, javaCefCommitHash, platform, directory);

        this.path = path;
    }

    @Override
    public void downloadJcef() {
    }

    @Override
    public boolean requiresDownload() {
        return false;
    }

    @Override
    public File getPlatformDirectory() {
        return this.path;
    }
}
