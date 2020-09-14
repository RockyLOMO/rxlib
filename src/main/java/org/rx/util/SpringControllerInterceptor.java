package org.rx.util;

import com.google.common.base.Stopwatch;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    @Getter(AccessLevel.PROTECTED)
    private List<String> skipMethods = new CopyOnWriteArrayList<>(Arrays.toList("setServletRequest", "setServletResponse", "isSignIn"));
    @Getter(AccessLevel.PROTECTED)
    private List<Class> infoLevelExceptions = new CopyOnWriteArrayList<>();
    @Getter(AccessLevel.PROTECTED)
    private String notSignInMsg = "Not sign in";

    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1 先过滤出有RequestMapping的方法
        final Signature signature = joinPoint.getSignature();
        if (!(signature instanceof MethodSignature) || getSkipMethods().contains(signature.getName())) {
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
                    throw new InvalidOperationException(getNotSignInMsg());
                }
            }
            returnValue = joinPoint.proceed();
            long ms = watcher.elapsed(TimeUnit.MILLISECONDS);
            msg.appendLine(String.format("\tElapsed = %sms", ms));
        } catch (Exception e) {
            hasError = true;
            msg.appendLine(String.format("\n\tError:\t%s", e));
            //抛出，controller return 常量会覆盖
            throw e;
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

    @ExceptionHandler({Exception.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public Object onException(Exception e, HttpServletRequest request) {
        String msg = DEFAULT_MESSAGE, debugMsg = null;
        boolean logInfo = false;
        if (e instanceof ValidateException || NQuery.of(getInfoLevelExceptions()).any(p -> Reflects.isInstance(e, p))
                || Strings.equals(e.getMessage(), getNotSignInMsg())) {
            //参数校验错误 ignore log
            msg = e.getMessage();
            logInfo = true;
        } else if (e instanceof SystemException) {
            msg = ((SystemException) e).getFriendlyMessage();
            debugMsg = e.getMessage();
        }

        if (logInfo) {
            log.info("HttpError {} {}", request.getRequestURL().toString(), e.getMessage());
        } else {
            log.error("HttpError {}", request.getRequestURL().toString(), e);
        }

        return handleExceptionResponse(e, msg, debugMsg);
    }

    protected Object handleExceptionResponse(Exception e, String msg, String debugMsg) {
        return msg + "\n" + debugMsg;
    }
}
