/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
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

package net.ccbluex.liquidbounce.cef.utils;

import net.ccbluex.liquidbounce.cef.MultiPartDownloadConfig;
import net.ccbluex.liquidbounce.cef.listeners.CefNativesProgressListener;
import net.ccbluex.liquidbounce.utils.io.PathExtKt;
import okhttp3.*;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLongArray;

final class MultiPartDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiPartDownloader.class);

    private static final int DOWNLOAD_LOG_OFF = 0;
    private static final int DOWNLOAD_LOG_INFO = 1;
    private static final int DOWNLOAD_LOG_DEBUG = 2;
    private static final int DOWNLOAD_LOG_LEVEL = DOWNLOAD_LOG_INFO;

    private static final String ACCEPT_ENCODING = "Accept-Encoding";
    private static final String ACCEPT_RANGES = "Accept-Ranges";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String CONTENT_RANGE = "Content-Range";
    private static final String RANGE = "Range";
    private static final int HTTP_PARTIAL_CONTENT = 206;
    private static final long PROGRESS_UPDATE_INTERVAL_NANOS = 100_000_000L;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final OkHttpClient client;
    private final MultiPartDownloadConfig config;
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();

    MultiPartDownloader(OkHttpClient client, MultiPartDownloadConfig config) {
        this.client = client;
        this.config = config;
    }

    boolean canAttempt() {
        return config.enabled() && config.maxConcurrency() > 1;
    }

    boolean download(CefNativesProgressListener progressListener, String task, HttpUrl url, File outputFile) throws IOException {
        if (!canAttempt()) {
            logDebug("Multipart download disabled: enabled={}, maxConcurrency={}",
                    config.enabled(), config.maxConcurrency());
            return false;
        }

        var headContentLength = fetchHeadContentLengthOrNull(url);
        if (headContentLength != null && partCountFor(headContentLength) <= 1) {
            logDebug("Multipart download skipped for task '{}': content length {} produces fewer than 2 parts",
                    task, headContentLength);
            return false;
        }

        var metadata = fetchMetadataOrNull(url, headContentLength);
        if (metadata == null) {
            logInfo("Multipart download unavailable for task '{}'; falling back to single request", task);
            return false;
        }
        var parts = split(metadata.contentLength());
        if (parts.length <= 1) {
            logDebug("Multipart download skipped for task '{}': content length {} produced {} part",
                    task, metadata.contentLength(), parts.length);
            return false;
        }

        logInfo("Starting multipart download for task '{}': contentLength={}, parts={}",
                task, metadata.contentLength(), parts.length);

        makeParentDirectories(outputFile);
        var tempFile = new File(outputFile.getCanonicalPath() + ".download").toPath();
        Files.deleteIfExists(tempFile);
        Files.createFile(tempFile);

        progressListener.onFileStart(task);

        var reporter = new ProgressReporter(progressListener, task, metadata.contentLength(), parts.length);
        var futures = new ArrayList<Future<?>>(parts.length);

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("CEF Downloader ", 0).factory())) {
            // TODO: change to StructuredScope after it's stable
            var completionService = new ExecutorCompletionService<Void>(executor);

            try (var file = new RandomAccessFile(tempFile.toFile(), "rw");
                 var channel = file.getChannel()) {
                file.setLength(metadata.contentLength());

                for (var part : parts) {
                    futures.add(completionService.submit(new PartDownload(part, reporter, url, channel, metadata.contentLength())));
                }

                waitForParts(completionService, parts.length);
                reporter.finish();
            }

            PathExtKt.atomicMoveTo(tempFile, outputFile.toPath());
            progressListener.onFileEnd(task);
            logInfo("Finished multipart download for task '{}': contentLength={}, parts={}",
                    task, metadata.contentLength(), parts.length);
            return true;
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            logInfo("Multipart download failed for task '{}': {}", task, e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(tempFile);
            logInfo("Multipart download interrupted for task '{}'", task);
            throw new IOException("Interrupted while downloading " + url, e);
        } catch (ExecutionException e) {
            Files.deleteIfExists(tempFile);
            var unwrapped = unwrap(url, e);
            logInfo("Multipart download failed for task '{}': {}", task, unwrapped.getMessage());
            throw unwrapped;
        } finally {
            cancel(futures);
        }
    }

    private @Nullable Long fetchHeadContentLengthOrNull(HttpUrl url) {
        try {
            var contentLength = fetchHeadContentLength(url);
            logDebug("HEAD content length for multipart probe: {}", contentLength);
            return contentLength;
        } catch (IOException e) {
            logDebug("HEAD content length probe failed: {}", e.getMessage());
            return null;
        }
    }

    private @Nullable Metadata fetchMetadataOrNull(HttpUrl url, @Nullable Long headContentLength) {
        try {
            return fetchMetadata(url, headContentLength);
        } catch (IOException e) {
            logDebug("Multipart metadata probe failed: {}", e.getMessage());
            return null;
        }
    }

    private @Nullable Metadata fetchMetadata(HttpUrl url, @Nullable Long headContentLength) throws IOException {
        var rangeProbe = probeRangeSupport(url, headContentLength);
        if (rangeProbe == null) {
            return null;
        }

        var contentLength = headContentLength != null ? headContentLength : rangeProbe.contentLength();
        return contentLength > 0 ? new Metadata(contentLength) : null;
    }

    private void makeParentDirectories(File file) throws IOException {
        var parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists() && !parentFile.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentFile);
        }
    }

    private @Nullable Long fetchHeadContentLength(HttpUrl url) throws IOException {
        var request = new Request.Builder()
                .url(url)
                .header(ACCEPT_ENCODING, "identity")
                .head()
                .build();

        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }

            return parseLongHeader(response, CONTENT_LENGTH);
        }
    }

    private @Nullable RangeProbe probeRangeSupport(HttpUrl url, @Nullable Long expectedContentLength) throws IOException {
        var request = new Request.Builder()
                .url(url)
                .header(ACCEPT_ENCODING, "identity")
                .header(RANGE, "bytes=0-0")
                .get()
                .build();

        try (var response = client.newCall(request).execute()) {
            if (response.code() != HTTP_PARTIAL_CONTENT) {
                logDebug("Range probe rejected: HTTP status {} {}", response.code(), response.message());
                return null;
            }

            var acceptRanges = response.header(ACCEPT_RANGES);
            if (acceptRanges != null && !"bytes".equalsIgnoreCase(acceptRanges)) {
                logDebug("Range probe rejected: {}={}", ACCEPT_RANGES, acceptRanges);
                return null;
            }

            var contentRange = parseContentRange(response.header(CONTENT_RANGE));
            if (contentRange == null || contentRange.start() != 0L || contentRange.end() != 0L) {
                logDebug("Range probe rejected: invalid {}={}", CONTENT_RANGE, response.header(CONTENT_RANGE));
                return null;
            }

            var rangeTotal = contentRange.total();
            if (rangeTotal == null) {
                logDebug("Range probe accepted without total length; using HEAD content length {}", expectedContentLength);
                return expectedContentLength != null ? new RangeProbe(expectedContentLength) : null;
            }
            if (expectedContentLength != null && rangeTotal.longValue() != expectedContentLength) {
                logDebug("Range probe rejected: HEAD content length {} != range total {}",
                        expectedContentLength, rangeTotal);
                return null;
            }

            logDebug("Range probe accepted: totalLength={}", rangeTotal);
            return new RangeProbe(rangeTotal);
        }
    }

    private Part[] split(long contentLength) {
        var count = partCountFor(contentLength);
        var partSize = contentLength / count;
        var remainder = contentLength % count;
        var parts = new Part[count];

        long start = 0L;
        for (int index = 0; index < count; index++) {
            var size = partSize + (index < remainder ? 1L : 0L);
            var end = start + size - 1L;
            parts[index] = new Part(index, start, end);
            start = end + 1L;
        }

        return parts;
    }

    private int partCountFor(long contentLength) {
        return (int) Math.min(config.maxConcurrency(), contentLength / config.minPartSizeBytes());
    }

    private void waitForParts(CompletionService<Void> completionService, int partCount)
            throws InterruptedException, ExecutionException {
        for (int remaining = partCount; remaining > 0; remaining--) {
            completionService.take().get();
        }
    }

    private void cancel(List<Future<?>> futures) {
        for (var future : futures) {
            future.cancel(true);
        }
        for (var call : activeCalls) {
            call.cancel();
        }
    }

    private IOException unwrap(HttpUrl url, ExecutionException e) {
        var cause = e.getCause();
        if (cause instanceof IOException ioException) {
            return ioException;
        }

        return new IOException("Failed to download " + url, cause);
    }

    private static @Nullable Long parseLongHeader(Response response, String headerName) {
        var value = response.header(headerName);
        if (value == null) {
            return null;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void logInfo(String message, @Nullable Object... arguments) {
        if (isLogLevelEnabled(DOWNLOAD_LOG_INFO) && LOGGER.isInfoEnabled()) {
            LOGGER.info(message, arguments);
        }
    }

    private static void logDebug(String message, @Nullable Object... arguments) {
        if (isLogLevelEnabled(DOWNLOAD_LOG_DEBUG) && LOGGER.isDebugEnabled()) {
            LOGGER.debug(message, arguments);
        }
    }

    private static boolean isLogLevelEnabled(int level) {
        return DOWNLOAD_LOG_LEVEL != DOWNLOAD_LOG_OFF && DOWNLOAD_LOG_LEVEL >= level;
    }

    private static @Nullable ContentRange parseContentRange(@Nullable String contentRange) {
        if (contentRange == null) {
            return null;
        }

        var value = contentRange.trim();
        if (!value.regionMatches(true, 0, "bytes", 0, "bytes".length())) {
            return null;
        }

        var rangeAndTotal = value.substring("bytes".length()).trim();
        var slashIndex = rangeAndTotal.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex + 1 >= rangeAndTotal.length()) {
            return null;
        }

        var range = rangeAndTotal.substring(0, slashIndex).trim();
        var total = rangeAndTotal.substring(slashIndex + 1).trim();
        var dashIndex = range.indexOf('-');
        if (dashIndex <= 0 || dashIndex + 1 >= range.length()) {
            return null;
        }

        try {
            var start = Long.parseLong(range.substring(0, dashIndex).trim());
            var end = Long.parseLong(range.substring(dashIndex + 1).trim());
            var parsedTotal = "*".equals(total) ? null : Long.parseLong(total);
            if (start < 0 || end < start || (parsedTotal != null && parsedTotal <= end)) {
                return null;
            }

            return new ContentRange(start, end, parsedTotal);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private final class PartDownload implements Callable<Void> {
        private final Part part;
        private final ProgressReporter reporter;
        private final HttpUrl url;
        private final FileChannel channel;
        private final long totalLength;

        private PartDownload(Part part, ProgressReporter reporter, HttpUrl url, FileChannel channel, long totalLength) {
            this.part = part;
            this.reporter = reporter;
            this.url = url;
            this.channel = channel;
            this.totalLength = totalLength;
        }

        @Override
        public Void call() throws IOException {
            long confirmedBytes = 0L;
            int retries = 0;

            while (confirmedBytes < part.length()) {
                var rangeStart = part.start() + confirmedBytes;
                try {
                    confirmedBytes += downloadRange(rangeStart, part.end(), confirmedBytes);
                } catch (RetryablePartDownloadException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Part download interrupted", e);
                    }

                    confirmedBytes += e.bytesRead();
                    reporter.update(part.index(), confirmedBytes, false);

                    if (confirmedBytes >= part.length()) {
                        break;
                    }
                    if (retries >= config.maxPartRetries()) {
                        throw new IOException(String.format(Locale.ROOT,
                                "Part download failed after %d retries: range=%d-%d, remaining=%d-%d",
                                retries, part.start(), part.end(), part.start() + confirmedBytes, part.end()), e);
                    }

                    retries++;
                    logInfo("Retrying multipart range for task part {}: range={}-{}, retry={}/{}",
                            part.index(), part.start() + confirmedBytes, part.end(), retries, config.maxPartRetries());
                    sleepBeforeRetry();
                }
            }

            reporter.update(part.index(), part.length(), true);
            return null;
        }

        private long downloadRange(long rangeStart, long rangeEnd, long confirmedBytesBeforeRange) throws IOException {
            var request = new Request.Builder()
                    .url(url)
                    .header(ACCEPT_ENCODING, "identity")
                    .header(RANGE, String.format(Locale.ROOT, "bytes=%d-%d", rangeStart, rangeEnd))
                    .get()
                    .build();

            var call = client.newCall(request);
            activeCalls.add(call);

            try {
                try (var response = execute(call)) {
                    if (response.code() != HTTP_PARTIAL_CONTENT) {
                        throw new IOException(String.format(Locale.ROOT,
                                "Part download failed: range=%d-%d, HTTP Status=%d %s",
                                rangeStart, rangeEnd, response.code(), response.message()));
                    }
                    validateContentRange(response, rangeStart, rangeEnd);

                    try (var source = response.body().source()) {
                        return copyPart(source, channel, rangeStart, confirmedBytesBeforeRange,
                                rangeEnd - rangeStart + 1L);
                    }
                }
            } finally {
                activeCalls.remove(call);
            }
        }

        private Response execute(Call call) throws IOException {
            try {
                return call.execute();
            } catch (IOException e) {
                throw new RetryablePartDownloadException(0L, e);
            }
        }

        private void validateContentRange(Response response, long rangeStart, long rangeEnd) throws IOException {
            var contentRange = parseContentRange(response.header(CONTENT_RANGE));
            if (contentRange == null
                    || contentRange.start() != rangeStart
                    || contentRange.end() != rangeEnd
                    || (contentRange.total() != null && contentRange.total() != totalLength)) {
                throw new IOException(String.format(Locale.ROOT,
                        "Part content range mismatch: expected=bytes %d-%d/%d, actual=%s",
                        rangeStart, rangeEnd, totalLength, response.header(CONTENT_RANGE)));
            }
        }

        private long copyPart(ReadableByteChannel source, FileChannel channel, long rangeStart,
                              long confirmedBytesBeforeRange, long expectedLength) throws IOException {
            long bytesRead = 0L;

            while (bytesRead < expectedLength) {
                long transferred;
                try {
                    transferred = channel.transferFrom(source, rangeStart + bytesRead,
                            Math.min(COPY_BUFFER_SIZE, expectedLength - bytesRead));
                } catch (IOException e) {
                    throw new RetryablePartDownloadException(bytesRead, e);
                }

                if (transferred == 0L) {
                    throw new RetryablePartDownloadException(bytesRead, new IOException(String.format(Locale.ROOT,
                            "Part length mismatch: range=%d-%d, expected=%d, actual=%d",
                            rangeStart, rangeStart + expectedLength - 1L, expectedLength, bytesRead)));
                }

                bytesRead += transferred;
                reporter.update(part.index(), confirmedBytesBeforeRange + bytesRead, false);
            }

            reporter.update(part.index(), confirmedBytesBeforeRange + bytesRead, true);
            return bytesRead;
        }

        private void sleepBeforeRetry() throws IOException {
            if (config.retryBackoffMillis() == 0L) {
                return;
            }

            try {
                Thread.sleep(config.retryBackoffMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Part download interrupted before retry", e);
            }
        }
    }

    private static final class RetryablePartDownloadException extends IOException {
        private final long bytesRead;

        private RetryablePartDownloadException(long bytesRead, IOException cause) {
            super(cause.getMessage(), cause);
            this.bytesRead = bytesRead;
        }

        private long bytesRead() {
            return bytesRead;
        }
    }

    private static final class ProgressReporter {
        private final CefNativesProgressListener progressListener;
        private final String task;
        private final long totalLength;
        private final AtomicLongArray partReads;
        private long lastUpdateNanos = 0L;

        private ProgressReporter(CefNativesProgressListener progressListener, String task, long totalLength, int partCount) {
            this.progressListener = progressListener;
            this.task = task;
            this.totalLength = totalLength;
            this.partReads = new AtomicLongArray(partCount);
        }

        private synchronized void update(int partIndex, long bytesRead, boolean force) {
            partReads.set(partIndex, bytesRead);

            var now = System.nanoTime();
            var totalRead = totalRead();
            if (!force && totalRead < totalLength && now - lastUpdateNanos < PROGRESS_UPDATE_INTERVAL_NANOS) {
                return;
            }

            emit(totalRead, false);
            lastUpdateNanos = now;
        }

        private synchronized void finish() {
            emit(totalLength, true);
        }

        private void emit(long bytesRead, boolean done) {
            progressListener.onProgressUpdate(task, Math.min((float) bytesRead / totalLength, 1.0f));
            progressListener.onFileProgress(task, bytesRead, totalLength, done);
        }

        private long totalRead() {
            long total = 0L;
            for (int index = 0; index < partReads.length(); index++) {
                total += partReads.get(index);
            }
            return total;
        }
    }

    private record Metadata(long contentLength) {
    }

    private record RangeProbe(long contentLength) {
    }

    private record ContentRange(long start, long end, @Nullable Long total) {
    }

    private record Part(int index, long start, long end) {
        private long length() {
            return end - start + 1L;
        }
    }

}
