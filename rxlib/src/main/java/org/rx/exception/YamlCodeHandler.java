package org.rx.exception;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.core.cache.MemoryCache;
import org.slf4j.helpers.MessageFormatter;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Map;

import static org.rx.core.Extends.as;
import static org.rx.core.Sys.fastCacheKey;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class YamlCodeHandler {
    public static final YamlCodeHandler INSTANCE = new YamlCodeHandler();

    protected YamlConfiguration getMessageSource() {
        return YamlConfiguration.RX_CONF;
    }

    @SneakyThrows
    public void handle(ApplicationException e) {
        Class<?> codeType = e.getErrorCode().getClass();
        if (codeType.isEnum()) {
            Map<String, Object> messageSource = getMessageSource().readAs(codeType.getName(), Map.class);
            if (messageSource == null) {
                return;
            }
            Enum<?> anEnum = (Enum<?>) e.getErrorCode();
            Field field = codeType.getDeclaredField(anEnum.name());
            ErrorCode errorCode;
            if ((errorCode = findCode(field, null, null)) == null) {
                errorCode = new ErrorCode() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return ErrorCode.class;
                    }

                    @Override
                    public String value() {
                        return null;
                    }

                    @Override
                    public Class<? extends Throwable> cause() {
                        return Exception.class;
                    }

                    @Override
                    public MessageFormatter messageFormatter() {
                        return MessageFormatter.MessageFormat;
                    }
                };
            }

            e.setFriendlyMessage(friendlyMessage(errorCode, messageSource, anEnum.name(), e.getCodeValues()));
            return;
        }

        Cache<String, Tuple<Class<?>, Method[]>> cache = Cache.getInstance(MemoryCache.class);
        for (StackTraceElement stack : e.getStacks()) {
            Map<String, Object> messageSource = getMessageSource().readAs(stack.getClassName(), Map.class);
            if (messageSource == null) {
                continue;
            }
            Tuple<Class<?>, Method[]> caller = as(cache.get(fastCacheKey(Constants.CACHE_REGION_ERROR_CODE, stack.getClassName()), p -> {
                Class<?> type = Reflects.loadClass(stack.getClassName(), false);
                return Tuple.of(type, type.getDeclaredMethods());
            }), Tuple.class);
            if (caller == null) {
                continue;
            }

            Method targetSite = null;
            ErrorCode errorCode = null;
            for (Method method : caller.right) {
                if (!Strings.hashEquals(method.getName(), stack.getMethodName())) {
                    continue;
                }
                if ((errorCode = findCode(method, e.getErrorCode().toString(), e.getCause())) == null) {
                    continue;
                }

                log.debug("SystemException: Found @ErrorCode at {}", method);
                targetSite = method;
                break;
            }
            if (errorCode == null) {
                continue;
            }

            e.setFriendlyMessage(friendlyMessage(errorCode, messageSource, targetSite.getName(), e.getCodeValues()));
            break;
        }

        if (e.getFriendlyMessage() == null) {
            log.debug("SystemException: Not found @ErrorCode");
        }
    }

    private ErrorCode findCode(AccessibleObject member, String code, Throwable cause) {
        Linq<ErrorCode> errorCodes = Linq.from(member.getAnnotationsByType(ErrorCode.class));
        if (!errorCodes.any()) {
            log.debug("SystemException: Not found @ErrorCode in {}", member);
            return null;
        }
        if (code != null) {
            errorCodes = errorCodes.where(p -> code.equals(p.value()));
        }
        if (cause != null) {
            errorCodes = errorCodes.orderByDescending(p -> {
                byte count = 0;
                Class<?> st = p.cause();
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

    private String friendlyMessage(ErrorCode errorCode, Map<String, Object> messageSource, String code, Object[] values) {
        if (!Strings.isEmpty(errorCode.value())) {
            code += "[" + errorCode.value() + "]";
        }
        if (!Exception.class.equals(errorCode.cause())) {
            code += "<" + errorCode.cause().getSimpleName() + ">";
        }
        String msg = as(messageSource.get(code), String.class);
        if (msg == null) {
            log.debug("SystemException: Not found messageName {}", code);
            return null;
        }

        switch (errorCode.messageFormatter()) {
            case StringFormat:
                msg = String.format(msg, values);
                break;
            default:
                msg = new StringBuilder().appendMessageFormat(msg, values).toString();
                break;
        }
        return msg;
    }
}
