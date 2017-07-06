package org.rx.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.common.Func1;
import org.rx.common.Tuple;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PrioritizedParameterNameDiscoverer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by za-wangxiaoming on 2017/6/30.
 */
public class RestClient {
    private static class DynamicProxy implements InvocationHandler, MethodInterceptor {
        private String                  baseUrl, proxyHost;
        private ParameterNameDiscoverer parameterNameDiscoverer = new PrioritizedParameterNameDiscoverer();

        private DynamicProxy(String baseUrl, String proxyHost) {
            this.baseUrl = baseUrl;
            this.proxyHost = proxyHost;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass().equals(Object.class)) {
                return method.invoke(proxy, args);
            }

            String apiPath = method.getName(),
                    httpMethod = App.isNullOrEmpty(args) ? HttpClient.GetMethod : HttpClient.PostMethod;
            boolean isFormParam = args != null && args.length > 1;
            RestMethod restMethod = method.getDeclaredAnnotation(RestMethod.class);
            if (restMethod != null) {
                String temp = App.isNull(restMethod.path(), restMethod.value());
                if (!App.isNullOrEmpty(temp)) {
                    apiPath = temp;
                }
                if (!App.isNullOrEmpty(restMethod.method())) {
                    httpMethod = restMethod.method();
                }
                isFormParam = restMethod.isFormParam();
            }
            String url = String.format("%s/%s", baseUrl, apiPath);
            HttpClient client = new HttpClient();
            client.setProxyHost(proxyHost);
            if (App.equals(httpMethod, HttpClient.GetMethod, true)) {
                return setResult(method, client.httpGet(url));
            }

            Parameter[] parameters = method.getParameters();
            String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
            Func1<Integer, String> func = offset -> !App.isNullOrEmpty(parameterNames)
                    && parameters.length == parameterNames.length ? parameterNames[offset]
                            : parameters[offset].getName();
            System.out.println(method.getDeclaringClass().getName() + " pNames: " + Arrays.toString(parameterNames));
            if (!isFormParam && parameters.length == 1) {
                return setResult(method, client.httpPost(url, args[0]));
            }

            if (!isFormParam) {
                JSONObject jsonEntity = new JSONObject();
                for (int i = 0; i < parameters.length; i++) {
                    Parameter p = parameters[i];
                    RestParam restParam = p.getDeclaredAnnotation(RestParam.class);
                    jsonEntity.put(restParam != null ? App.isNull(restParam.name(), restParam.value()) : func.invoke(i),
                            args[i]);
                }
                return setResult(method, client.httpPost(url, jsonEntity));
            }

            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                Parameter p = parameters[i];
                RestParam restParam = p.getDeclaredAnnotation(RestParam.class);
                params.put(restParam != null ? App.isNull(restParam.name(), restParam.value()) : func.invoke(i),
                        JSON.toJSONString(args[i]));
            }
            return setResult(method, client.httpPost(url, params));
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            return invoke(o, method, objects);
        }

        private Object setResult(Method method, String resText) {
            Class<?> returnType = method.getReturnType();
            if (returnType.equals(Void.TYPE)) {
                return Void.TYPE;
            }
            Tuple<Boolean, ?> r = App.tryConvert(resText, returnType);
            System.out.println(r.Item1 + "," + r.Item2 + "=>" + resText + "," + returnType);
            return r.Item1 ? r.Item2 : JSON.toJavaObject(JSON.parseObject(resText), returnType);
        }
    }

    public static <T> T create(Class<? extends T> restInterface, String baseUrl) {
        return create(restInterface, baseUrl, null, true);
    }

    public static <T> T create(Class<? extends T> restInterface, String baseUrl, String proxyHost, boolean byCglib) {
        DynamicProxy handler = new DynamicProxy(baseUrl, proxyHost);
        return (T) (byCglib ? Enhancer.create(restInterface, handler)
                : Proxy.newProxyInstance(handler.getClass().getClassLoader(), new Class[] { restInterface }, handler));
    }
}
