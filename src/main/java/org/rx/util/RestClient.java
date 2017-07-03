package org.rx.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.rx.common.Tuple;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by za-wangxiaoming on 2017/6/30.
 */
public class RestClient {
    public static class DynamicProxy implements InvocationHandler {
        private String baseUrl;

        private DynamicProxy(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String apiName = method.getName(),
                    httpMethod = App.isNullOrEmpty(args) ? HttpClient.GetMethod : HttpClient.PostMethod;
            boolean isFormParam = args != null && args.length > 1;
            RestMethod restMethod = method.getDeclaredAnnotation(RestMethod.class);
            if (restMethod != null) {
                if (restMethod.apiName() != null) {
                    apiName = restMethod.apiName();
                }
                if (!App.isNullOrEmpty(restMethod.httpMethod())) {
                    httpMethod = restMethod.httpMethod();
                }
                isFormParam = restMethod.isFormParam();
            }
            String url = String.format("%s/%s", baseUrl, apiName);
            HttpClient client = new HttpClient();
            if (App.equals(httpMethod, HttpClient.GetMethod, true)) {
                return setResult(method, client.httpGet(url));
            }

            Parameter[] parameters = method.getParameters();
            if (!isFormParam && parameters.length == 1) {
                return setResult(method, client.httpPost(url, args[0]));
            }

            if (!isFormParam) {
                JSONObject jsonEntity = new JSONObject();
                for (int i = 0; i < parameters.length; i++) {
                    jsonEntity.put(parameters[i].getName(), args[i]);
                }
                return setResult(method, client.httpPost(url, jsonEntity));
            }

            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                params.put(parameters[i].getName(), JSON.toJSONString(args[i]));
            }
            return setResult(method, client.httpPost(url, params));
        }

        private Object setResult(Method method, String resText) {
            Class<?> returnType = method.getReturnType();
            Tuple<Boolean, ?> r = App.tryConvert(resText, returnType);
            return r.Item1 ? r.Item2 : JSON.toJavaObject(JSON.parseObject(resText), returnType);
        }
    }

    public static <T> T create(Class<? extends T> restInterface, String baseUrl) {
        InvocationHandler handler = new DynamicProxy(baseUrl);
        return (T) Proxy.newProxyInstance(handler.getClass().getClassLoader(), new Class[] { restInterface }, handler);
    }
}
