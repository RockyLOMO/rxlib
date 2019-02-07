package org.rx.util;

import com.alibaba.fastjson.JSONObject;
import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Object;
import com.google.common.annotations.Beta;
import com.google.common.base.Strings;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.common.*;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.rx.common.Contract.*;

@Beta
@Slf4j
public final class JsonMapper extends Disposable {
    private static class V8Console {
        public void log(String message) {
            log.info("[V8] {}", message);
        }

        public void error(String message) {
            log.error("[V8] {}", message);
        }
    }

    public static final JsonMapper Default = new JsonMapper("jScript/");
    private static final String scriptFunc = "(function(){var $={},$val=JSON.parse(_x); %s; return JSON.stringify($);})()";

    private Map<String, Object> settings;
    private Lazy<V8> runtime;

    public JsonMapper(String configPath) {
        settings = refreshSettings(configPath);
        runtime = new Lazy<>(() -> {
            V8 v8 = V8.createV8Runtime();
            V8Object v8Console = new V8Object(v8);
            v8.add("console", v8Console);
            V8Console console = new V8Console();
            Class[] argTypes = new Class[]{String.class};
            v8Console.registerJavaMethod(console, "log", "log", argTypes);
            v8Console.registerJavaMethod(console, "error", "error", argTypes);
            v8.executeScript("console.log('V8 start..');");
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

    public <F, T> T convertTo(Class<T> toType, F from) {
        require(toType, from);

        return convertTo(toType, from.getClass().getName(), from);
    }

    public <T> T convertTo(Class<T> toType, String configKey, Object sourceValue) {
        require(toType, configKey);

        String script = getScript(toType, configKey);
        V8 v8 = runtime.getValue();
        v8.add("_x", toJsonString(sourceValue));
        String jResult = v8.executeStringScript(String.format(scriptFunc, script));
        return JSONObject.parseObject(jResult, toType);
    }

    @ErrorCode(value = "keyError", messageKeys = {"$key"})
    private String getScript(Class tType, String key) {
        String tKey = tType.getName();
        Map<String, Object> v = as(settings.get(tKey), Map.class);
        if (v == null) {
            throw new SystemException(values(tKey), "keyError");
        }
        String script = (String) v.get(key);
        if (Strings.isNullOrEmpty(script)) {
            throw new SystemException(values(tKey + "." + key), "keyError");
        }
        return script;
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
                map.putAll(App.loadYaml(p.toString()));
            } catch (Exception e) {
                log.error("refreshSettings", e);
            }
        }
        return map;
    }
}
