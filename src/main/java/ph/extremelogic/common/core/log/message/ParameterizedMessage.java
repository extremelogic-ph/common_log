package ph.extremelogic.common.core.log.message;

import ph.extremelogic.common.core.log.api.Message;

public final class ParameterizedMessage  implements Message {
    private static final int INITIAL_CAPACITY = 512; // Increased for better performance
    private static final Object[] EMPTY_PARAMS = new Object[0];
    private static final String PLACEHOLDER = "{}";

    private final StringBuilder buffer;
    private final Object[] parameterArray;
    private String format;
    private int parameterCount;
    private Throwable throwable;
    private boolean formatted = false;

    public ParameterizedMessage() {
        this.buffer = new StringBuilder(INITIAL_CAPACITY);
        this.parameterArray = new Object[16]; // Increased parameter capacity
    }

    public void setMessage(String format, Object... args) {
        this.format = format;
        this.parameterCount = args != null ? Math.min(args.length, parameterArray.length) : 0;

        if (parameterCount > 0) {
            System.arraycopy(args, 0, parameterArray, 0, parameterCount);
        }

        this.formatted = false;
        formatMessageOptimized();
    }

    public void setMessage(String message) {
        this.format = message;
        this.parameterCount = 0;
        this.formatted = false;

        buffer.setLength(0);
        buffer.append(message);
        this.formatted = true;
    }

    // Highly optimized message formatting
    private void formatMessageOptimized() {
        if (formatted || format == null) return;

        buffer.setLength(0);

        if (parameterCount == 0) {
            buffer.append(format);
        } else {
            int start = 0;
            int paramIndex = 0;
            final int formatLength = format.length();

            // Optimized placeholder replacement
            while (paramIndex < parameterCount && start < formatLength - 1) {
                int placeholderIndex = format.indexOf(PLACEHOLDER, start);
                if (placeholderIndex == -1) break;

                // Append text before placeholder
                if (placeholderIndex > start) {
                    buffer.append(format, start, placeholderIndex);
                }

                // Append parameter value
                Object param = parameterArray[paramIndex++];
                if (param != null) {
                    if (param instanceof String) {
                        buffer.append((String) param);
                    } else {
                        buffer.append(param.toString());
                    }
                } else {
                    buffer.append("null");
                }

                start = placeholderIndex + 2;
            }

            // Append remaining format string
            if (start < formatLength) {
                buffer.append(format, start, formatLength);
            }
        }

        formatted = true;
    }

    public void clear() {
        buffer.setLength(0);
        format = null;
        // Only clear used portion of parameter array
        for (int i = 0; i < parameterCount; i++) {
            parameterArray[i] = null;
        }
        parameterCount = 0;
        throwable = null;
        formatted = false;
    }

    @Override
    public String getFormattedMessage() {
        if (!formatted) {
            formatMessageOptimized();
        }
        return buffer.toString();
    }

    @Override
    public String getFormat() { return format; }

    @Override
    public Object[] getParameters() {
        return parameterCount == 0 ? EMPTY_PARAMS :
                java.util.Arrays.copyOf(parameterArray, parameterCount);
    }

    @Override
    public Throwable getThrowable() { return throwable; }

    public void setThrowable(Throwable throwable) { this.throwable = throwable; }
}
