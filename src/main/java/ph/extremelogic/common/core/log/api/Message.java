package ph.extremelogic.common.core.log.api;

public interface Message {
    String getFormattedMessage();
    String getFormat();
    Object[] getParameters();
    Throwable getThrowable();
}
