package org.rx.feign;

import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.Tuple;
import org.rx.util.LogInterceptor;
import org.rx.util.StringBuilder;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FeignInterceptor extends LogInterceptor {
    @Override
    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) throws Throwable {
        Signature signature = joinPoint.getSignature();
        if (!(signature instanceof MethodSignature)) {
            return joinPoint.proceed();
        }
        Method method = ((MethodSignature) signature).getMethod();
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

        String httpMethod = ArrayUtils.isEmpty(apiMapping.method()) ? "POST"
                : String.join(",", Arrays.stream(apiMapping.method()).map(p -> p.name()).collect(Collectors.toList()));
        msg.appendLine().appendLine("%s\t\t%s", httpMethod, resolveUrl(url, signature));
        return super.onProcess(joinPoint, msg);
    }

    protected String resolveUrl(String url, Signature signature) {
        return url;
    }

    @Override
    protected Tuple<String, String> getProcessFormat() {
        return Tuple.of("Request:\t%s", "Response:\t%s");
    }
}
