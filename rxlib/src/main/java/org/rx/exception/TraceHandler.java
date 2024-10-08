package org.rx.exception;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.DbColumn;
import org.rx.annotation.Subscribe;
import org.rx.bean.CircularBlockingQueue;
import org.rx.bean.DateTime;
import org.rx.codec.CodecUtil;
import org.rx.core.Arrays;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serializable;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;

import static org.rx.core.Extends.as;
import static org.rx.core.RxConfig.ConfigNames.TRACE_KEEP_DAYS;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class TraceHandler implements Thread.UncaughtExceptionHandler {
    @Getter
    @Setter
    @ToString
    public static class ExceptionEntity implements Serializable {
        private static final long serialVersionUID = 8387064071982888474L;
        @DbColumn(primaryKey = true)
        long id;
        ExceptionLevel level;
        Queue<Map<String, Object>> messages;
        String stackTrace;
        int occurCount;

        String appName;
        String threadName;
        Date modifyTime;
    }

    @Getter
    @Setter
    @ToString
    public static class MethodEntity implements Serializable {
        private static final long serialVersionUID = 941255683071148L;
        @DbColumn(primaryKey = true)
        long id;
        String methodName;
        String parameters;
        String returnValue;
        Map<String, String> MDC;
        long elapsedMicros;
        int occurCount;

        String appName;
        String threadName;
        Date modifyTime;
    }

    @Getter
    @Setter
    @ToString
    public static class MetricsEntity implements Serializable {
        private static final long serialVersionUID = 2049476730423563051L;
        @DbColumn(primaryKey = true)
        String name;
        String message;
        String stackTrace;
        int occurCount;
        Date modifyTime;
    }

    public static final TraceHandler INSTANCE = new TraceHandler();

    public static Object[] getMessageCandidate(Object... args) {
        if (args != null && args.length != 0) {
            int lastIndex = args.length - 1;
            Object last = args[lastIndex];
            if (last instanceof Throwable) {
                if (lastIndex == 0) {
                    return Arrays.EMPTY_OBJECT_ARRAY;
                }
                return Linq.from(args).take(lastIndex).toArray();
            }
        }
        return args;
    }

    final CircularBlockingQueue<Object[]> queue = new CircularBlockingQueue<>(RxConfig.INSTANCE.getTrace().getWriteQueueLength());
    ScheduledFuture<?> future;

    private TraceHandler() {
        RxConfig conf = RxConfig.INSTANCE;
        try {
            EntityDatabase db = EntityDatabase.DEFAULT;
            db.createMapping(ExceptionEntity.class, MethodEntity.class, MetricsEntity.class, ThreadEntity.class);
            queue.onConsume.combine((s, e) -> {
                RxConfig.TraceConfig c = RxConfig.INSTANCE.getTrace();
                if (c.getKeepDays() <= 0) {
                    return;
                }

                Reflects.invokeMethod(this, "innerSave", e);
            });
            queue.setConsumePeriod(conf.getTrace().getFlushQueuePeriod());
        } catch (Throwable e) {
            log.error("RxMeta init db error", e);
        }
        ObjectChangeTracker.DEFAULT.register(this);
        onChanged(new ObjectChangedEvent(conf, Collections.emptyMap()));
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Subscribe(topicClass = RxConfig.class)
    synchronized void onChanged(ObjectChangedEvent event) {
        RxConfig.TraceConfig trace = RxConfig.INSTANCE.getTrace();
        queue.setCapacity(trace.getWriteQueueLength());

        int keepDays = trace.getKeepDays();
        log.info("RxMeta {} changed {}", TRACE_KEEP_DAYS, keepDays);
        if (keepDays > 0) {
            if (future == null) {
                future = Tasks.scheduleDaily(() -> {
                    EntityDatabase db = EntityDatabase.DEFAULT;
                    DateTime d = DateTime.now().addDays(-keepDays - 1);
                    db.delete(new EntityQueryLambda<>(ExceptionEntity.class)
                            .lt(ExceptionEntity::getModifyTime, d));
                    db.delete(new EntityQueryLambda<>(MethodEntity.class)
                            .lt(MethodEntity::getModifyTime, d));
                    db.delete(new EntityQueryLambda<>(ThreadEntity.class)
                            .lt(ThreadEntity::getSnapshotTime, d));
                    db.compact();
                }, Time.valueOf("3:00:00"));
            }
        } else {
            if (future != null) {
                future.cancel(true);
                future = null;
            }
        }
    }

    public void log(String format, Object... args) {
        try {
            FormattingTuple tuple = MessageFormatter.arrayFormat(format, args);
            if (tuple.getThrowable() == null) {
                log.warn(format + "[NoThrowableCandidate]", args);
                return;
            }

            log.error(format, args);
            saveExceptionTrace(Thread.currentThread(), tuple.getMessage(), tuple.getThrowable());
        } catch (Throwable ie) {
            ie.printStackTrace();
        }
    }

    public void log(Throwable e) {
        uncaughtException(Thread.currentThread(), e);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            log.error("Thread[{}] uncaught", t.getId(), e);
            saveExceptionTrace(t, null, e);
        } catch (Throwable ie) {
            ie.printStackTrace();
        }
    }

    public void saveExceptionTrace(Thread t, String msg, Throwable e) {
        queue.offer(new Object[]{t.getName(), Sys.getMDCCtxMap(), DateTime.now(), msg, e});
    }

    void innerSave(String thread, Map<String, String> mdc, DateTime now, String msg, Throwable e) {
        RxConfig.TraceConfig conf = RxConfig.INSTANCE.getTrace();
        String stackTrace = ExceptionUtils.getStackTrace(e);
        String eMsg = Strings.EMPTY;
        int eMsgFlag = stackTrace.indexOf(Constants.STACK_TRACE_FLAG);
        if (eMsgFlag != -1) {
            eMsgFlag += 2;
            eMsg = stackTrace.substring(0, eMsgFlag);
            stackTrace = stackTrace.substring(eMsgFlag);
        }

        long pk = CodecUtil.hash64(stackTrace);
        EntityDatabase db = EntityDatabase.DEFAULT;
        db.begin();
        try {
            ExceptionEntity entity = db.findById(ExceptionEntity.class, pk);
            boolean doInsert = entity == null;
            if (doInsert) {
                entity = new ExceptionEntity();
                entity.setId(pk);
                InvalidException invalidException = as(e, InvalidException.class);
                ExceptionLevel level = invalidException != null && invalidException.getLevel() != null ? invalidException.getLevel()
                        : ExceptionLevel.SYSTEM;
                entity.setLevel(level);
                entity.setMessages(new ConcurrentLinkedQueue<>());
                entity.setStackTrace(stackTrace);
            }
            Queue<Map<String, Object>> queue = entity.getMessages();
            if (queue.size() > conf.getErrorMessageSize()) {
                queue.poll();
            }
            Map<String, Object> call = new HashMap<>(2);
            StringBuilder b = new StringBuilder();
            b.appendMessageFormat("{}\t{}{}", now.toDateTimeString(), eMsg, msg);
            call.put("message", b.toString());
            call.put("MDC", mdc);
            queue.offer(call);
            entity.occurCount++;
            entity.setAppName(RxConfig.INSTANCE.getId());
            entity.setThreadName(thread);
            entity.setModifyTime(now);
            db.save(entity, doInsert);
            db.commit();
        } catch (Throwable ex) {
            log.error("dbTrace", ex);
            db.rollback();
        }
    }

    public List<ExceptionEntity> queryExceptionTraces(Date startTime, Date endTime, ExceptionLevel level, String keyword,
                                                      Boolean newest, Integer limit) {
        if (newest == null) {
            newest = Boolean.FALSE;
        }
        if (limit == null) {
            limit = 20;
        }

        EntityQueryLambda<ExceptionEntity> q = new EntityQueryLambda<>(ExceptionEntity.class).limit(limit);
        if (startTime != null) {
            q.ge(ExceptionEntity::getModifyTime, startTime);
        }
        if (endTime != null) {
            q.lt(ExceptionEntity::getModifyTime, endTime);
        }
        if (level != null) {
            q.eq(ExceptionEntity::getLevel, level);
        }
        if (!Strings.isBlank(keyword)) {
            q.like(ExceptionEntity::getStackTrace, "%" + keyword.trim() + "%");
        }
        if (newest) {
            q.orderByDescending(ExceptionEntity::getModifyTime);
        } else {
            q.orderByDescending(ExceptionEntity::getOccurCount);
        }
        EntityDatabase db = EntityDatabase.DEFAULT;
        return db.findBy(q);
    }

    public void saveMethodTrace(Thread t, String declaringTypeName, String methodName, Object[] parameters,
                                Object returnValue, Throwable e, long elapsedMicros) {
        queue.offer(new Object[]{t.getName(), Sys.getMDCCtxMap(), DateTime.now(), declaringTypeName, methodName, parameters, returnValue, e, elapsedMicros});
    }

    void innerSave(String thread, Map<String, String> mdc, DateTime now, String declaringTypeName, String methodName, Object[] parameters,
                   Object returnValue, Throwable error, long elapsedNanos) {
        RxConfig.TraceConfig conf = RxConfig.INSTANCE.getTrace();
        long elapsedMicros;
        if ((elapsedMicros = elapsedNanos / 1000L) < conf.getSlowMethodElapsedMicros()) {
            return;
        }

        String fullName = String.format("%s.%s(%s)", declaringTypeName, methodName, parameters == null ? 0 : parameters.length);
        long pk = CodecUtil.hash64(fullName);
//        Tasks.nextPool().runSerial(() -> {
        EntityDatabase db = EntityDatabase.DEFAULT;
        db.begin();
        try {
            MethodEntity entity = db.findById(MethodEntity.class, pk);
            boolean doInsert = entity == null;
            if (doInsert) {
                entity = new MethodEntity();
                entity.setId(pk);
                entity.setMethodName(fullName);
            }
            if (parameters != null) {
                entity.setParameters(toJsonString(parameters));
            }
            if (error != null) {
                entity.setReturnValue(ExceptionUtils.getStackTrace(error));
            } else if (returnValue != null) {
                entity.setReturnValue(toJsonString(returnValue));
            }
            entity.setMDC(mdc);
            entity.elapsedMicros = Math.max(entity.elapsedMicros, elapsedMicros);
            entity.occurCount++;
            entity.setAppName(RxConfig.INSTANCE.getId());
            entity.setThreadName(thread);
            entity.setModifyTime(now);
            db.save(entity, doInsert);
            db.commit();
        } catch (Throwable e) {
            log.error("dbTrace", e);
            db.rollback();
        }
//            return null;
//        }, pk);
    }

    public List<MethodEntity> queryMethodTraces(String methodNamePrefix, Boolean methodOccurMost, Integer limit) {
        if (methodOccurMost == null) {
            methodOccurMost = Boolean.FALSE;
        }
        if (limit == null) {
            limit = 20;
        }

        EntityQueryLambda<MethodEntity> q = new EntityQueryLambda<>(MethodEntity.class).limit(limit);
        if (methodNamePrefix != null) {
            q.like(MethodEntity::getMethodName, String.format("%s%%", methodNamePrefix));
        }
        if (methodOccurMost) {
            q.orderByDescending(MethodEntity::getOccurCount);
        } else {
            q.orderByDescending(MethodEntity::getElapsedMicros);
        }
        EntityDatabase db = EntityDatabase.DEFAULT;
        return db.findBy(q);
    }

    public void saveThreadTrace(Linq<ThreadEntity> snapshot) {
        RxConfig.TraceConfig conf = RxConfig.INSTANCE.getTrace();
        if (conf.getKeepDays() <= 0) {
            return;
        }

        Tasks.run(() -> {
            EntityDatabase db = EntityDatabase.DEFAULT;
            db.begin();
            try {
                for (ThreadEntity t : snapshot) {
                    db.save(t, true);
                }
                db.commit();
            } catch (Throwable ex) {
                log.error("dbTrace", ex);
                db.rollback();
            }
        });
    }

    public Linq<ThreadEntity> queryThreadTrace(Long snapshotId, Date startTime, Date endTime) {
        EntityQueryLambda<ThreadEntity> q = new EntityQueryLambda<>(ThreadEntity.class);
        if (snapshotId != null) {
            q.eq(ThreadEntity::getSnapshotId, snapshotId);
        }
        if (startTime != null) {
            q.ge(ThreadEntity::getSnapshotTime, startTime);
        }
        if (endTime != null) {
            q.lt(ThreadEntity::getSnapshotTime, endTime);
        }
        EntityDatabase db = EntityDatabase.DEFAULT;
        return Linq.from(db.findBy(q));
    }

    public void saveMetric(String name, String message) {
        log.info("saveMetric {} {}", name, message);
        String stackTrace = Reflects.getStackTrace(Thread.currentThread());
        Tasks.nextPool().runSerial(() -> {
            EntityDatabase db = EntityDatabase.DEFAULT;
            db.begin();
            try {
                MetricsEntity entity = db.findById(MetricsEntity.class, name);
                boolean doInsert = entity == null;
                if (doInsert) {
                    entity = new MetricsEntity();
                    entity.setName(name);
                }
                entity.setMessage(message);
                entity.setStackTrace(stackTrace);
                entity.occurCount++;
                entity.setModifyTime(DateTime.now());
                db.save(entity, doInsert);
                db.commit();
            } catch (Throwable e) {
                log.error("dbTrace", e);
                db.rollback();
            }
            return null;
        }, name);
    }

    public List<MetricsEntity> queryMetrics(String name, Integer limit) {
        if (limit == null) {
            limit = 20;
        }

        EntityQueryLambda<MetricsEntity> q = new EntityQueryLambda<>(MetricsEntity.class);
        if (name != null) {
            q.eq(MetricsEntity::getName, name);
        }
        EntityDatabase db = EntityDatabase.DEFAULT;
        return db.findBy(q.orderByDescending(MetricsEntity::getOccurCount).limit(limit));
    }
}
