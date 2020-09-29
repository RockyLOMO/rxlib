package org.rx.core.exception;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.bean.Tuple;
import org.rx.core.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.rx.core.Contract.*;

@Slf4j
public class DefaultExceptionCodeHandler implements ExceptionCodeHandler {
    private static Map<String, Object> getMessageSource() {
        return Cache.getOrSet(cacheKey("getMessageSource"), k -> {
            List<String> files = new ArrayList<>();
            files.add("code.yml");
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
        }, Cache.WEAK_CACHE);
    }

    @SneakyThrows
    @Override
    public void handle(ApplicationException e) {
        Class<?> codeType = e.getErrorCode().getClass();
        if (codeType.isEnum()) {
            Map<String, Object> messageSource = as(readSetting(codeType.getName(), null, getMessageSource()), Map.class);
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

        for (StackTraceElement stack : e.getStacks()) {
            Map<String, Object> messageSource = as(readSetting(stack.getClassName(), null, getMessageSource()), Map.class);
            if (messageSource == null) {
                continue;
            }
            Tuple<Class, Method[]> caller = as(Cache.getOrSet(stack.getClassName(), p -> {
                Class type = Reflects.loadClass(p, false);
                return Tuple.of(type, type.getDeclaredMethods());
            }, Cache.WEAK_CACHE), Tuple.class);
            if (caller == null) {
                continue;
            }

            Method targetSite = null;
            ErrorCode errorCode = null;
            for (Method method : caller.right) {
                if (!method.getName().equals(stack.getMethodName())) {
                    continue;
                }
                if ((errorCode = findCode(method, e.getErrorCode().toString(), e.getCause())) == null) {
                    continue;
                }

                log.debug("SystemException: Found @ErrorCode at {}", method.toString());
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
        NQuery<ErrorCode> errorCodes = NQuery.of(member.getAnnotationsByType(ErrorCode.class));
        if (!errorCodes.any()) {
            log.debug("SystemException: Not found @ErrorCode in {}", member.toString());
            return null;
        }
        if (code != null) {
            errorCodes = errorCodes.where(p -> code.equals(p.value()));
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

    private String friendlyMessage(ErrorCode errorCode, Map<String, Object> messageSource, String code, Object[] values) {
        if (!Strings.isNullOrEmpty(errorCode.value())) {
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
                msg = MessageFormat.format(msg, values);
                break;
        }
        return msg;
    }
}
