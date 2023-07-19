package org.rx.core;

import lombok.Getter;
import lombok.Setter;
import org.rx.annotation.DbColumn;

import java.io.Serializable;
import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.util.Date;

@Getter
@Setter
public class ThreadEntity implements Serializable {
    private static final long serialVersionUID = 6508431367948320100L;
    @DbColumn(primaryKey = true, autoIncrement = true)
    long id;
    String threadName;
    long threadId;
    long blockedTime;
    long blockedCount;
    long waitedTime;
    long waitedCount;
    long userNanos;
    long cpuNanos;
    LockInfo lockInfo;
    String lockName;
    long lockOwnerId;
    String lockOwnerName;
    boolean inNative;
    boolean suspended;
    Thread.State threadState;
    StackTraceElement[] stackTrace;
    MonitorInfo[] lockedMonitors;
    LockInfo[] lockedSynchronizers;

    Date snapshotTime;

    @Override
    public String toString() {
        return toString(8);
    }

    public String toString(int maxFrames) {
        StringBuilder sb = new StringBuilder("\"" + threadName + "\"" +
                " Id=" + threadId + " " +
                threadState);
        if (lockName != null) {
            sb.append(" on " + lockName);
        }
        if (lockOwnerName != null) {
            sb.append(" owned by \"" + lockOwnerName +
                    "\" Id=" + lockOwnerId);
        }
        if (suspended) {
            sb.append(" (suspended)");
        }
        if (inNative) {
            sb.append(" (in native)");
        }
        sb.append(" BlockedTime=%s WaitedTime=%s UserTime=%s CpuTime=%s",
                Sys.formatNanosElapsed(blockedTime, 2), Sys.formatNanosElapsed(waitedTime, 2),
                Sys.formatNanosElapsed(userNanos), Sys.formatNanosElapsed(cpuNanos));
        sb.append('\n');
        int i = 0;
        for (; i < stackTrace.length && i < maxFrames; i++) {
            StackTraceElement ste = stackTrace[i];
            sb.append("\tat " + ste.toString());
            sb.append('\n');
            if (i == 0 && lockInfo != null) {
                Thread.State ts = threadState;
                switch (ts) {
                    case BLOCKED:
                        sb.append("\t-  blocked on " + lockInfo);
                        sb.append('\n');
                        break;
                    case WAITING:
                    case TIMED_WAITING:
                        sb.append("\t-  waiting on " + lockInfo);
                        sb.append('\n');
                        break;
                }
            }

            for (MonitorInfo mi : lockedMonitors) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked " + mi);
                    sb.append('\n');
                }
            }
        }
        if (i < stackTrace.length) {
            sb.append("\t...");
            sb.append('\n');
        }

        if (lockedSynchronizers.length > 0) {
            sb.append("\n\tNumber of locked synchronizers = " + lockedSynchronizers.length);
            sb.append('\n');
            for (LockInfo li : lockedSynchronizers) {
                sb.append("\t- " + li);
                sb.append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }
}
