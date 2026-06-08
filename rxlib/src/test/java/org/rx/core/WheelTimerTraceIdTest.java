package org.rx.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.util.function.Func;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WheelTimerTraceIdTest {
    private ThreadPool pool;
    private WheelTimer timer;
    private Func<String> oldTraceIdGenerator;
    private long oldSlowMethodElapsedMicros;

    @BeforeEach
    void setUp() {
        oldTraceIdGenerator = ThreadPool.traceIdGenerator;
        oldSlowMethodElapsedMicros = RxConfig.INSTANCE.getTrace().getSlowMethodElapsedMicros();
        RxConfig.INSTANCE.getTrace().setSlowMethodElapsedMicros(0);
    }

    @AfterEach
    void tearDown() {
        if (timer != null) {
            timer.shutdownNow();
        }
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
        ThreadPool.traceIdGenerator = oldTraceIdGenerator;
        RxConfig.INSTANCE.getTrace().setSlowMethodElapsedMicros(oldSlowMethodElapsedMicros);
        ThreadPool.CTX_TRACE_ID.get().clear();
    }

    @Test
    void wheelTimerUsesCapturedTraceWhenWorkerHasDifferentTrace() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        AtomicReference<String> traceInTask = new AtomicReference<>();
        AtomicReference<String> workerTraceAfterTask = new AtomicReference<>();
        String staleTrace = "timer-worker-stale-" + UUID.randomUUID();
        String capturedTrace = "timer-captured-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName(null);
            conf.setMaxTraceDepth(5);
            pool = ThreadPool.fixed("WT-TRACE-CAPTURED", 1, 8);
            timer = new WheelTimer(pool);

            pool.runAsync(() -> ThreadPool.startTrace(staleTrace)).get(5, TimeUnit.SECONDS);

            ThreadPool.startTrace(capturedTrace);
            try {
                ScheduledFuture<?> future = timer.schedule(() -> traceInTask.set(ThreadPool.traceId()), 0, TimeUnit.MILLISECONDS);
                future.get(5, TimeUnit.SECONDS);
                assertEquals(capturedTrace, traceInTask.get());
                assertEquals(capturedTrace, ThreadPool.traceId());
            } finally {
                ThreadPool.endTrace();
            }

            pool.runAsync(() -> workerTraceAfterTask.set(ThreadPool.traceId())).get(5, TimeUnit.SECONDS);
            assertNull(workerTraceAfterTask.get());
        }
    }

    @Test
    void wheelTimerGeneratesNewTraceWhenCallerHasNoTraceAndWorkerHasDifferentTrace() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        AtomicReference<String> traceInTask = new AtomicReference<>();
        AtomicReference<String> workerTraceAfterTask = new AtomicReference<>();
        String staleTrace = "timer-worker-stale-" + UUID.randomUUID();
        String generatedTrace = "timer-generated-" + UUID.randomUUID();
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName(null);
            conf.setMaxTraceDepth(5);
            ThreadPool.traceIdGenerator = () -> generatedTrace;
            pool = ThreadPool.fixed("WT-TRACE-GENERATED", 1, 8);
            timer = new WheelTimer(pool);

            pool.runAsync(() -> ThreadPool.startTrace(staleTrace)).get(5, TimeUnit.SECONDS);

            ScheduledFuture<?> future = timer.schedule(() -> traceInTask.set(ThreadPool.traceId()), 0, TimeUnit.MILLISECONDS);
            future.get(5, TimeUnit.SECONDS);
            assertEquals(generatedTrace, traceInTask.get());
            assertNotEquals(staleTrace, traceInTask.get());
            assertNull(ThreadPool.traceId());

            pool.runAsync(() -> workerTraceAfterTask.set(ThreadPool.traceId())).get(5, TimeUnit.SECONDS);
            assertNull(workerTraceAfterTask.get());
        }
    }

    @Test
    void wheelTimerKeepsCapturedTraceWhenThreadPoolAutoTraceIsEnabled() throws Exception {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        AtomicInteger autoTraceSeq = new AtomicInteger();
        AtomicReference<String> generatedTraceInTask = new AtomicReference<>();
        AtomicReference<String> traceInTask = new AtomicReference<>();
        String capturedTrace = "timer-captured-" + UUID.randomUUID();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ThreadPool.class);
        Level oldLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try (ThreadPoolConfigSnapshot ignored = ThreadPoolConfigSnapshot.capture()) {
            conf.setTraceName("rx-traceId");
            conf.setMaxTraceDepth(5);
            ThreadPool.traceIdGenerator = () -> "timer-auto-" + autoTraceSeq.incrementAndGet();
            pool = ThreadPool.fixed("WT-TRACE-AUTO", 1, 8);
            timer = new WheelTimer(pool);

            timer.schedule(() -> generatedTraceInTask.set(ThreadPool.traceId()), 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            assertEquals("timer-auto-1", generatedTraceInTask.get());

            ThreadPool.startTrace(capturedTrace);
            try {
                ScheduledFuture<?> future = timer.schedule(() -> traceInTask.set(ThreadPool.traceId()), 0, TimeUnit.MILLISECONDS);
                future.get(5, TimeUnit.SECONDS);
                assertEquals(capturedTrace, traceInTask.get());
                assertEquals(capturedTrace, ThreadPool.traceId());
            } finally {
                ThreadPool.endTrace();
            }
            assertFalse(appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("RTrace - Trace requires new")));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
        }
    }
}
