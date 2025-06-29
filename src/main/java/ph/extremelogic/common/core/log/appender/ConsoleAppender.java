package ph.extremelogic.common.core.log.appender;

import ph.extremelogic.common.core.log.LogEvent;
import ph.extremelogic.common.core.log.layout.Layout;
import ph.extremelogic.common.core.log.layout.PatternLayout;

import java.io.PrintStream;

public final class ConsoleAppender implements Appender {
    private final String name;
    private final Layout<String> layout;
    private final PrintStream out;
    private volatile boolean started = false;

    // Remove ThreadLocal buffer as Layout now handles buffering
    public ConsoleAppender(String name) {
        this(name, new PatternLayout(), System.out);
    }

    public ConsoleAppender(String name, Layout<String> layout, PrintStream out) {
        this.name = name;
        this.layout = layout != null ? layout : new PatternLayout();
        this.out = out;
    }

    @Override
    public void append(LogEvent event) {
        if (!started) return;

        try {
            String message = layout.toSerializable(event);

            // Optimized output - synchronized block is smaller
            synchronized (out) {
                out.println(message);
            }
        } catch (Exception ignored) {
            // Silently ignore to maintain performance
        }
    }

    @Override
    public void start() { started = true; }

    @Override
    public void stop() {
        started = false;
        if (out != System.out && out != System.err) {
            try {
                out.close();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean isStarted() { return started; }

    @Override
    public String getName() { return name; }
}