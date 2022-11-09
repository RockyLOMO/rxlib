package org.rx.core;

import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.io.FileStream;
import org.rx.io.FileWatcher;
import org.rx.io.Files;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.values;

@Slf4j
public class YamlConfiguration implements EventTarget<YamlConfiguration> {
    @RequiredArgsConstructor
    @Getter
    public static class ChangedEventArgs extends EventArgs {
        private static final long serialVersionUID = -1217316266335592369L;
        final String filePath;
    }

    static final String DEFAULT_CONFIG_FILE = "application.yml";
    public static final YamlConfiguration RX_CONF = new YamlConfiguration(Constants.RX_CONFIG_FILE, DEFAULT_CONFIG_FILE);

    public static Map<String, Object> loadYaml(String... fileNames) {
        return loadYaml(Linq.from(fileNames).selectMany(p -> {
            File file = new File(p);
            if (file.exists()) {
                return Arrays.toList(new FileStream(file).getReader());
            }
            Linq<InputStream> resources = Reflects.getResources(p);
            if (resources.any()) {
                return resources.reverse();
            }
            return resources;
        }).toList());
    }

    public static Map<String, Object> loadYaml(List<InputStream> streams) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(streams)) {
            return result;
        }
        Yaml yaml = new Yaml();
        for (Object data : Linq.from(streams).selectMany(yaml::loadAll)) {
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

    public final Delegate<YamlConfiguration, ChangedEventArgs> onChanged = Delegate.create();
    final String[] fileNames;
    @Getter
    final Map<String, Object> yaml;
    String outputFile;
    FileWatcher watcher;

    public YamlConfiguration(@NonNull String... fileNames) {
        yaml = loadYaml(this.fileNames = fileNames);
    }

    public YamlConfiguration enableWatch() {
        if (fileNames.length == 0) {
            throw new InvalidException("Empty loaded fileNames");
        }
        return enableWatch(fileNames[fileNames.length - 1]);
    }

    public synchronized YamlConfiguration enableWatch(@NonNull String outputFile) {
        if (watcher != null) {
            throw new InvalidException("Already watched");
        }

        if (!yaml.isEmpty()) {
            try (FileStream fs = new FileStream(outputFile)) {
                fs.setPosition(0);
                fs.writeString(new Yaml().dumpAsMap(yaml));
                fs.flip();
            }
        }
        watcher = new FileWatcher(Files.getFullPath(this.outputFile = outputFile), p -> p.toString().equals(this.outputFile));
        watcher.onChanged.combine((s, e) -> {
            String filePath = e.getPath().toString();
            log.info("Config changing {} {} -> {}", e.isCreate(), filePath, yaml);
            synchronized (this) {
                yaml.clear();
                if (!e.isDelete()) {
                    write(filePath);
                }
            }
            log.info("Config changed {} {} -> {}", e.isCreate(), filePath, yaml);
            raiseEvent(onChanged, new ChangedEventArgs(filePath));
        });

        return this;
    }

    public synchronized void raiseChange() {
        raiseChange(outputFile);
    }

    public synchronized void raiseChange(String filePath) {
        if (filePath == null) {
            return;
        }
        File f = new File(filePath);
        if (!f.exists()) {
            log.warn("File not found {}", filePath);
            return;
        }

        write(filePath);
        log.info("Config changed {} -> {}", filePath, yaml);
        raiseEvent(onChanged, new ChangedEventArgs(filePath));
    }

    public synchronized YamlConfiguration write(@NonNull String fileName) {
        String[] clone = fileNames.clone();
        yaml.putAll(loadYaml(Arrays.add(clone, fileName)));
        return this;
    }

    public <T> T read(String key, T defaultVal) {
        Object val = readAs(key, Object.class);
        return val != null ? (T) val : defaultVal;
    }

    public <T> T readAs(Class<T> type) {
        return readAs(null, type, false);
    }

    public <T> T readAs(String key, Class<T> type) {
        return readAs(key, type, false);
    }

    @ErrorCode("keyError")
    @ErrorCode("partialKeyError")
    public synchronized <T> T readAs(String key, Class<T> type, boolean throwOnEmpty) {
        Map<String, Object> tmp = yaml;
        if (key == null) {
            return convert(tmp, type);
        }

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
//            new Yaml().loadAs()
            return new JSONObject(map).toJavaObject(type);
        }
        return Reflects.changeType(p, type);
    }
}
