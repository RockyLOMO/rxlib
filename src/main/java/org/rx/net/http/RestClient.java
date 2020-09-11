package org.rx.net.http;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.StringBuilder;
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

import static org.rx.core.Contract.*;

@Slf4j
public final class RestClient {
    static final ThreadLocal<Type> RESULT_TYPE = new ThreadLocal<>();

    public static <T> T facade(Class<T> contract, String serverPrefixUrl, BiFunc<String, Boolean> checkResponse) {
        RequestMapping baseMapping = contract.getAnnotation(RequestMapping.class);
        String prefix = serverPrefixUrl + getFirstPath(baseMapping);
        boolean defMethod = isPostMethod(baseMapping);
        return proxy(contract, (m, a, t) -> {
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
                    Object val = a[i];
                    if (val == null && param != null) {
                        val = catchCall(() -> Reflects.changeType(param.defaultValue(), parameter.getType()));
                    }
                    data.put(name, val);
                }
                return data;
            };

            Exception ex = null;
            StringBuilder logMsg = new StringBuilder();
            String responseText = null;
            HttpClient client = new HttpClient();
            try {
                if (doPost) {
                    logMsg.appendLine("POST: %s", reqUrl);
                    if (parameters.length == 1 && parameters[0].isAnnotationPresent(RequestBody.class)) {
                        logMsg.appendLine("Request:\t%s", toJsonString(a[0]));
                        responseText = client.post(reqUrl, a[0]);
                    } else {
                        Map<String, Object> data = getFormData.invoke();
                        logMsg.appendLine("Request:\t%s", toJsonString(data));
                        responseText = client.post(reqUrl, data);
                    }
                } else {
                    logMsg.appendLine("GET: %s", reqUrl);
                    Map<String, Object> data = getFormData.invoke();
                    logMsg.appendLine("Request:\t%s", toJsonString(data));
                    responseText = client.get(HttpClient.buildQueryString(reqUrl, data));
                }
            } catch (Exception e) {
                ex = e;
            }
            logMsg.append("Response:\t%s", responseText);
            if (ex != null || (checkResponse != null && !checkResponse.invoke(responseText))) {
                throw new RestErrorException(logMsg.toString(), ex);
            }
            log.info(logMsg.toString());
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
