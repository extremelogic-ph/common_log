package ph.extremelogic.common.core.log.message;

import ph.extremelogic.common.core.log.api.Message;

public class SimpleMessage implements Message {
    private final String message;

    public SimpleMessage(String message) {
        this.message = message;
    }

    @Override public String getFormattedMessage() { return message; }
    @Override public String getFormat() { return message; }
    @Override public Object[] getParameters() { return new Object[0]; }
    @Override public Throwable getThrowable() { return null; }
}
