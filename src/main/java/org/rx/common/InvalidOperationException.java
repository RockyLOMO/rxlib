package org.rx.common;

import org.rx.util.App;
import org.springframework.core.NestedRuntimeException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.toJSONString;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/9/7
 */
public class InvalidOperationException extends NestedRuntimeException {
    public static final String               ErrorFile  = "ErrorCode";
    public static final String               DefaultKey = "default", DefaultMsg = "网络繁忙，请稍后再试。";
    private static final Map<String, String> settings   = App.readSettings(ErrorFile);

    private String                           friendlyMessage;
    private Enum                             errorCode;
    private Map<String, Object>              data;
    /**
     * Gets or sets the class of the object that causes the error.
     */
    private Class                            source;
    /**
     * Gets the method that throws the current exception.
     */
    private Method                           targetSite;

    @Override
    public String getMessage() {
        return isNull(friendlyMessage, super.getMessage());
    }

    public String getFriendlyMessage() {
        return isNull(friendlyMessage, isNull(settings.get(DefaultKey), DefaultMsg));
    }

    public Enum getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getData() {
        if (data == null) {
            data = new HashMap<>();
        }
        return data;
    }

    public InvalidOperationException(String format, Object... args) {
        super(String.format(format, args));
    }

    public InvalidOperationException(Throwable ex) {
        super(ex.getMessage(), ex);
    }

    public InvalidOperationException(Enum errorCode, Tuple<String, Object>... data) {
        super(isNull(settings.get(DefaultKey), DefaultMsg));

        if ((this.errorCode = errorCode) == null) {
            return;
        }
        Map<String, Object> errorData = getData();
        if (!App.isNullOrEmpty(data)) {
            for (Tuple<String, Object> tuple : data) {
                errorData.put(tuple.left, tuple.right);
            }
        }
        String pk = String.format("%s.%s", errorCode.getClass().getName(), errorCode.name());
        String pv = settings.get(pk);
        if (pv == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : errorData.entrySet()) {
            String k = entry.getKey(), v = isNull(entry.getValue(), "").toString();
            //            System.out.println(k + ": " + v);
            pv = pv.replace(k, v);
        }
        friendlyMessage = pv;
        return;
    }

    public InvalidOperationException setSource(Class source, Object... args) {
        if (source == null) {
            return this;
        }
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String log = toJSONString(source.getName(), stackTrace);
        System.out.println(log);
        StackTraceElement target = SQuery.of(stackTrace).where(p -> p.getClassName().equals(source.getName()))
                .firstOrDefault();
        if (target == null) {
            return this;
        }
        log = toJSONString(target.getMethodName(), args);
        System.out.println(log);
        Method caller = null;
        if (App.isNullOrEmpty(args)) {
            try {
                caller = source.getMethod(target.getMethodName());
                this.source = source;
                this.targetSite = caller;
                System.out.println("setSource ok");
            } catch (NoSuchMethodException ex) {
                System.out.println(ex.getMessage());
            }
        }
        if (caller == null) {
            SQuery<Method> query = SQuery.of(source.getDeclaredMethods()).where(p -> {
                p.setAccessible(true);
                if (p.getParameterCount() != args.length) {
                    return false;
                }
                Class[] types = p.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    if (args[i] == null) {
                        continue;
                    }
                    if (!args[i].getClass().equals(types[i])) {
                        return false;
                    }
                }
                return true;
            });
            if (query.count() == 1) {
                caller = query.single();
                this.source = source;
                this.targetSite = caller;
                System.out.println("setSource ok");
            }
        }
        return this;
    }

    public InvalidOperationException setFriendlyMessage(String format, Object... args) {
        friendlyMessage = String.format(format, args);
        return this;
    }
}
