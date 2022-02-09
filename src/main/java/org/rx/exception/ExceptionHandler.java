package org.rx.exception;

import io.netty.util.internal.SystemPropertyUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.annotation.DbColumn;
import org.rx.bean.DateTime;
import org.rx.core.Arrays;
import org.rx.core.Constants;
import org.rx.core.Container;
import org.rx.core.NQuery;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.List;

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

        String threadName;
        Date modifyTime;
    }

    static {
        Container.register(ExceptionCodeHandler.class, new DefaultExceptionCodeHandler());
        Container.register(ExceptionHandler.class, new ExceptionHandler());
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

    private ExceptionHandler() {
        if (!SystemPropertyUtil.getBoolean(Constants.TRACE_ENABLE, false)) {
            return;
        }

        enableTrace();
    }

    public void enableTrace() {
        System.setProperty(Constants.TRACE_ENABLE, Boolean.TRUE.toString());
        EntityDatabase.DEFAULT.getValue().createMapping(ErrorEntity.class);
    }

    public void uncaughtException(String format, Object... args) {
        Throwable e = MessageFormatter.getThrowableCandidate(args);
        if (e == null) {
            log.warn(format + "[NoThrowableCandidate]", args);
            return;
        }

        log.error(format, args);
        saveTrace(Thread.currentThread(), e);
    }

    public void uncaughtException(Throwable e) {
        uncaughtException(Thread.currentThread(), e);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("Thread[{}] uncaught", t.getId(), e);
        saveTrace(t, e);
    }

    public List<ErrorEntity> queryTraces(int takeCount) {
        if (!SystemPropertyUtil.getBoolean(Constants.TRACE_ENABLE, false)) {
            return Collections.emptyList();
        }

        EntityDatabase db = EntityDatabase.DEFAULT.getValue();
        return db.findBy(new EntityQueryLambda<>(ErrorEntity.class)
                .orderByDescending(ErrorEntity::getOccurCount)
                .limit(takeCount));
    }

    public void saveTrace(Thread t, Throwable e) {
        if (!SystemPropertyUtil.getBoolean(Constants.TRACE_ENABLE, false)) {
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
