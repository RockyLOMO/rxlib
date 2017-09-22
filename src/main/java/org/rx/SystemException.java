package org.rx;

import org.rx.bean.BiTuple;
import org.rx.bean.Tuple;
import org.rx.cache.WeakCache;
import org.rx.util.StringBuilder;
import org.springframework.core.NestedRuntimeException;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.rx.Contract.*;
import static org.rx.Contract.toJSONString;

/**
 * .fillInStackTrace()
 */
public class SystemException extends NestedRuntimeException {
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
     * Gets the method that throws the current cause.
     */
    private BiTuple<Class, Method, ErrorCode> targetSite;
    private Enum                              errorCode;

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

    public Enum getErrorCode() {
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
            for (int i = 0; i < Math.min(8, stackTrace.length); i++) {
                StackTraceElement stack = stackTrace[i];
                Map<String, Object> methodSettings = as(Settings.get(stack.getClassName()), Map.class);
                if (methodSettings == null) {
                    continue;
                }
                Tuple<Class, Method[]> caller = as(WeakCache.getOrStore(this.getClass(), stack.getClassName(), p -> {
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
                    if ((errorCode = findCode(method, errorName, cause)) == null) {
                        continue;
                    }

                    source = caller.left;
                    targetSite = method;
                    break;
                }
                if (errorCode == null) {
                    continue;
                }

                this.targetSite = BiTuple.of(source, targetSite, errorCode);
                setFriendlyMessage(methodSettings, targetSite.getName(), errorCode, messageValues);
                break;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public <T extends Enum<T>> SystemException setErrorCode(T enumErrorCode, Object... messageValues) {
        if ((this.errorCode = enumErrorCode) == null) {
            Logger.debug("SystemException.setErrorCode: Parameter errorCode is null");
            return this;
        }

        try {
            Class type = enumErrorCode.getClass();
            Map<String, Object> methodSettings = as(Settings.get(type.getName()), Map.class);
            if (methodSettings == null) {
                return this;
            }
            Field field = type.getDeclaredField(enumErrorCode.name());
            ErrorCode errorCode;
            if ((errorCode = findCode(field, null, null)) == null) {
                return this;
            }

            setFriendlyMessage(methodSettings, enumErrorCode.name(), errorCode, messageValues);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return this;
    }

    private ErrorCode findCode(AccessibleObject member, String errorName, Throwable cause) {
        NQuery<ErrorCode> errorCodes = NQuery.of(member.getAnnotationsByType(ErrorCode.class));
        if (!errorCodes.any()) {
            Logger.debug("SystemException: Not found @ErrorCode in $s", member.toString());
            return null;
        }
        if (errorName != null) {
            errorCodes = errorCodes.where(p -> errorName.equals(p.value()));
        }
        if (cause != null) {
            errorCodes = errorCodes.where(p -> cause.getClass().equals(p.cause()));
        }
        if (!errorCodes.any()) {
            return null;
        }
        return errorCodes.first();
    }

    private void setFriendlyMessage(Map<String, Object> methodSettings, String messageName, ErrorCode errorCode,
                                    Object[] messageValues) {
        if (!App.isNullOrEmpty(errorCode.value())) {
            messageName += "[" + errorCode.value() + "]";
        }
        if (!Exception.class.equals(errorCode.cause())) {
            messageName += "<" + errorCode.cause().getSimpleName() + ">";
        }
        String msg = as(methodSettings.get(messageName), String.class);
        if (msg == null) {
            Logger.debug("SystemException: Not found messageName %s", messageName);
            return;
        }

        switch (errorCode.messageFormatter()) {
            case StringFormat:
                msg = String.format(msg, messageValues);
                break;
            case MessageFormat:
                msg = MessageFormat.format(msg, messageValues);
                break;
            default:
                String[] messageKeys = errorCode.messageKeys();
                if (messageKeys.length != messageValues.length) {
                    Logger.debug("SystemException: MessageKeys length %s not equals messageValues length %s",
                            messageKeys.length, messageValues.length);
                    return;
                }
                StringBuilder temp = new StringBuilder().append(msg);
                for (int j = 0; j < messageKeys.length; j++) {
                    String mk = messageKeys[j], mv = toJSONString(messageValues[j]);
                    temp.replace(mk, mv);
                }
                if (data != null) {
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        temp.replace(entry.getKey(), toJSONString(entry.getValue()));
                    }
                }
                msg = temp.toString();
                break;
        }
        friendlyMessage = msg;
    }

    public <T extends Throwable> boolean tryGet($<T> out, Class<T> exType) {
        if (out == null || exType == null) {
            return false;
        }
        if (exType.isInstance(this)) {
            out.$ = (T) this;
            return true;
        }
        Throwable cause = this.getCause();
        if (cause == this) {
            return false;
        }
        if (cause instanceof SystemException) {
            return ((SystemException) cause).tryGet(out, exType);
        }

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
                    Tuple<Class, Method[]> caller = as(
                            WeakCache.getOrStore(this.getClass(), stack.getClassName(), p -> {
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
                            errorCodes = errorCodes.where(p -> cause.getClass().equals(p.cause()));
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
                if (!Exception.class.equals(errorCode.cause())) {
                    k += "<" + errorCode.cause().getSimpleName() + ">";
                }
                String msgTemp = properties.get(k);
                if (msgTemp == null) {
                    Logger.debug("SystemException: Not found messageName %s", k);
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

    public SystemException setFriendlyMessage(String format, Object... args) {
        friendlyMessage = String.format(format, args);
        return this;
    }

    protected Object recall(Object instance, Object... args) throws ReflectiveOperationException {
        require(targetSite);

        return targetSite.middle.invoke(instance, args);
    }
}
