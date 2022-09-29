package org.rx.exception;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.DbColumn;
import org.rx.bean.DateTime;
import org.rx.core.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serializable;
import java.sql.Time;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;

import static org.rx.core.App.toJsonString;
import static org.rx.core.Extends.as;

@Slf4j
public final class TraceHandler implements Thread.UncaughtExceptionHandler {
    @Data
    public static class ErrorEntity implements Serializable {
        private static final long serialVersionUID = 8387064071982888474L;
        @DbColumn(primaryKey = true)
        long id;
        ExceptionLevel level;
        Queue<String> messages;
        String stackTrace;
        int occurCount;

        String appName;
        String threadName;
        Date modifyTime;
    }

    @Data
    public static class MethodEntity implements Serializable {
        private static final long serialVersionUID = 941255683071148L;
        @DbColumn(primaryKey = true)
        long id;
        String methodName;
        String parameters;
        long elapsedMillis;
        int occurCount;

        String appName;
        String threadName;
        Date modifyTime;
    }

    @Data
    public static class MetricsEntity implements Serializable {
        private static final long serialVersionUID = 2049476730423563051L;
        @DbColumn(primaryKey = true)
        String name;
        String message;
        Date createTime;
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

    ScheduledFuture<?> future;

    public int getKeepDays() {
        return RxConfig.INSTANCE.getTraceKeepDays();
    }

    public synchronized void setKeepDays(int keepDays) {
        if (keepDays > 0) {
            EntityDatabase db = EntityDatabase.DEFAULT;
            db.createMapping(ErrorEntity.class, MethodEntity.class, MetricsEntity.class);
            if (future == null) {
                future = Tasks.scheduleDaily(() -> {
                    DateTime d = DateTime.now().addDays(-keepDays - 1);
                    db.delete(new EntityQueryLambda<>(ErrorEntity.class)
                            .lt(ErrorEntity::getModifyTime, d));
                    db.delete(new EntityQueryLambda<>(MethodEntity.class)
                            .lt(MethodEntity::getModifyTime, d));
                    db.compact();
                }, Time.valueOf("3:00:00"));
            }
        } else {
            if (future != null) {
                future.cancel(true);
                future = null;
            }
        }
        RxConfig.INSTANCE.setTraceKeepDays(keepDays);
    }

    private TraceHandler() {
        try {
            Thread.setDefaultUncaughtExceptionHandler(this);
            setKeepDays(RxConfig.INSTANCE.getTraceKeepDays());
        } catch (Throwable e) {
            log.error("rx init error", e);
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
            saveTrace(Thread.currentThread(), tuple.getMessage(), tuple.getThrowable());
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
            saveTrace(t, null, e);
        } catch (Throwable ie) {
            ie.printStackTrace();
        }
    }

    public void saveTrace(Thread t, String msg, Throwable e) {
        if (Strings.contains(e.getMessage(), "Duplicate entry ")) {
            saveMetrics(Constants.DUPLICATE_KEY, e.getMessage());
        }

        if (getKeepDays() <= 0) {
            return;
        }

        String stackTrace = ExceptionUtils.getStackTrace(e);
        long pk = App.hash64(stackTrace);
        EntityDatabase db = EntityDatabase.DEFAULT;
        db.begin();
        try {
            ErrorEntity entity = db.findById(ErrorEntity.class, pk);
            boolean doInsert = entity == null;
            if (doInsert) {
                entity = new ErrorEntity();
                entity.setId(pk);
                InvalidException invalidException = as(e, InvalidException.class);
                ExceptionLevel level = invalidException != null && invalidException.getLevel() != null ? invalidException.getLevel()
                        : ExceptionLevel.SYSTEM;
                entity.setLevel(level);
                entity.setMessages(new ConcurrentLinkedQueue<>());
                entity.setStackTrace(stackTrace);
            }
            Queue<String> queue = entity.getMessages();
            if (queue.size() > RxConfig.INSTANCE.getTraceErrorMessageSize()) {
                queue.poll();
            }
            queue.offer(String.format("%s\t%s", DateTime.now().toDateTimeString(), msg));
            entity.occurCount++;
            entity.setAppName(RxConfig.INSTANCE.getId());
            entity.setThreadName(t.getName());
            entity.setModifyTime(DateTime.now());
            db.save(entity, doInsert);
            db.commit();
        } catch (Throwable ex) {
            log.error("dbTrace", ex);
            db.rollback();
        }
    }

    public List<ErrorEntity> queryTraces(Boolean newest, ExceptionLevel level, Integer limit) {
        if (newest == null) {
            newest = Boolean.FALSE;
        }
        if (limit == null) {
            limit = 20;
        }

        EntityQueryLambda<ErrorEntity> q = new EntityQueryLambda<>(ErrorEntity.class).limit(limit);
        if (newest) {
            q.orderByDescending(ErrorEntity::getModifyTime);
        } else {
            q.orderByDescending(ErrorEntity::getOccurCount);
        }
        if (level != null) {
            q.eq(ErrorEntity::getLevel, level);
        }
        EntityDatabase db = EntityDatabase.DEFAULT;
        return db.findBy(q);
    }

    public void saveTrace(Class<?> declaringType, String methodName, Object[] parameters, long elapsedMillis) {
        if (getKeepDays() <= 0 || elapsedMillis < RxConfig.INSTANCE.getTraceSlowElapsedMillis()) {
            return;
        }

        String fullName = String.format("%s.%s", declaringType.getName(), methodName);
        long pk = App.hash64(fullName);
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
            entity.setParameters(toJsonString(parameters));
            entity.elapsedMillis = Math.max(entity.elapsedMillis, elapsedMillis);
            entity.occurCount++;
            entity.setAppName(RxConfig.INSTANCE.getId());
            entity.setThreadName(Thread.currentThread().getName());
            entity.setModifyTime(DateTime.now());
            db.save(entity, doInsert);
            db.commit();
        } catch (Throwable e) {
            log.error("dbTrace", e);
            db.rollback();
        }
    }

    public List<MethodEntity> queryTraces(String methodNamePrefix, Integer limit) {
        if (limit == null) {
            limit = 20;
        }

        EntityQueryLambda<MethodEntity> q = new EntityQueryLambda<>(MethodEntity.class)
                .orderByDescending(MethodEntity::getElapsedMillis).limit(limit);
        if (methodNamePrefix != null) {
            q.like(MethodEntity::getMethodName, String.format("%s%%", methodNamePrefix));
        }
        EntityDatabase db = EntityDatabase.DEFAULT;
        return db.findBy(q);
    }

    public void saveMetrics(String name, String message) {
        MetricsEntity entity = new MetricsEntity();
        entity.setName(name);
        entity.setMessage(message);
        entity.setCreateTime(DateTime.now());
        EntityDatabase db = EntityDatabase.DEFAULT;
        db.save(entity, true);
    }

    public List<MetricsEntity> queryMetrics(String name, Integer limit) {
        EntityQueryLambda<MetricsEntity> q = new EntityQueryLambda<>(MetricsEntity.class);
        if (name != null) {
            q.eq(MetricsEntity::getName, name);
        }
        if (limit == null) {
            limit = 20;
        }

        EntityDatabase db = EntityDatabase.DEFAULT;
        return db.findBy(q.orderByDescending(MetricsEntity::getCreateTime).limit(limit));
    }
}
