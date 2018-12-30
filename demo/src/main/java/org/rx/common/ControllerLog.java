package org.rx.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.NQuery;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Aspect
@Component
public class ControllerLog {
    private static final NQuery<String> skipMethods = NQuery.of("setServletRequest", "setServletResponse");

    @Around("execution(public * org.rx.fl.web.*.*(..))")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1 先过滤出有RequestMapping的方法
        final Signature signature = joinPoint.getSignature();
        if (!(signature instanceof MethodSignature) || skipMethods.any(p -> p.equals(signature.getName()))) {
            return joinPoint.proceed();
        }

        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        String lineSeparator = System.lineSeparator();
        StringBuilder logContent = new StringBuilder(lineSeparator);

        // 2.2 获取接口的类及方法名
        logContent.append(String.format("Class:\t%s.%s", method.getDeclaringClass().getName(), method.getName()));
        logContent.append(lineSeparator);

        // 2.3 拼装接口URL
        // 2.3.1 获取Class上的FeignURL
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
        // 2.3.2 获取Class RequestMapping URL
        RequestMapping methodReqMapping = feignMethod.getAnnotation(RequestMapping.class);
        if (methodReqMapping != null) {
            url += reqMappingFunc.apply(methodReqMapping);
        }

        logContent.append(String.format("Url：\t%s", url + lineSeparator));
        logContent.append(String.format("Request:\t%s", toString(joinPoint.getArgs(), method)));
        try {
            Stopwatch watcher = Stopwatch.createStarted();
            Object r = joinPoint.proceed();
            long ms = watcher.elapsed(TimeUnit.MILLISECONDS);
            logContent.append(String.format("\tElapsed = %sms", ms));
            logContent.append(lineSeparator + String.format("Response:\t%s", toJsonString(r)));
            return r;
        } catch (Exception ex) {
            logContent.append(lineSeparator + String.format("Error:\t%s", ex.getMessage()));
            return onException(log, ex, method);
        } finally {
            log.info(logContent.toString() + lineSeparator);
        }
    }

    private String toString(Object[] args, Method method) {
        List list = NQuery.of(args).select(o -> o instanceof MultipartFile ? "[FileStream]" : o).toList();
//        if (method.getName().equals("imageUpload") && list.size() > 0) {
//            list.set(0, String.valueOf(list.get(0)));
//        }
        return toJsonString(list);
    }

    private String toJsonString(Object val) {
        try {
            return JSON.toJSONString(val);
        } catch (Exception ex) {
            JSONObject err = new JSONObject();
            err.put("errMsg", ex.getMessage());
            return err.toJSONString();
        }
    }

    protected Object onException(org.slf4j.Logger log, Exception ex, Method method) {
        log.error("Controller Error {}", ex);

        if (method.getReturnType().equals(RestResult.class)) {
            RestResult errorResult = new RestResult();
            errorResult.setCode(2);
            errorResult.setMsg(ex.getMessage());
            return errorResult;
        }
        return null;
    }
}
