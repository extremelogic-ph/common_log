package ph.extremelogic.common.core.log;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Optimized log message formatter with thread-local caching and fast timestamp formatting
 */
public class LogMessageFormatter {

    // Static immutable formatter - thread-safe and reusable
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    private static final String LINE_SEPARATOR = System.lineSeparator();

    // Pre-calculated level strings to avoid enum.name() calls
    private static final String[] LEVEL_STRINGS = new String[LogLevel.values().length];
    static {
        for (LogLevel level : LogLevel.values()) {
            LEVEL_STRINGS[level.ordinal()] = level.name();
        }
    }

    // Enhanced thread-local caching
    private static final ThreadLocal<ThreadLocalCache> THREAD_CACHE =
            ThreadLocal.withInitial(ThreadLocalCache::new);

    /**
     * Thread-local cache to reduce object allocation and improve performance
     */
    private static class ThreadLocalCache {
        final StringBuilder stringBuilder = new StringBuilder(512);
        final char[] charBuffer = new char[256];
        long lastTimestamp = -1;
        String cachedTimestamp = null;

        // Cache common log format components
        String lastLoggerName = null;
        String cachedLoggerPrefix = null;
    }

    /**
     * Format a log message with optimized caching and minimal allocations
     */
    public static String formatMessage(LogLevel level, String loggerName, String message, long timestamp) {
        ThreadLocalCache cache = THREAD_CACHE.get();
        StringBuilder sb = cache.stringBuilder;
        sb.setLength(0);

        // Cache timestamp formatting
        String timestampStr;
        if (cache.lastTimestamp == timestamp && cache.cachedTimestamp != null) {
            timestampStr = cache.cachedTimestamp;
        } else {
            timestampStr = formatTimestampFast(timestamp);
            cache.lastTimestamp = timestamp;
            cache.cachedTimestamp = timestampStr;
        }

        // Cache logger name prefix
        String loggerPrefix;
        if (loggerName.equals(cache.lastLoggerName) && cache.cachedLoggerPrefix != null) {
            loggerPrefix = cache.cachedLoggerPrefix;
        } else {
            loggerPrefix = "] " + LEVEL_STRINGS[level.ordinal()] + " [" + loggerName + "] - ";
            cache.lastLoggerName = loggerName;
            cache.cachedLoggerPrefix = loggerPrefix;
        }

        // Build message: [timestamp] + loggerPrefix + message + newline
        sb.append('[').append(timestampStr).append(loggerPrefix)
                .append(message).append(LINE_SEPARATOR);

        return sb.toString();
    }

    /**
     * Format a log message for synchronous logging (slightly different format)
     */
    public static String formatMessageSync(LogLevel level, String loggerName, String message, long timestamp) {
        ThreadLocalCache cache = THREAD_CACHE.get();
        StringBuilder sb = cache.stringBuilder;
        sb.setLength(0);

        String timestampStr;
        if (cache.lastTimestamp == timestamp && cache.cachedTimestamp != null) {
            timestampStr = cache.cachedTimestamp;
        } else {
            timestampStr = formatTimestampFast(timestamp);
            cache.lastTimestamp = timestamp;
            cache.cachedTimestamp = timestampStr;
        }

        sb.append('[').append(timestampStr).append("] ")
                .append(LEVEL_STRINGS[level.ordinal()]).append(" [")
                .append(loggerName).append("] - ")
                .append(message).append(LINE_SEPARATOR);

        return sb.toString();
    }

    /**
     * Ultra-fast timestamp formatting with multiple optimization layers
     */
    private static String formatTimestampFast(long timestamp) {
        try {
            // Direct conversion avoiding Instant creation
            long epochSecond = timestamp / 1000;
            int nanoAdjustment = (int) ((timestamp % 1000) * 1_000_000);

            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(
                    epochSecond, nanoAdjustment, SYSTEM_ZONE.getRules().getOffset(Instant.ofEpochMilli(timestamp))
            );

            return dateTime.format(TIMESTAMP_FORMATTER);

        } catch (Exception e) {
            // Fallback to simple format
            return String.format("%tF %<tT.%<tL", timestamp);
        }
    }

    /**
     * Get the current timestamp formatted as string (for immediate use)
     */
    public static String getCurrentTimestamp() {
        return formatTimestampFast(System.currentTimeMillis());
    }

    /**
     * Clear the thread-local cache (useful for testing or memory cleanup)
     */
    public static void clearCache() {
        THREAD_CACHE.remove();
    }
}
