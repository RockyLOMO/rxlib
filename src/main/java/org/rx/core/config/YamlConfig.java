package org.rx.core.config;

import lombok.NonNull;
import org.rx.annotation.ErrorCode;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.exception.ApplicationException;
import org.rx.io.FileStream;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.rx.core.App.*;

public class YamlConfig {
    public static <T> T readAs(Map<String, Object> yaml, String key, Class<T> type) {
        return readAs(yaml, key, type, false);
    }

    @ErrorCode("keyError")
    @ErrorCode("partialKeyError")
    public static <T> T readAs(Map<String, Object> yaml, String key, Class<T> type, boolean throwOnEmpty) {
        Function<Object, T> func = p -> {
            if (type == null) {
                return null;
            }
            Map<String, Object> map = as(p, Map.class);
            if (map != null) {
                return fromJson(map, type);
            }
            return Reflects.changeType(p, type);
        };
        Object val;
        if ((val = yaml.get(key)) != null) {
            return func.apply(val);
        }

        StringBuilder buf = new StringBuilder();
        String[] splits = Strings.split(key, Constants.CONFIG_KEY_SPLITS);
        int c = splits.length - 1;
        for (int i = 0; i <= c; i++) {
            if (buf.length() > 0) {
                buf.append(Constants.CONFIG_KEY_SPLITS);
            }
            String k = buf.append(splits[i]).toString();
            if ((val = yaml.get(k)) == null) {
                continue;
            }
            if (i == c) {
                return func.apply(val);
            }
            if ((yaml = as(val, Map.class)) == null) {
                throw new ApplicationException("partialKeyError", values(k, type));
            }
            buf.setLength(0);
        }

        if (throwOnEmpty) {
            throw new ApplicationException("keyError", values(key, type));
        }
        return null;
    }

    public static Map<String, Object> loadYaml(@NonNull String... fileNames) {
        Map<String, Object> result = new HashMap<>();
        Yaml yaml = new Yaml();
        for (Object data : NQuery.of(fileNames).selectMany(p -> {
            NQuery<InputStream> resources = Reflects.getResources(p);
            if (resources.any()) {
                return resources.reverse();
            }
            File file = new File(p);
            return file.exists() ? Arrays.toList(new FileStream(file).getReader()) : Collections.emptyList();
        }).selectMany(yaml::loadAll)) {
            Map<String, Object> one = (Map<String, Object>) data;
            fill(one, result);
        }
        return result;
    }

    private static void fill(Map<String, Object> one, Map<String, Object> all) {
        if (one == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : one.entrySet()) {
            Map<String, Object> next;
            if ((next = as(entry.getValue(), Map.class)) == null) {
                all.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, Object> nextAll = (Map<String, Object>) all.get(entry.getKey());
            if (nextAll == null) {
                all.put(entry.getKey(), next);
                continue;
            }
            fill(next, nextAll);
        }
    }

    public static <T> String dumpYaml(@NonNull T bean) {
        Yaml yaml = new Yaml();
        return yaml.dump(bean);
    }
}
