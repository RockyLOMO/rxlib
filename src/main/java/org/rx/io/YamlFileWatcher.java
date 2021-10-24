package org.rx.io;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.Delegate;
import org.rx.core.EventArgs;
import org.rx.core.EventTarget;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.function.Predicate;

public class YamlFileWatcher<T> implements EventTarget<YamlFileWatcher<T>> {
    @RequiredArgsConstructor
    @Getter
    public class ChangedEventArgs extends EventArgs {
        final String filePath;
        final T configObject;
    }

    public final Delegate<YamlFileWatcher<T>, ChangedEventArgs> onChanged = Delegate.create();
    final Class<T> confType;
    final FileWatcher watcher;

    public YamlFileWatcher(Class<T> confType, String directoryPath, Predicate<Path> filter) {
        this.confType = confType;

        watcher = new FileWatcher(directoryPath, filter);
        watcher.onChanged.combine((s, e) -> {
            String path = e.getPath().toString();
            T confObj = e.isDelete() ? null : new Yaml().loadAs(new FileStream(path).getReader(), confType);
            raiseEvent(onChanged, new ChangedEventArgs(path, confObj));
        });
    }

    public void raiseEvent(String filePath) {
        raiseEvent(onChanged, new ChangedEventArgs(filePath, new Yaml().loadAs(new FileStream(filePath).getReader(), confType)));
    }
}
