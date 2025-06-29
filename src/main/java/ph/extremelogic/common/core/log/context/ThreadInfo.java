package ph.extremelogic.common.core.log.context;

public class ThreadInfo {
    private final long id;
    private final String name;
    private final int priority;
    private final boolean daemon;

    // Cache thread info to avoid repeated Thread method calls
    private static final ThreadLocal<ThreadInfo> THREAD_INFO_CACHE =
            ThreadLocal.withInitial(() -> new ThreadInfo(Thread.currentThread()));

    protected ThreadInfo(Thread thread) {
        this.id = thread.getId();
        this.name = thread.getName();
        this.priority = thread.getPriority();
        this.daemon = thread.isDaemon();
    }

    public ThreadInfo(long id, String name, int priority, boolean daemon) {
        this.id = id;
        this.name = name;
        this.priority = priority;
        this.daemon = daemon;
    }

    public static ThreadInfo current() {
        return THREAD_INFO_CACHE.get();
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public int getPriority() { return priority; }
    public boolean isDaemon() { return daemon; }

    @Override
    public String toString() {
        return String.format("Thread[id=%d, name=%s, priority=%d, daemon=%s]",
                id, name, priority, daemon);
    }
}