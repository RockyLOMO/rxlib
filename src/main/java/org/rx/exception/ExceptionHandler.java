package org.rx.exception;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.DbColumn;
import org.rx.bean.DateTime;
import org.rx.core.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serializable;
import java.sql.Time;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.rx.core.App.as;

@Slf4j
public final class ExceptionHandler implements Thread.UncaughtExceptionHandler {
    @Data
    public static class ErrorEntity implements Serializable {
        private static final long serialVersionUID = 8387064071982888474L;
        @DbColumn(primaryKey = true)
        int id;
        ExceptionLevel level;
        String stackTrace;
        int occurCount;

        //        String appName;
        String threadName;
        Date modifyTime;
    }

    public static final ExceptionHandler INSTANCE = new ExceptionHandler();

    static {
        Container.register(ExceptionCodeHandler.class, new DefaultExceptionCodeHandler());
        Container.register(ExceptionHandler.class, INSTANCE);
    }

    public static Object[] getMessageCandidate(Object... args) {
        if (args != null && args.length != 0) {
            int lastIndex = args.length - 1;
            Object last = args[lastIndex];
            if (last instanceof Throwable) {
                if (lastIndex == 0) {
                    return Arrays.EMPTY_OBJECT_ARRAY;
                }
                return NQuery.of(args).take(lastIndex).toArray();
            }
        }
        return args;
    }

    int keepDays;
    ScheduledFuture<?> future;

    public synchronized void setKeepDays(int keepDays) {
        if ((this.keepDays = keepDays) > 0) {
            EntityDatabase db = EntityDatabase.DEFAULT.getValue();
            db.createMapping(ErrorEntity.class);
            if (future == null) {
                future = Tasks.scheduleDaily(() -> db.delete(new EntityQueryLambda<>(ErrorEntity.class)
                        .lt(ErrorEntity::getModifyTime, DateTime.now().addDays(-keepDays))), Time.valueOf("3:00:00"));
            }
        } else {
            if (future != null) {
                future.cancel(true);
                future = null;
            }
        }
    }

    public void log(String format, Object... args) {
        Throwable e = MessageFormatter.getThrowableCandidate(args);
        if (e == null) {
            log.warn(format + "[NoThrowableCandidate]", args);
            return;
        }

        log.error(format, args);
        saveTrace(Thread.currentThread(), e);
    }

    public void log(Throwable e) {
        uncaughtException(Thread.currentThread(), e);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("Thread[{}] uncaught", t.getId(), e);
        saveTrace(t, e);
    }

    public List<ErrorEntity> queryTraces(int takeCount) {
        if (keepDays <= 0) {
            return Collections.emptyList();
        }

        EntityDatabase db = EntityDatabase.DEFAULT.getValue();
        return db.findBy(new EntityQueryLambda<>(ErrorEntity.class)
                .orderByDescending(ErrorEntity::getOccurCount)
                .limit(takeCount));
    }

    public void saveTrace(Thread t, Throwable e) {
        if (keepDays <= 0) {
            return;
        }

        String stackTrace = ExceptionUtils.getStackTrace(e);
        int pk = stackTrace.hashCode();
        EntityDatabase db = EntityDatabase.DEFAULT.getValue();
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
                entity.setStackTrace(stackTrace);
            }
            entity.occurCount++;
            entity.setThreadName(t.getName());
            entity.setModifyTime(DateTime.now());
            db.save(entity, doInsert);
            db.commit();
        } catch (Throwable ex) {
            log.error("dbTrace", ex);
            db.rollback();
        }
    }
}
