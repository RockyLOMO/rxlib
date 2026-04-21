package org.rx.diagnostic;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ThreadStateSampler {
    private final ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
    private final Map<Long, StateTrack> tracks = new HashMap<>();

    StateCounters sampleCounters(long now) {
        long[] ids = threadMxBean.getAllThreadIds();
        ThreadInfo[] infos = threadMxBean.getThreadInfo(ids, 0);
        Set<Long> alive = new HashSet<>(ids.length * 2);
        StateCounters counters = new StateCounters();
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            long id = info.getThreadId();
            alive.add(Long.valueOf(id));
            StateTrack track = track(id, info.getThreadState(), now);
            if (info.getThreadState() == Thread.State.BLOCKED) {
                counters.blockedCount++;
                counters.maxBlockedDurationMillis = Math.max(counters.maxBlockedDurationMillis, now - track.sinceMillis);
            } else if (isWaiting(info.getThreadState())) {
                counters.waitingCount++;
                counters.maxWaitingDurationMillis = Math.max(counters.maxWaitingDurationMillis, now - track.sinceMillis);
            }
        }
        removeDeadTracks(alive);
        counters.deadlockedIds = findDeadlockedThreads();
        return counters;
    }

    List<ThreadStateSample> sampleBlocked(int topN, int maxFrames, long now) {
        return sampleTop(Thread.State.BLOCKED, topN, maxFrames, now);
    }

    List<ThreadStateSample> sampleWaiting(int topN, int maxFrames, long now) {
        List<ThreadStateSample> samples = sampleTop(Thread.State.WAITING, topN, maxFrames, now);
        if (samples.size() < topN) {
            samples.addAll(sampleTop(Thread.State.TIMED_WAITING, topN - samples.size(), maxFrames, now));
        }
        return samples;
    }

    List<ThreadStateSample> sampleDeadlocked(long[] ids, int maxFrames, long now) {
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        ThreadInfo[] infos = threadMxBean.getThreadInfo(ids, Math.max(1, maxFrames));
        List<ThreadStateSample> samples = new ArrayList<>(infos.length);
        for (ThreadInfo info : infos) {
            if (info != null) {
                samples.add(toSample(info, now, maxFrames));
            }
        }
        return samples;
    }

    List<ThreadStateSample> sampleAll(int topN, int maxFrames, final long now) {
        if (topN <= 0) {
            return Collections.emptyList();
        }
        long[] ids = threadMxBean.getAllThreadIds();
        ThreadInfo[] infos = threadMxBean.getThreadInfo(ids, 0);
        Set<Long> alive = new HashSet<>(ids.length * 2);
        List<StateTrack> candidates = new ArrayList<>(infos.length);
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            long id = info.getThreadId();
            alive.add(Long.valueOf(id));
            candidates.add(track(id, info.getThreadState(), now));
        }
        removeDeadTracks(alive);
        Collections.sort(candidates, new Comparator<StateTrack>() {
            @Override
            public int compare(StateTrack o1, StateTrack o2) {
                int severity = Integer.compare(severity(o2.state), severity(o1.state));
                if (severity != 0) {
                    return severity;
                }
                int duration = Long.compare(now - o2.sinceMillis, now - o1.sinceMillis);
                if (duration != 0) {
                    return duration;
                }
                return Long.compare(o1.threadId, o2.threadId);
            }
        });
        int len = Math.min(topN, candidates.size());
        long[] topIds = new long[len];
        for (int i = 0; i < len; i++) {
            topIds[i] = candidates.get(i).threadId;
        }
        ThreadInfo[] topInfos = threadMxBean.getThreadInfo(topIds, Math.max(1, maxFrames));
        List<ThreadStateSample> samples = new ArrayList<>(len);
        for (ThreadInfo info : topInfos) {
            if (info != null) {
                samples.add(toSample(info, now, maxFrames));
            }
        }
        return samples;
    }

    private List<ThreadStateSample> sampleTop(final Thread.State state, int topN, int maxFrames, final long now) {
        if (topN <= 0) {
            return Collections.emptyList();
        }
        List<StateTrack> candidates = new ArrayList<>();
        for (StateTrack track : tracks.values()) {
            if (track.state == state) {
                candidates.add(track);
            }
        }
        Collections.sort(candidates, new Comparator<StateTrack>() {
            @Override
            public int compare(StateTrack o1, StateTrack o2) {
                return Long.compare(now - o2.sinceMillis, now - o1.sinceMillis);
            }
        });
        int len = Math.min(topN, candidates.size());
        long[] ids = new long[len];
        for (int i = 0; i < len; i++) {
            ids[i] = candidates.get(i).threadId;
        }
        ThreadInfo[] infos = threadMxBean.getThreadInfo(ids, Math.max(1, maxFrames));
        List<ThreadStateSample> samples = new ArrayList<>(len);
        for (ThreadInfo info : infos) {
            if (info != null) {
                samples.add(toSample(info, now, maxFrames));
            }
        }
        return samples;
    }

    private ThreadStateSample toSample(ThreadInfo info, long now, int maxFrames) {
        StateTrack track = track(info.getThreadId(), info.getThreadState(), now);
        StackTraceElement[] stack = info.getStackTrace();
        String stackText = StackTraceCodec.format(info, Math.max(1, maxFrames));
        long stackHash = StackTraceCodec.hash(stack, Math.max(1, maxFrames));
        long blockedTime = Math.max(0L, info.getBlockedTime());
        long waitedTime = Math.max(0L, info.getWaitedTime());
        return new ThreadStateSample(now, info.getThreadId(), info.getThreadName(),
                info.getThreadState().name(), blockedTime, waitedTime, now - track.sinceMillis,
                info.getLockName(), info.getLockOwnerId(), info.getLockOwnerName(), stackHash, stackText);
    }

    private StateTrack track(long id, Thread.State state, long now) {
        StateTrack track = tracks.get(Long.valueOf(id));
        if (track == null) {
            track = new StateTrack(id, state, now);
            tracks.put(Long.valueOf(id), track);
            return track;
        }
        if (track.state != state) {
            track.state = state;
            track.sinceMillis = now;
        }
        return track;
    }

    private void removeDeadTracks(Set<Long> alive) {
        List<Long> stale = new ArrayList<>();
        for (Long id : tracks.keySet()) {
            if (!alive.contains(id)) {
                stale.add(id);
            }
        }
        for (Long id : stale) {
            tracks.remove(id);
        }
    }

    private long[] findDeadlockedThreads() {
        try {
            long[] ids = threadMxBean.findDeadlockedThreads();
            if (ids != null) {
                return ids;
            }
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            return threadMxBean.findMonitorDeadlockedThreads();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static boolean isWaiting(Thread.State state) {
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    private static int severity(Thread.State state) {
        if (state == Thread.State.BLOCKED) {
            return 4;
        }
        if (isWaiting(state)) {
            return 3;
        }
        if (state == Thread.State.RUNNABLE) {
            return 2;
        }
        return 1;
    }

    static final class StateCounters {
        int blockedCount;
        int waitingCount;
        long maxBlockedDurationMillis;
        long maxWaitingDurationMillis;
        long[] deadlockedIds;
    }

    private static final class StateTrack {
        final long threadId;
        Thread.State state;
        long sinceMillis;

        StateTrack(long threadId, Thread.State state, long sinceMillis) {
            this.threadId = threadId;
            this.state = state;
            this.sinceMillis = sinceMillis;
        }
    }
}
