package org.rx.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Object;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.*;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.rx.common.Contract.as;
import static org.rx.common.Contract.require;

@Slf4j
public final class JsonMapper extends Disposable {
    private static class V8Console {
        public void log(String message) {
            Logger.info("[V8] %s", message);
        }

        public void error(String message) {
            Logger.error(null, "[V8] %s", message);
        }
    }

    private static final String     defaultValue = "DEFAULT_VALUE";
    private static final String     scriptFunc   = "(function(){var $={},$val=JSON.parse(_x); %s return JSON.stringify($);})()";
    private static final JsonMapper instance     = new JsonMapper("jsonMapper/");

    public static <F, T> T map(F from, Class<T> toType) {
        return instance.convert(from, toType);
    }

    private Map<String, Object> settings;
    private Lazy<V8>            runtime;

    public JsonMapper(String configPath) {
        settings = refreshSettings(configPath);
        runtime = new Lazy<>(() -> {
            V8 v8 = V8.createV8Runtime();
            V8Object v8Console = new V8Object(v8);
            v8.add("console", v8Console);
            V8Console console = new V8Console();
            v8Console.registerJavaMethod(console, "log", "log", new Class[] { String.class });
            v8Console.registerJavaMethod(console, "error", "error", new Class[] { String.class });
            v8.executeScript("console.log('start..');");
            return v8;
        });
    }

    @Override
    protected void freeObjects() {
        if (runtime.isValueCreated()) {
            runtime.getValue().release();
            runtime = null;
        }
    }

    public <F, T> T convert(F from, Class<T> toType) {
        require(from, toType);

        Class fType = from.getClass();
        Map<String, Object> map = getMap(fType, toType);
        if (map == null) {
            return JSON.parseObject(JSON.toJSONString(from), toType);
        }

        Object val = JSON.toJSON(from);
        JSONObject json = as(val, JSONObject.class);
        if (json == null) {
            String jsonStr = (String) map.getOrDefault(String.valueOf(val), map.get(defaultValue));
            if (toType.isEnum()) {
                Enum name = NQuery.of(toType.getEnumConstants()).<Enum> cast().where(p -> p.name().equals(jsonStr))
                        .firstOrDefault();
                if (name == null) {
                    return null;
                }
                return (T) name;
            }
            return JSON.parseObject(jsonStr, toType);
        }

        V8 v8 = runtime.getValue();
        v8.add("_x", json.toJSONString());
        String result = v8.executeStringScript(String.format(scriptFunc, map.get("script")));
        //        System.out.println("result:" + result);
        json.putAll(JSON.parseObject(result));

        return json.toJavaObject(toType);
    }

    private Map<String, Object> getMap(Class fType, Class tType) {
        Map<String, Object> v = (Map<String, Object>) settings.get(tType.getName());
        if (v == null) {
            return null;
        }
        return (Map<String, Object>) v.get(fType.getName());
    }

    @SneakyThrows
    private Map<String, Object> refreshSettings(String configPath) {
        URL path = App.getClassLoader().getResource(configPath);
        if (path == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> map = new HashMap<>();
        for (Path p : App.fileStream(Paths.get(path.toURI()))) {
            try {
                map.putAll(App.readSettings(p.toString(), false));
            } catch (Exception e) {
                log.error("refreshSettings", e);
            }
        }
        return map;
    }
}
