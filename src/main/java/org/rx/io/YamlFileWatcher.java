//package org.rx.io;
//
//import lombok.Getter;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.core.Delegate;
//import org.rx.core.EventArgs;
//import org.rx.core.EventTarget;
//import org.yaml.snakeyaml.Yaml;
//
//import java.io.File;
//
//@Slf4j
//public class YamlFileWatcher<T> implements EventTarget<YamlFileWatcher<T>> {
//    @RequiredArgsConstructor
//    @Getter
//    public class ChangedEventArgs extends EventArgs {
//        final String filePath;
//        final T configObject;
//    }
//
//    public static final String DEFAULT_FILE = "conf.yml";
//
//    public final Delegate<YamlFileWatcher<T>, ChangedEventArgs> onChanged = Delegate.create();
//    final Class<T> confType;
//    final FileWatcher watcher;
//
//    public YamlFileWatcher(Class<T> confType) {
//        this(confType, ".");
//    }
//
//    public YamlFileWatcher(Class<T> confType, String directoryPath) {
//        this.confType = confType;
//
//        watcher = new FileWatcher(directoryPath, p -> p.toString().endsWith(".yml"));
//        watcher.onChanged.combine((s, e) -> {
//            String path = e.getPath().toString();
//            T confObj = e.isDelete() ? null : new Yaml().loadAs(new FileStream(e.getPath().toFile()).getReader(), confType);
//            log.info("Config {} changed -> {}", path, confObj);
//            raiseEvent(onChanged, new ChangedEventArgs(path, confObj));
//        });
//    }
//
//    public void raiseChangeWithDefaultFile() {
//        raiseChange(DEFAULT_FILE);
//    }
//
//    public void raiseChange(String filePath) {
//        File f = new File(filePath);
//        if (!f.exists()) {
//            log.warn("File not found {}", filePath);
//            return;
//        }
//
//        raiseEvent(onChanged, new ChangedEventArgs(filePath, new Yaml().loadAs(new FileStream(f).getReader(), confType)));
//    }
//}
