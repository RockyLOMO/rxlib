package org.rx.net.http;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.ProceedEventArgs;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiFunc;
import org.rx.util.function.Func;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import static org.rx.core.App.*;

@Slf4j
public final class RestClient {
    static final FastThreadLocal<Type> RESULT_TYPE = new FastThreadLocal<>();

    public static <T> T facade(Class<T> contract, String serverPrefixUrl, BiFunc<String, Boolean> checkResponse) {
        RequestMapping baseMapping = contract.getAnnotation(RequestMapping.class);
        String prefix = serverPrefixUrl + getFirstPath(baseMapping);
        boolean defMethod = isPostMethod(baseMapping);
        return proxy(contract, (m, p) -> {
            RequestMapping pathMapping = m.getAnnotation(RequestMapping.class);
            String path = getFirstPath(pathMapping);
            if (Strings.isEmpty(path)) {
                path = m.getName();
            }
            String reqUrl = prefix + path;

            boolean doPost = Arrays.isEmpty(pathMapping.method()) ? defMethod : isPostMethod(pathMapping);
            Parameter[] parameters = m.getParameters();
            Func<Map<String, Object>> getFormData = () -> {
                Map<String, Object> data = new HashMap<>();
                for (int i = 0; i < parameters.length; i++) {
                    Parameter parameter = parameters[i];
                    RequestParam param = parameter.getAnnotation(RequestParam.class);
                    String name = param != null ? !Strings.isEmpty(param.value()) ? param.value() : param.name() : parameter.getName();
                    Object val = p.arguments[i];
                    if (val == null && param != null) {
                        val = Reflects.changeType(param.defaultValue(), parameter.getType());
                    }
                    data.put(name, val);
                }
                return data;
            };

            String responseText;
            ProceedEventArgs args = new ProceedEventArgs(contract, new Object[1], m.getReturnType().equals(void.class));
            HttpClient client = new HttpClient();
            try {
                if (doPost) {
                    if (parameters.length == 1 && parameters[0].isAnnotationPresent(RequestBody.class)) {
                        args.getParameters()[0] = p.arguments[0];
                        responseText = args.proceed(() -> client.postJson(reqUrl, args.getParameters()[0]).asString());
                    } else {
                        Map<String, Object> data = getFormData.invoke();
                        args.getParameters()[0] = data;
                        responseText = args.proceed(() -> client.post(reqUrl, data).asString());
                    }
                } else {
                    Map<String, Object> data = getFormData.invoke();
                    args.getParameters()[0] = data;
                    responseText = args.proceed(() -> client.get(HttpClient.buildQueryString(reqUrl, data)).asString());
                }
                if (checkResponse != null && !checkResponse.invoke(responseText)) {
                    throw new InvalidException("Response status error");
                }
            } catch (Exception e) {
                args.setError(e);
                throw new RestClientException(args.getTraceId().toString(), args.getError());
            } finally {
                App.log(args, e -> {
                    StringBuilder msg = new StringBuilder();
                    if (doPost) {
                        msg.appendLine("POST: %s %s", args.getTraceId(), reqUrl);
                    } else {
                        msg.appendLine("GET: %s %s", args.getTraceId(), reqUrl);
                    }
                    msg.appendLine("Request:\t%s", toJsonString(e.getParameters()));
                    msg.append("Response:\t%s", e.getReturnValue());
                    return msg.toString();
                });
            }
            if (m.getReturnType().equals(Void.class)) {
                return null;
            }
            return fromJson(responseText, isNull(RESULT_TYPE.get(), m.getReturnType()));
        });
    }

    private static boolean isPostMethod(RequestMapping mapping) {
        return Arrays.isEmpty(mapping.method()) || NQuery.of(mapping.method()).contains(RequestMethod.POST);
    }

    private static String getFirstPath(RequestMapping mapping) {
        return mapping != null ? NQuery.of(!Arrays.isEmpty(mapping.value()) ? mapping.value() : mapping.path()).firstOrDefault() : null;
    }
}
