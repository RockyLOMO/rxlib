package org.rx.io;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.core.*;
import org.rx.util.function.PredicateFunc;

import java.nio.file.*;
import java.util.concurrent.Future;

import static org.rx.core.Extends.quietly;

@Slf4j
public class FileWatcher extends Disposable implements EventPublisher<FileWatcher> {
    @RequiredArgsConstructor
    public static class FileChangeEventArgs extends EventArgs {
        @Getter
        final Path path;
        final WatchEvent.Kind<Path> changeKind;

        public boolean isCreate() {
            return StandardWatchEventKinds.ENTRY_CREATE.equals(changeKind);
        }

        public boolean isModify() {
            return StandardWatchEventKinds.ENTRY_MODIFY.equals(changeKind);
        }

        public boolean isDelete() {
            return StandardWatchEventKinds.ENTRY_DELETE.equals(changeKind);
        }
    }

    public final Delegate<FileWatcher, FileChangeEventArgs> onChanged = Delegate.create();
    @Getter
    private final String directoryPath;
    private final WatchService service;
    private final Future<?> future;
    @Setter
    private PredicateFunc<Path> filter;

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.QUIETLY.flags();
    }

    public FileWatcher(String directoryPath) {
        this(directoryPath, null);
    }

    @SneakyThrows
    public FileWatcher(String directoryPath, PredicateFunc<Path> filter) {
        this.directoryPath = directoryPath;
        this.filter = filter;

        Files.createDirectory(directoryPath);
        service = FileSystems.getDefault().newWatchService();
        Paths.get(directoryPath).register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

        future = Tasks.run(() -> {
            while (!isClosed()) {
                quietly(() -> {
                    WatchKey key = service.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        raiseEvent(event);
                    }
                    key.reset();
                });
            }
        });
    }

    @Override
    protected void freeObjects() throws Throwable {
        future.cancel(true);
        service.close();
    }

    private void raiseEvent(WatchEvent<?> tmp) {
        WatchEvent<Path> event = (WatchEvent<Path>) tmp;
        Path absolutePath = Paths.get(directoryPath, event.context().toString());
        if (filter != null && !filter.test(absolutePath)) {
            return;
        }
        raiseEvent(onChanged, new FileChangeEventArgs(absolutePath, event.kind()));
    }
}
