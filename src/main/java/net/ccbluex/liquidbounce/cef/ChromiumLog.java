/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2026 CCBlueX
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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Copies what Chromium and its helper processes write to their log file into the game log. Launchers rarely keep
 * a native process's stderr, so without this a GPU or renderer process that fails leaves no trace in
 * {@code latest.log}.
 */
@NullMarked
final class ChromiumLog implements Runnable {

    // [pid:tid:MMDD/HHMMSS.micros:SEVERITY:file.cc:line] message
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+):\\d+:[^:]*:([A-Z_]+):([^]]+)] ?(.*)$");
    private static final int MAX_PER_SOURCE = 5;
    private static final long POLL_MILLIS = 250;

    private final Logger logger = LoggerFactory.getLogger("LiquidBounce/Chromium");
    private final File file;
    private final Map<String, Integer> countBySource = new HashMap<>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private @Nullable String lastSeverity;
    private long position;
    private volatile boolean running = true;

    private ChromiumLog(File file) {
        this.file = file;
        // Left over when a process of an earlier run still holds the file, which Windows does not let us delete
        this.position = file.length();
    }

    static ChromiumLog start(File file) {
        var log = new ChromiumLog(file);
        var thread = new Thread(log, "CEF-Log");
        thread.setDaemon(true);
        thread.start();
        return log;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            readNew();
        }
        // What the processes wrote while shutting down
        readNew();
    }

    private void readNew() {
        if (!file.isFile()) {
            return;
        }

        try (var raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < position) {
                position = 0;
            }
            raf.seek(position);

            var buffer = new byte[8192];
            int read;
            while ((read = raf.read(buffer)) > 0) {
                position += read;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        forward(pending.toString(StandardCharsets.UTF_8));
                        pending.reset();
                    } else {
                        pending.write(buffer[i]);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read {}: {}", file, e.getMessage());
            running = false;
        }
    }

    private void forward(String line) {
        if (line.isBlank()) {
            return;
        }

        var matcher = LINE.matcher(line);
        if (!matcher.matches()) {
            // The rest of a message that spans several lines
            if (lastSeverity != null) {
                log(lastSeverity, line);
            }
            return;
        }

        var severity = matcher.group(2);
        var source = matcher.group(3);
        int count = countBySource.merge(source, 1, Integer::sum);
        if (count > MAX_PER_SOURCE) {
            lastSeverity = null;
            return;
        }

        lastSeverity = severity;
        var message = "[pid " + matcher.group(1) + "] " + source + ": " + matcher.group(4);
        log(severity, count == MAX_PER_SOURCE ? message + " (further messages from here are not shown)" : message);
    }

    private void log(String severity, String message) {
        switch (severity) {
            case "FATAL", "ERROR" -> logger.error(message);
            case "WARNING" -> logger.warn(message);
            default -> logger.info(message);
        }
    }

}
