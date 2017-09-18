package org.rx;

import org.rx.cache.WeakCache;
import org.springframework.core.NestedRuntimeException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.rx.Contract.*;

/**
 * .fillInStackTrace()
 */
public class SystemException extends NestedRuntimeException {
    public interface EnumErrorCode {
        String[] messageKeys();
    }

    public static final String               ErrorFile = "errorCode";
    public static final String               DefaultMessage;
    private static final Map<String, Object> Settings;

    static {
        Settings = App.readSettings(ErrorFile);
        DefaultMessage = isNull(Settings.get("default"), "网络繁忙，请稍后再试。").toString();
    }

    public static Object[] values(Object... x) {
        return x;
    }

    private String                            friendlyMessage;
    private Map<String, Object>               data;
    /**
     * Gets the method that throws the current exception.
     */
    private BiTuple<Class, Method, ErrorCode> targetSite;
    private EnumErrorCode                     errorCode;

    @Override
    public String getMessage() {
        return isNull(friendlyMessage, super.getMessage());
    }

    public String getFriendlyMessage() {
        return isNull(friendlyMessage, DefaultMessage);
    }

    public Map<String, Object> getData() {
        if (data == null) {
            data = new HashMap<>();
        }
        return data;
    }

    protected BiTuple<Class, Method, ErrorCode> getTargetSite() {
        return targetSite;
    }

    public EnumErrorCode getErrorCode() {
        return errorCode;
    }

    protected SystemException(String message) {
        super(message);
    }

    public SystemException(Throwable cause) {
        super(cause.getMessage(), cause);
    }

    public SystemException(Object[] messageValues) {
        this(null, null, messageValues);
    }

    public SystemException(Object[] messageValues, String errorName) {
        this(errorName, null, messageValues);
    }

    public SystemException(Object[] messageValues, Throwable cause) {
        this(null, cause, messageValues);
    }

    public SystemException(String errorName, Throwable cause, Object[] messageValues) {
        super(cause != null ? cause.getMessage() : DefaultMessage, cause);

        if (messageValues == null) {
            messageValues = new Object[0];
        }
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            Set<Map.Entry<String, Object>> settings = Settings.entrySet();
            for (int i = 0; i < Math.min(8, stackTrace.length); i++) {
                StackTraceElement stack = stackTrace[i];
                Map<String, Object> methodSettings = as(Settings.get(stack.getClassName()), Map.class);
                if (methodSettings == null) {
                    continue;
                }
                Tuple<Class, Method[]> caller = as(WeakCache.getInstance().getOrAdd(stack.getClassName(), p -> {
                    try {
                        Class type = Class.forName(p);
                        return Tuple.of(type, type.getDeclaredMethods());
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                        return null;
                    }
                }), Tuple.class);
                if (caller == null) {
                    continue;
                }

                Class source = null;
                Method targetSite = null;
                ErrorCode errorCode = null;
                for (Method method : caller.right) {
                    if (!method.getName().equals(stack.getMethodName())) {
                        continue;
                    }

                    NQuery<ErrorCode> errorCodes = NQuery.of(method.getAnnotationsByType(ErrorCode.class));
                    if (!errorCodes.any()) {
                        continue;
                    }
                    if (errorName != null) {
                        errorCodes = errorCodes.where(p -> errorName.equals(p.value()));
                        if (!errorCodes.any()) {
                            continue;
                        }
                    }
                    if (cause != null) {
                        errorCodes = errorCodes.where(p -> cause.getClass().equals(p.exception()));
                        if (!errorCodes.any()) {
                            continue;
                        }
                    }
                    source = caller.left;
                    targetSite = method;
                    errorCode = errorCodes.first();
                    break;
                }
                if (errorCode == null) {
                    continue;
                }

                String k = targetSite.getName();
                String[] messageKeys = errorCode.messageKeys();
                if (messageKeys.length != messageValues.length) {
                    Logger.debug("SystemException: MessageKeys length %s not equals messageValues length %s",
                            messageKeys.length, messageValues.length);
                    return;
                }
                if (!App.isNullOrEmpty(errorCode.value())) {
                    k += "[" + errorCode.value() + "]";
                }
                if (!Exception.class.equals(errorCode.exception())) {
                    k += "<" + errorCode.exception().getSimpleName() + ">";
                }
                String msgTemp = as(methodSettings.get(k), String.class);
                if (msgTemp == null) {
                    Logger.debug("SystemException: Not fund %s key", k);
                    return;
                }

                this.targetSite = BiTuple.of(source, targetSite, errorCode);
                setFriendlyMessage(msgTemp, errorCode.messageKeys(), messageValues);
                break;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void setFriendlyMessage(String msgTemp, String[] messageKeys, Object[] messageValues) {
        for (int j = 0; j < messageKeys.length; j++) {
            String mk = messageKeys[j], mv = toJSONString(messageValues[j]);
            msgTemp = msgTemp.replace(mk, mv);
        }
        friendlyMessage = msgTemp;
    }

    public <T extends Throwable> boolean tryGet($<T> out, Class<T> exType) {
        if (exType == null) {
            return false;
        } else if (exType.isInstance(this)) {
            out.$ = (T) this;
            return true;
        } else {
            Throwable cause = this.getCause();
            if (cause == this) {
                return false;
            } else if (cause instanceof SystemException) {
                return ((SystemException) cause).tryGet(out, exType);
            } else {
                while (cause != null) {
                    if (exType.isInstance(cause)) {
                        out.$ = (T) cause;
                        return true;
                    }
                    if (cause.getCause() == cause) {
                        break;
                    }
                    cause = cause.getCause();
                }
                return false;
            }
        }
    }

    public <T extends Enum<T> & EnumErrorCode> SystemException setErrorCode(T errorCode, Object... messageValues) {
        if ((this.errorCode = errorCode) == null) {
            Logger.debug("SystemException: setErrorCode errorCode is null");
            return this;
        }
        String[] messageKeys = errorCode.messageKeys();
        if (App.isNullOrEmpty(messageKeys) || App.isNullOrEmpty(messageValues)
                || messageKeys.length != messageValues.length) {
            return this;
        }
        String pk = String.format("%s.%s", errorCode.getClass().getName(), errorCode.name());
        String pv = as(Settings.get(pk), String.class);
        if (pv == null) {
            return this;
        }

        Map<String, Object> errorData = getData();
        for (int i = 0; i < messageKeys.length; i++) {
            errorData.put(messageKeys[i], messageValues[i]);
        }
        setFriendlyMessage(pv, NQuery.of(errorData.keySet()).toArray(String.class), errorData.values().toArray());
        return this;
    }

    public SystemException setFriendlyMessage(String format, Object... args) {
        friendlyMessage = String.format(format, args);
        return this;
    }

    @Deprecated
    protected void assignFromProperties(String errorName, Throwable cause, Object[] messageValues,
                                        Map<String, String> properties) {
        if (messageValues == null) {
            messageValues = new Object[0];
        }
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            Set<Map.Entry<String, String>> settings = properties.entrySet();
            for (int i = 0; i < Math.min(8, stackTrace.length); i++) {
                StackTraceElement stack = stackTrace[i];
                String k = String.format("%s.%s", stack.getClassName(), stack.getMethodName());
                Class source = null;
                Method targetSite = null;
                ErrorCode errorCode = null;
                for (Map.Entry<String, String> entry : settings) {
                    if (!entry.getKey().startsWith(k)) {
                        continue;
                    }
                    Tuple<Class, Method[]> caller = as(WeakCache.getInstance().getOrAdd(stack.getClassName(), p -> {
                        try {
                            Class type = Class.forName(p);
                            return Tuple.of(type, type.getDeclaredMethods());
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                            return null;
                        }
                    }), Tuple.class);
                    if (caller == null) {
                        continue;
                    }

                    for (Method method : caller.right) {
                        if (!method.getName().equals(stack.getMethodName())) {
                            continue;
                        }
                        NQuery<ErrorCode> errorCodes = NQuery.of(method.getAnnotationsByType(ErrorCode.class));
                        if (!errorCodes.any()) {
                            continue;
                        }
                        if (errorName != null) {
                            errorCodes = errorCodes.where(p -> errorName.equals(p.value()));
                            if (!errorCodes.any()) {
                                continue;
                            }
                        }
                        if (cause != null) {
                            errorCodes = errorCodes.where(p -> cause.getClass().equals(p.exception()));
                            if (!errorCodes.any()) {
                                continue;
                            }
                        }
                        source = caller.left;
                        targetSite = method;
                        errorCode = errorCodes.first();
                        break;
                    }
                    if (errorCode != null) {
                        break;
                    }
                }
                if (errorCode == null) {
                    continue;
                }

                String[] messageKeys = errorCode.messageKeys();
                if (messageKeys.length != messageValues.length) {
                    Logger.debug("SystemException: MessageKeys length %s not equals messageValues length %s",
                            messageKeys.length, messageValues.length);
                    return;
                }
                if (!App.isNullOrEmpty(errorCode.value())) {
                    k += "[" + errorCode.value() + "]";
                }
                if (!Exception.class.equals(errorCode.exception())) {
                    k += "<" + errorCode.exception().getSimpleName() + ">";
                }
                String msgTemp = properties.get(k);
                if (msgTemp == null) {
                    Logger.debug("SystemException: Not fund %s key", k);
                    return;
                }

                this.targetSite = BiTuple.of(source, targetSite, errorCode);
                setFriendlyMessage(msgTemp, errorCode.messageKeys(), messageValues);
                break;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected Object recall(Object instance, Object... args) throws ReflectiveOperationException {
        require(targetSite);

        return targetSite.middle.invoke(instance, args);
    }
}
