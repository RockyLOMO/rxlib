package org.rx.io;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.NEnum;
import org.rx.bean.Tuple;
import org.rx.core.Disposable;
import org.rx.core.Tasks;
import org.rx.util.function.TripleAction;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static org.rx.core.Contract.*;

@Slf4j
public class FileWatcher extends Disposable {
    @RequiredArgsConstructor
    public enum ChangeKind implements NEnum<ChangeKind> {
        Create(1), Modify(2), Delete(3);

        @Getter
        private final int value;
    }

    @Getter
    private String directoryPath;
    private WatchService service;
    private volatile boolean keepHandle;
    private Future<?> future;
    private final List<Tuple<TripleAction<ChangeKind, Path>, Predicate<Path>>> callback;

    @SneakyThrows
    public FileWatcher(String directoryPath) {
        this.directoryPath = directoryPath;
        callback = new CopyOnWriteArrayList<>();

        Files.createDirectory(directoryPath);
        service = FileSystems.getDefault().newWatchService();
        Paths.get(directoryPath).register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        stop();
        service.close();
        service = null;
    }

    public boolean tryPeek(TripleAction<ChangeKind, Path> onChange) {
        require(onChange);

        WatchKey key = service.poll();
        if (key == null) {
            return false;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            raiseEvent(event, Tuple.of(onChange, null));
        }
        key.reset();
        return true;
    }

    public synchronized FileWatcher start() {
        if (future != null) {
            return this;
        }

        keepHandle = true;
        future = Tasks.run(() -> {
            while (keepHandle) {
                quietly(() -> {
                    WatchKey key = service.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        for (Tuple<TripleAction<ChangeKind, Path>, Predicate<Path>> tuple : callback) {
                            quietly(() -> raiseEvent(event, tuple));
                        }
                    }
                    key.reset();
                });
            }
            return null;
        });
        return this;
    }

    public FileWatcher callback(TripleAction<ChangeKind, Path> onChange) {
        return callback(onChange, null);
    }

    public FileWatcher callback(TripleAction<ChangeKind, Path> onChange, Predicate<Path> filter) {
        require(keepHandle, onChange);

        callback.add(Tuple.of(onChange, filter));
        return this;
    }

    @SneakyThrows
    private void raiseEvent(WatchEvent<?> event, Tuple<TripleAction<ChangeKind, Path>, Predicate<Path>> tuple) {
        WatchEvent<Path> $event = (WatchEvent<Path>) event;
        Path absolutePath = Paths.get(directoryPath, $event.context().toString());
        if (tuple.right != null && !tuple.right.test(absolutePath)) {
            return;
        }
        ChangeKind changeKind;
        if ($event.kind().equals(StandardWatchEventKinds.ENTRY_CREATE)) {
            changeKind = ChangeKind.Create;
        } else if ($event.kind().equals(StandardWatchEventKinds.ENTRY_MODIFY)) {
            changeKind = ChangeKind.Modify;
        } else {
            changeKind = ChangeKind.Delete;
        }
        tuple.left.invoke(changeKind, absolutePath);
    }

    @SneakyThrows
    protected void raiseCallback(ChangeKind kind, Path changedPath) {
        for (Tuple<TripleAction<ChangeKind, Path>, Predicate<Path>> tuple : callback) {
            tuple.left.invoke(kind, changedPath);
        }
    }

    public synchronized void stop() {
        if (future == null) {
            return;
        }

        future.cancel(false);
        future = null;
        keepHandle = false;
        callback.clear();
    }
}
