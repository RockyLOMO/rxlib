package org.rx.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.Getter;
import lombok.NonNull;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;
import org.rx.io.FileStream;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.values;

public class YamlConfig {
    public static final YamlConfig RX_CONF = new YamlConfig(Constants.RX_CONFIG_FILE);

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
            Map<String, Object> sub = (Map<String, Object>) data;
            fill(sub, result);
        }
        return result;
    }

    private static void fill(Map<String, Object> child, Map<String, Object> parent) {
        if (child == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : child.entrySet()) {
            Map<String, Object> next;
            if ((next = as(entry.getValue(), Map.class)) == null) {
                parent.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, Object> nextAll = (Map<String, Object>) parent.get(entry.getKey());
            if (nextAll == null) {
                parent.put(entry.getKey(), next);
                continue;
            }
            fill(next, nextAll);
        }
    }

    public static <T> String dumpYaml(@NonNull T bean) {
        Yaml yaml = new Yaml();
        return yaml.dump(bean);
    }

    @Getter
    final Map<String, Object> yaml;

    public YamlConfig(String fileName) {
        yaml = loadYaml(fileName);
    }

    public <T> T readAs(String key, Class<T> type) {
        return readAs(key, type, false);
    }

    @ErrorCode("keyError")
    @ErrorCode("partialKeyError")
    public <T> T readAs(String key, Class<T> type, boolean throwOnEmpty) {
        Map<String, Object> tmp = yaml;
        Object val;
        if ((val = tmp.get(key)) != null) {
            return convert(val, type);
        }

        StringBuilder buf = new StringBuilder();
        String[] splits = Strings.split(key, Constants.CONFIG_KEY_SPLITS);
        int c = splits.length - 1;
        for (int i = 0; i <= c; i++) {
            if (buf.length() > 0) {
                buf.append(Constants.CONFIG_KEY_SPLITS);
            }
            String k = buf.append(splits[i]).toString();
            if ((val = tmp.get(k)) == null) {
                continue;
            }
            if (i == c) {
                return convert(val, type);
            }
            if ((tmp = as(val, Map.class)) == null) {
                throw new ApplicationException("partialKeyError", values(k, type));
            }
            buf.setLength(0);
        }

        if (throwOnEmpty) {
            throw new ApplicationException("keyError", values(key, type));
        }
        return null;
    }

    <T> T convert(Object p, Class<T> type) {
        if (type == null) {
            return null;
        }
        Map<String, Object> map = as(p, Map.class);
        if (map != null) {
            if (type.equals(Map.class)) {
                return (T) map;
            }
            return JSON.parseObject(JSON.toJSONString(map, SerializerFeature.DisableCircularReferenceDetect), type, Feature.OrderedField);
        }
        return Reflects.changeType(p, type);
    }
}
