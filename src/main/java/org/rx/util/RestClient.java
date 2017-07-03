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
    private static class DynamicProxy implements InvocationHandler {
        private String baseUrl, proxyHost;

        private DynamicProxy(String baseUrl, String proxyHost) {
            this.baseUrl = baseUrl;
            this.proxyHost = proxyHost;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String apiName = method.getName(),
                    httpMethod = App.isNullOrEmpty(args) ? HttpClient.GetMethod : HttpClient.PostMethod;
            boolean isFormParam = args != null && args.length > 1;
            RestMethod restMethod = method.getDeclaredAnnotation(RestMethod.class);
            if (restMethod != null) {
                if (!App.isNullOrEmpty(restMethod.apiName())) {
                    apiName = restMethod.apiName();
                }
                if (!App.isNullOrEmpty(restMethod.httpMethod())) {
                    httpMethod = restMethod.httpMethod();
                }
                isFormParam = restMethod.isFormParam();
            }
            String url = String.format("%s/%s", baseUrl, apiName);
            HttpClient client = new HttpClient();
            client.setProxyHost(proxyHost);
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
                    Parameter p = parameters[i];
                    RestParameter restParameter = p.getDeclaredAnnotation(RestParameter.class);
                    jsonEntity.put(restParameter != null ? restParameter.name() : p.getName(), args[i]);
                }
                return setResult(method, client.httpPost(url, jsonEntity));
            }

            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                Parameter p = parameters[i];
                RestParameter restParameter = p.getDeclaredAnnotation(RestParameter.class);
                params.put(restParameter != null ? restParameter.name() : p.getName(), JSON.toJSONString(args[i]));
            }
            return setResult(method, client.httpPost(url, params));
        }

        private Object setResult(Method method, String resText) {
            Class<?> returnType = method.getReturnType();
            if (returnType.equals(Void.TYPE)) {
                return Void.TYPE;
            }
            Tuple<Boolean, ?> r = App.tryConvert(resText, returnType);
            return r.Item1 ? r.Item2 : JSON.toJavaObject(JSON.parseObject(resText), returnType);
        }
    }

    public static <T> T create(Class<? extends T> restInterface, String baseUrl) {
        return create(restInterface, baseUrl, null);
    }

    public static <T> T create(Class<? extends T> restInterface, String baseUrl, String proxyHost) {
        InvocationHandler handler = new DynamicProxy(baseUrl, proxyHost);
        return (T) Proxy.newProxyInstance(handler.getClass().getClassLoader(), new Class[] { restInterface }, handler);
    }
}
