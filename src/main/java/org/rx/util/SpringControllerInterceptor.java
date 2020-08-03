package org.rx.util;

import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.rx.core.Contract.toJsonString;
import static org.rx.core.SystemException.DEFAULT_MESSAGE;

@Slf4j
@Component
@ControllerAdvice
//@Aspect
//@Order(0)
public class SpringControllerInterceptor {
    public interface IRequireSignIn {
        boolean isSignIn(String methodName, Object[] args);
    }

    private static final NQuery<String> skipMethods = NQuery.of("setServletRequest", "setServletResponse");
    protected String notSignInMsg = "Not sign in";

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1 先过滤出有RequestMapping的方法
        final Signature signature = joinPoint.getSignature();
        if (!(signature instanceof MethodSignature) || skipMethods.any(p -> p.equals(signature.getName()))) {
            return joinPoint.proceed();
        }

        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(signature.getDeclaringType());
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        StringBuilder msg = new StringBuilder().appendLine();

        // 2.1 获取接口的类及方法名
        msg.appendLine(String.format("Class:\t%s.%s", method.getDeclaringClass().getName(), method.getName()));

        // 2.2 拼装接口URL
        // 2.2.1 获取Class上的FeignURL
        String url = "";
        Function<RequestMapping, String> reqMappingFunc = p -> String.join(",", ArrayUtils.isNotEmpty(p.value()) ? p.value() : p.path());
        Class parentType = method.getDeclaringClass(), feignType = NQuery.of(parentType).firstOrDefault();
        if (feignType != null) {
            parentType = feignType;
        }
        RequestMapping baseMapping = (RequestMapping) parentType.getAnnotation(RequestMapping.class);
        if (baseMapping != null) {
            url += reqMappingFunc.apply(baseMapping);
        }

        Method feignMethod = parentType.getDeclaredMethod(method.getName(), method.getParameterTypes());
        // 2.2.2 获取Class RequestMapping URL
        RequestMapping methodReqMapping = feignMethod.getAnnotation(RequestMapping.class);
        if (methodReqMapping != null) {
            url += reqMappingFunc.apply(methodReqMapping);
        }

        msg.appendLine(String.format("Url：\t%s", url));
        msg.append(String.format("Request:\t%s", toArgsString(method, joinPoint.getArgs())));
        Object returnValue = null;
        boolean hasError = false;
        try {
            Stopwatch watcher = Stopwatch.createStarted();
            if (joinPoint.getTarget() instanceof IRequireSignIn) {
                if (!((IRequireSignIn) joinPoint.getTarget()).isSignIn(method.getName(), joinPoint.getArgs())) {
                    throw new InvalidOperationException(notSignInMsg);
                }
            }
            returnValue = joinPoint.proceed();
            long ms = watcher.elapsed(TimeUnit.MILLISECONDS);
            msg.appendLine(String.format("\tElapsed = %sms", ms));
        } catch (Exception e) {
            try {
                msg.append(String.format("Error:\t%s", e.getMessage()));
                returnValue = onException(e, msg);
            } catch (Throwable ie) {
                hasError = true;
                throw ie;
            }
        } finally {
            msg.appendLine(String.format("Response:\t%s", toJsonString(returnValue)));
            if (hasError) {
                log.error(msg.toString());
            } else {
                log.info(msg.toString());
            }
        }
        return returnValue;
    }

    protected String toArgsString(Method method, Object[] args) {
        if (ArrayUtils.isEmpty(args)) {
            return "NULL";
        }
        return toJsonString(NQuery.of(args).select(p -> {
            if (p instanceof MultipartFile) {
                return "[FileStream]";
            }
            if (p instanceof String) {
                String s = (String) p;
                if (App.isBase64String(s)) {
                    return "[Base64String]";
                }
            }
            return p;
        }).toList());
    }

    protected Object onException(Exception e, StringBuilder msg) throws Throwable {
        msg.appendLine("ControllerError:\t\t%s", e);
        throw e;
    }

    @ResponseStatus(HttpStatus.OK)
    @ExceptionHandler({Exception.class})
    public ResponseEntity handleException(Exception e, HttpServletRequest request) {
        String msg = DEFAULT_MESSAGE, debugMsg = null;
        Exception logEx = e;
        if (e instanceof ValidateException || handleInfoLevelExceptions().any(p -> Reflects.isInstance(e, p))) {
            //参数校验错误 ignore log
            msg = e.getMessage();
            logEx = null;
        } else if (e instanceof SystemException) {
            msg = ((SystemException) e).getFriendlyMessage();
            debugMsg = e.getMessage();
        }
        if (logEx != null) {
            log.error("HttpError {}", request.getRequestURL().toString(), logEx);
        }

        return ResponseEntity.ok(handleExceptionResponse(msg, debugMsg));
    }

    protected NQuery<Class> handleInfoLevelExceptions() {
        return NQuery.of();
    }

    protected Object handleExceptionResponse(String msg, String debugMsg) {
        return msg + "\n" + debugMsg;
    }
}
