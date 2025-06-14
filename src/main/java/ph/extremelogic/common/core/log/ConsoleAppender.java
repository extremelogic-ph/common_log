package ph.extremelogic.common.core.log;

import java.io.IOException;

/**
 * Console appender implementation
 */
public class ConsoleAppender implements Appender {
    @Override
    public void append(String message) {
        System.out.print(message);
    }

    @Override
    public void append(String message, Throwable throwable) {
        System.out.print(message);
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void initialize() throws IOException {
        // No initialization needed for console
    }

    @Override
    public String getName() {
        return "CONSOLE";
    }
}
