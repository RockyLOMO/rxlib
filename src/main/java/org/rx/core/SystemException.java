package org.rx.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.bean.$;
import org.rx.bean.BiTuple;
import org.rx.bean.Tuple;
import org.springframework.core.NestedRuntimeException;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.*;

import static org.rx.core.Contract.*;

/**
 * 根据Exception来读取errorCode.yml的错误信息
 * ex.fillInStackTrace()
 * https://northconcepts.com/blog/2013/01/18/6-tips-to-improve-your-exception-handling/
 */
@Slf4j
public class SystemException extends NestedRuntimeException {
    public static final String CODE_FILE = "code.yml";
    public static final String DEFAULT_MESSAGE = isNull(getSettings().get("default"), "网络繁忙，请稍后再试。").toString();

    private static Map<String, Object> getSettings() {
        return MemoryCache.getOrStore("SystemException", k -> {
            List<String> files = new ArrayList<>();
            files.add(CODE_FILE);
            if (!Arrays.isEmpty(CONFIG.getErrorCodeFiles())) {
                files.addAll(Arrays.toList(CONFIG.getErrorCodeFiles()));
            }

            return isNull(catchCall(() -> {
                Map<String, Object> codes = loadYaml(NQuery.of(files).toArray(String.class));
                if (codes.isEmpty()) {
                    log.warn("load code.yml fail");
                }
                return codes;
            }), Collections.emptyMap());
        });
    }

    public static SystemException wrap(Throwable cause) {
        if (cause == null) {
            return null;
        }
        if (cause instanceof SystemException) {
            return (SystemException) cause;
        }
        return new SystemException(cause);
    }

    private String friendlyMessage;
    private Map<String, Object> data;
    /**
     * Gets the method that throws the current cause.
     */
    @Getter(AccessLevel.PROTECTED)
    private BiTuple<Class, Method, ErrorCode> targetSite;
    @Getter
    private Enum errorCode;

    @Override
    public String getMessage() {
        return isNull(friendlyMessage, super.getMessage());
    }

    public String getFriendlyMessage() {
        return isNull(friendlyMessage, DEFAULT_MESSAGE);
    }

    public Map<String, Object> getData() {
        if (data == null) {
            data = new HashMap<>();
        }
        return data;
    }

    protected SystemException(String message) {
        super(message);
    }

    protected SystemException(Throwable cause) {
        super(cause != null ? cause.getMessage() : null, cause);
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
        super(cause != null ? cause.getMessage() : null, cause);
        if (messageValues == null) {
            messageValues = Arrays.EMPTY_OBJECT_ARRAY;
        }

        for (StackTraceElement stack : Reflects.threadStack(8)) {
            Map<String, Object> methodSettings = as(readSetting(stack.getClassName(), null, getSettings()), Map.class);
            if (methodSettings == null) {
                continue;
            }
            Tuple<Class, Method[]> caller = as(MemoryCache.getOrStore(stack.getClassName(), p -> {
                Class type = Reflects.loadClass(p, false);
                return Tuple.of(type, type.getDeclaredMethods());
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

                log.debug("SystemException: Found @ErrorCode at {}", method.toString());
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

        if (friendlyMessage == null) {
            log.debug("SystemException: Not found @ErrorCode");
        }
    }

    @SneakyThrows
    public <T extends Enum<T>> SystemException setErrorCode(T enumErrorCode, Object... messageValues) {
        if ((this.errorCode = enumErrorCode) == null) {
            log.debug("SystemException.setErrorCode: Parameter errorCode is null");
            return this;
        }

        Class type = enumErrorCode.getClass();
        Map<String, Object> methodSettings = as(readSetting(type.getName(), null, getSettings()), Map.class);
        if (methodSettings == null) {
            return this;
        }
        Field field = type.getDeclaredField(enumErrorCode.name());
        ErrorCode errorCode;
        if ((errorCode = findCode(field, null, null)) == null) {
            return this;
        }

        setFriendlyMessage(methodSettings, enumErrorCode.name(), errorCode, messageValues);
        return this;
    }

    private ErrorCode findCode(AccessibleObject member, String errorName, Throwable cause) {
        NQuery<ErrorCode> errorCodes = NQuery.of(member.getAnnotationsByType(ErrorCode.class));
        if (!errorCodes.any()) {
            log.debug("SystemException: Not found @ErrorCode in {}", member.toString());
            return null;
        }
        if (errorName != null) {
            errorCodes = errorCodes.where(p -> errorName.equals(p.value()));
        }
        if (cause != null) {
            errorCodes = errorCodes.orderByDescending(p -> {
                byte count = 0;
                Class st = p.cause();
                for (; count < 8; count++) {
                    if (st.equals(Exception.class)) {
                        break;
                    }
                    st = st.getSuperclass();
                }
                return count;
            }).where(p -> p.cause().isAssignableFrom(cause.getClass()));
        }
        if (!errorCodes.any()) {
            return null;
        }
        return errorCodes.first();
    }

    private void setFriendlyMessage(Map<String, Object> methodSettings, String messageName, ErrorCode errorCode,
                                    Object[] messageValues) {
        if (!Strings.isNullOrEmpty(errorCode.value())) {
            messageName += "[" + errorCode.value() + "]";
        }
        if (!Exception.class.equals(errorCode.cause())) {
            messageName += "<" + errorCode.cause().getSimpleName() + ">";
        }
        String msg = as(methodSettings.get(messageName), String.class);
        if (msg == null) {
            log.debug("SystemException: Not found messageName {}", messageName);
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
                    log.debug("SystemException: MessageKeys length {} not equals messageValues length {}",
                            messageKeys.length, messageValues.length);
                    return;
                }
                NQuery<Tuple<String, Object>> query = NQuery.of(messageKeys)
                        .select((p, i) -> Tuple.of(p, messageValues[i]));
                if (data != null) {
                    query = query
                            .union(NQuery.of(data.entrySet()).select(p -> Tuple.of(p.getKey(), p.getValue())).toList());
                }
                StringBuilder temp = new StringBuilder().append(msg);
                for (Tuple<String, Object> tuple : query.orderByDescending(p -> p.left.length())) {
                    temp.replace(tuple.left, toJsonString(tuple.right));
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
            out.v = (T) this;
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
                out.v = (T) cause;
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
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
