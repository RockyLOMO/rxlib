package org.rx.util;

import com.zhongan.graphene.common.dto.ResultBase;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.rx.Contract.toJSONString;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/8/16
 * 外部调用方法记录入参与返回值
 */
@Slf4j
public class FeignClientInterceptor {
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

        org.rx.util.StringBuilder msg = new org.rx.util.StringBuilder().appendLine();
        String httpMethod = ArrayUtils.isEmpty(apiMapping.method()) ? "POST"
                : String.join(",", Arrays.stream(apiMapping.method()).map(p -> p.name()).collect(Collectors.toList()));
        msg.appendLine("%s\t\t%s", httpMethod, url);

        msg.appendLine("Request:\t%s", toJSONString(joinPoint.getArgs()));
        try {
            Object rt = joinPoint.proceed();
            msg.appendLine("Response:\t%s", toJSONString(rt));

            //这里可以check返回值
            //            if (rt instanceof ResultBase) {
            //                ResultBase<Object> rtw = (ResultBase<Object>) rt;
            //                GrapheneUtil.checkResult(rtw);
            //            }
            return rt;
        } catch (Exception ex) {
            msg.appendLine("Error:\t\t%s", ex.getMessage());
            log.error("FeignClientInterceptor proceed {}", ex);

            ResultBase<Object> errorResult = new ResultBase<>();
            errorResult.setSuccess(false);
            errorResult.setErrorMsg("网络异常, 稍后再试!");
            return errorResult;
        } finally {
            log.info(msg.toString());
        }
    }
}
