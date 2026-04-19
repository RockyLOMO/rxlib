package org.rx.diagnostic;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;

public final class StackTraceCodec {
    private static final long FNV64_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV64_PRIME = 0x100000001b3L;

    private StackTraceCodec() {
    }

    public static long hash(StackTraceElement[] stackTrace, int maxFrames) {
        if (stackTrace == null || stackTrace.length == 0) {
            return 0L;
        }
        long hash = FNV64_OFFSET;
        int limit = Math.min(stackTrace.length, maxFrames);
        for (int i = 0; i < limit; i++) {
            StackTraceElement frame = stackTrace[i];
            hash = update(hash, frame.getClassName());
            hash = update(hash, "#");
            hash = update(hash, frame.getMethodName());
            hash = update(hash, ":");
            hash = update(hash, frame.getFileName());
            hash = update(hash, ":");
            hash = update(hash, Integer.toString(frame.getLineNumber()));
        }
        return hash;
    }

    public static long hash(String text) {
        return update(FNV64_OFFSET, text);
    }

    public static String format(StackTraceElement[] stackTrace, int maxFrames) {
        StringBuilder builder = new StringBuilder(512);
        appendStack(builder, stackTrace, maxFrames);
        return builder.toString();
    }

    public static String format(ThreadInfo info, int maxFrames) {
        if (info == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(1024);
        builder.append('"').append(info.getThreadName()).append('"')
                .append(" Id=").append(info.getThreadId())
                .append(' ').append(info.getThreadState());
        if (info.getLockName() != null) {
            builder.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            builder.append(" owned by \"").append(info.getLockOwnerName())
                    .append("\" Id=").append(info.getLockOwnerId());
        }
        if (info.isSuspended()) {
            builder.append(" (suspended)");
        }
        if (info.isInNative()) {
            builder.append(" (in native)");
        }
        builder.append('\n');
        StackTraceElement[] stackTrace = info.getStackTrace();
        int limit = Math.min(stackTrace.length, maxFrames);
        for (int i = 0; i < limit; i++) {
            StackTraceElement frame = stackTrace[i];
            builder.append("\tat ").append(frame).append('\n');
            if (i == 0 && info.getLockInfo() != null) {
                appendLock(builder, info.getThreadState(), info.getLockInfo());
            }
            MonitorInfo[] monitors = info.getLockedMonitors();
            if (monitors != null) {
                for (MonitorInfo monitor : monitors) {
                    if (monitor.getLockedStackDepth() == i) {
                        builder.append("\t- locked ").append(monitor).append('\n');
                    }
                }
            }
        }
        if (limit < stackTrace.length) {
            builder.append("\t...").append('\n');
        }
        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers != null && synchronizers.length != 0) {
            builder.append("\n\tNumber of locked synchronizers = ").append(synchronizers.length).append('\n');
            for (LockInfo synchronizer : synchronizers) {
                builder.append("\t- ").append(synchronizer).append('\n');
            }
        }
        return builder.toString();
    }

    private static void appendStack(StringBuilder builder, StackTraceElement[] stackTrace, int maxFrames) {
        if (stackTrace == null) {
            return;
        }
        int limit = Math.min(stackTrace.length, maxFrames);
        for (int i = 0; i < limit; i++) {
            builder.append("\tat ").append(stackTrace[i]).append('\n');
        }
        if (limit < stackTrace.length) {
            builder.append("\t...").append('\n');
        }
    }

    private static void appendLock(StringBuilder builder, Thread.State state, LockInfo lockInfo) {
        switch (state) {
            case BLOCKED:
                builder.append("\t- blocked on ").append(lockInfo).append('\n');
                break;
            case WAITING:
            case TIMED_WAITING:
                builder.append("\t- waiting on ").append(lockInfo).append('\n');
                break;
            default:
                break;
        }
    }

    private static long update(long hash, String value) {
        if (value == null) {
            return update(hash, "null");
        }
        long h = hash;
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= FNV64_PRIME;
        }
        return h;
    }
}

