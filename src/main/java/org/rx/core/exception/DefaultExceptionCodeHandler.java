package org.rx.core.exception;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.bean.RxConfig;
import org.rx.bean.Tuple;
import org.rx.core.*;

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
    private static Map<String, Object> getSettings() {
        return Cache.getOrSet(cacheKey("getSettings"), k -> {
            List<String> files = new ArrayList<>();
            files.add("code.yml");
            RxConfig rxConfig = Container.getInstance().get(RxConfig.class);
            if (!Arrays.isEmpty(rxConfig.getErrorCodeFiles())) {
                files.addAll(Arrays.toList(rxConfig.getErrorCodeFiles()));
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
        if (e.getEnumCode() != null) {
            Class type = e.getEnumCode().getClass();
            Map<String, Object> methodSettings = as(readSetting(type.getName(), null, getSettings()), Map.class);
            if (methodSettings == null) {
                return;
            }
            Field field = type.getDeclaredField(e.getEnumCode().name());
            ErrorCode errorCode;
            if ((errorCode = findCode(field, null, null)) == null) {
                return;
            }

            e.setFriendlyMessage(friendlyMessage(methodSettings, e.getEnumCode().name(), errorCode, e.getCodeValues()));
            return;
        }

        for (StackTraceElement stack : e.getStacks()) {
            Map<String, Object> methodSettings = as(readSetting(stack.getClassName(), null, getSettings()), Map.class);
            if (methodSettings == null) {
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
                if ((errorCode = findCode(method, e.getMethodCode(), e.getCause())) == null) {
                    continue;
                }

                log.debug("SystemException: Found @ErrorCode at {}", method.toString());
                targetSite = method;
                break;
            }
            if (errorCode == null) {
                continue;
            }

            e.setFriendlyMessage(friendlyMessage(methodSettings, targetSite.getName(), errorCode, e.getCodeValues()));
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

    private String friendlyMessage(Map<String, Object> methodSettings, String messageName, ErrorCode errorCode, Object[] messageValues) {
        if (!Strings.isNullOrEmpty(errorCode.value())) {
            messageName += "[" + errorCode.value() + "]";
        }
        if (!Exception.class.equals(errorCode.cause())) {
            messageName += "<" + errorCode.cause().getSimpleName() + ">";
        }
        String msg = as(methodSettings.get(messageName), String.class);
        if (msg == null) {
            log.debug("SystemException: Not found messageName {}", messageName);
            return null;
        }

        switch (errorCode.messageFormatter()) {
            case StringFormat:
                msg = String.format(msg, messageValues);
                break;
            default:
                msg = MessageFormat.format(msg, messageValues);
                break;
        }
        return msg;
    }
}
