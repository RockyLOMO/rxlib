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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.rx.core.Extends.as;

@Slf4j
public final class ExceptionHandler implements Thread.UncaughtExceptionHandler {
    @Data
    public static class ErrorEntity implements Serializable {
        private static final long serialVersionUID = 8387064071982888474L;
        @DbColumn(primaryKey = true)
        int id;
        ExceptionLevel level;
        String message;
        String stackTrace;
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

    public static final ExceptionHandler INSTANCE = new ExceptionHandler();

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
            db.createMapping(ErrorEntity.class, MetricsEntity.class);
            if (future == null) {
                future = Tasks.scheduleDaily(() -> {
                    db.delete(new EntityQueryLambda<>(ErrorEntity.class)
                            .lt(ErrorEntity::getModifyTime, DateTime.now().addDays(-keepDays - 1)));
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

    private ExceptionHandler() {
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

    public List<ErrorEntity> queryTraces(Boolean newest, ExceptionLevel level, Integer limit) {
        if (getKeepDays() <= 0) {
            return Collections.emptyList();
        }
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

    public void saveTrace(Thread t, String msg, Throwable e) {
        if (Strings.contains(e.getMessage(), "Duplicate entry ")) {
            saveMetrics(Constants.DUPLICATE_KEY, e.getMessage());
        }

        if (getKeepDays() <= 0) {
            return;
        }

        String stackTrace = ExceptionUtils.getStackTrace(e);
        int pk = msg != null ? java.util.Arrays.hashCode(new Object[]{msg, stackTrace}) : stackTrace.hashCode();
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
                entity.setMessage(msg);
                entity.setStackTrace(stackTrace);
            }
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
