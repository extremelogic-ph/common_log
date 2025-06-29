package ph.extremelogic.common.core.log.layout;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.context.ReadOnlyStringMap;
import ph.extremelogic.common.core.log.DefaultLogEvent;
import ph.extremelogic.common.core.log.LogEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class PatternLayout implements Layout<String> {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    // Larger buffer pool for better performance
    private static final ThreadLocal<StringBuilder> BUFFER_POOL =
            ThreadLocal.withInitial(() -> new StringBuilder(2048));

    // Pre-computed level strings with exact spacing for alignment
    private static final String[] LEVEL_STRINGS = {
            "DEBUG", "INFO ", "WARN ", "ERROR"
    };

    // Cache for common format parts
    private static final String THREAD_PREFIX = "] [";
    private static final String LOGGER_SUFFIX = " - ";
    private static final String THREAD_SUFFIX = "]";  // Added this
    private static final String LOGGER_PREFIX = " ["; // Changed this
    private static final String MESSAGE_PREFIX = " - "; // Changed this

    @Override
    public String toSerializable(LogEvent event) {
        StringBuilder sb = BUFFER_POOL.get();
        sb.setLength(0);

        // Optimized formatting with minimal method calls
        appendTimestampOptimized(sb, event.getTimeMillis());

        // Thread info
        sb.append(LOGGER_PREFIX);
        if (event instanceof DefaultLogEvent) {
            int levelInt = ((DefaultLogEvent) event).getLevelInt();
            sb.append(LEVEL_STRINGS[levelInt]);
        } else {
            appendLevel(sb, event.getLevel());
        }

        // Thread info with proper closing bracket
        sb.append(THREAD_PREFIX)
                .append(event.getThreadInfo().getName())
                .append(THREAD_SUFFIX);  // Added closing bracket

        // Logger name and message
        sb.append(MESSAGE_PREFIX)
                .append(event.getLoggerName())
                .append(MESSAGE_PREFIX)
                .append(event.getMessage().getFormattedMessage());

        // Only append context if present (optimization)
        ReadOnlyStringMap contextData = event.getContextData();
        if (contextData != null && !contextData.isEmpty()) {
            appendContextDataOptimized(sb, contextData);
        }

        Throwable thrown = event.getThrown();
        if (thrown != null) {
            appendExceptionOptimized(sb, thrown);
        }

        return sb.toString();
    }

    private void appendTimestampOptimized(StringBuilder sb, long millis) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis), SYSTEM_ZONE);
        sb.append(dateTime.format(TIMESTAMP_FORMATTER));
    }

    private void appendLevel(StringBuilder sb, Level level) {
        int levelIndex = level.getIntLevel();
        sb.append(LEVEL_STRINGS[levelIndex]);
    }

    private void appendContextDataOptimized(StringBuilder sb, ReadOnlyStringMap contextData) {
        sb.append(" {");
        boolean first = true;
        for (Map.Entry<String, String> entry : contextData.toMap().entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        sb.append('}');
    }

    private void appendExceptionOptimized(StringBuilder sb, Throwable t) {
        sb.append(System.lineSeparator());
        sb.append(t.getClass().getName()).append(": ");
        String message = t.getMessage();
        if (message != null) {
            sb.append(message);
        }

        StackTraceElement[] elements = t.getStackTrace();
        int maxElements = Math.min(8, elements.length); // Reduced for performance
        for (int i = 0; i < maxElements; i++) {
            sb.append(System.lineSeparator()).append("\tat ").append(elements[i]);
        }
        if (elements.length > maxElements) {
            sb.append(System.lineSeparator()).append("\t... ")
                    .append(elements.length - maxElements).append(" more");
        }
    }

    @Override
    public String getContentType() { return "text/plain"; }
    @Override
    public byte[] getHeader() { return null; }
    @Override
    public byte[] getFooter() { return null; }
}