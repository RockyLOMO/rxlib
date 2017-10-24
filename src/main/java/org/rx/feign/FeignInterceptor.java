package org.rx.feign;

import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.Logger;
import org.rx.SystemException;
import org.rx.util.StringBuilder;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.rx.Contract.toJsonString;

public class FeignInterceptor {
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        if (!(signature instanceof MethodSignature)) {
            return joinPoint.proceed();
        }
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        RequestMapping apiMapping = method.getAnnotation(RequestMapping.class);
        if (apiMapping == null) {
            return joinPoint.proceed();
        }

        String url = "";
        FeignClient feignClient = null;
        for (Class<?> pi : joinPoint.getTarget().getClass().getInterfaces()) {
            if ((feignClient = pi.getAnnotation(FeignClient.class)) != null) {
                break;
            }
        }
        if (feignClient != null) {
            url += feignClient.url();
        }
        RequestMapping baseMapping = method.getDeclaringClass().getAnnotation(RequestMapping.class);
        Function<RequestMapping, String> pf = p -> String.join(",",
                !ArrayUtils.isEmpty(p.value()) ? p.value() : p.path());
        if (baseMapping != null) {
            url += pf.apply(baseMapping);
        }
        url += pf.apply(apiMapping);

        StringBuilder msg = new StringBuilder().appendLine();
        String httpMethod = ArrayUtils.isEmpty(apiMapping.method()) ? "POST"
                : String.join(",", Arrays.stream(apiMapping.method()).map(p -> p.name()).collect(Collectors.toList()));
        msg.appendLine("%s\t\t%s", httpMethod, url);

        msg.appendLine("Request:\t%s", toJsonString(joinPoint.getArgs()));
        try {
            Object r = onProcess(joinPoint, msg);
            msg.appendLine("Response:\t%s", toJsonString(r));
            return r;
        } catch (Exception ex) {
            msg.appendLine("Error:\t\t%s", ex.getMessage());
            return onException(ex);
        } finally {
            Logger.info(msg.toString());
        }
    }

    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) throws Throwable {
        return joinPoint.proceed();
    }

    protected Object onException(Exception ex) {
        throw SystemException.wrap(ex);
    }
}
