package org.rx.net.http;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiFunc;
import org.rx.util.function.Func;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.*;

@Slf4j
public final class RestClient {
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
            Object[] args = new Object[1];
            boolean isVoid = m.getReturnType().equals(void.class);
            HttpClient client = new HttpClient();
            ProceedFunc<String> proceed;
            if (doPost) {
                if (parameters.length == 1 && parameters[0].isAnnotationPresent(RequestBody.class)) {
                    args[0] = p.arguments[0];
                    proceed = new ProceedFunc<String>() {
                        @Override
                        public boolean isVoid() {
                            return isVoid;
                        }

                        @Override
                        public String invoke() {
                            return client.postJson(reqUrl, args[0]).toString();
                        }
                    };
                } else {
                    Map<String, Object> data = getFormData.invoke();
                    args[0] = data;
                    proceed = new ProceedFunc<String>() {
                        @Override
                        public boolean isVoid() {
                            return isVoid;
                        }

                        @Override
                        public String invoke() {
                            return client.post(reqUrl, data).toString();
                        }
                    };
                }
            } else {
                Map<String, Object> data = getFormData.invoke();
                args[0] = data;
                proceed = new ProceedFunc<String>() {
                    @Override
                    public boolean isVoid() {
                        return isVoid;
                    }

                    @Override
                    public String invoke() {
                        return client.get(HttpClient.buildUrl(reqUrl, data)).toString();
                    }
                };
            }
            responseText = Sys.callLog(contract, String.format("%s %s", doPost ? "POST" : "GET", reqUrl), args, proceed, Sys.HTTP_LOG_BUILDER, null);
            if (checkResponse != null && !checkResponse.invoke(responseText)) {
                throw new InvalidException("Response status error");
            }
            if (isVoid) {
                return null;
            }
            return fromJson(responseText, ifNull(JsonTypeInvoker.JSON_TYPE.get(), m.getReturnType()));
        });
    }

    private static boolean isPostMethod(RequestMapping mapping) {
        return Arrays.isEmpty(mapping.method()) || Linq.from(mapping.method()).contains(RequestMethod.POST);
    }

    private static String getFirstPath(RequestMapping mapping) {
        return mapping != null ? Linq.from(!Arrays.isEmpty(mapping.value()) ? mapping.value() : mapping.path()).firstOrDefault() : null;
    }
}
