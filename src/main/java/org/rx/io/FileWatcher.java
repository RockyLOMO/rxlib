package org.rx.io;

import lombok.Getter;
import lombok.NonNull;
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
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static org.rx.core.App.*;

@Slf4j
public class FileWatcher extends Disposable {
    @RequiredArgsConstructor
    public enum ChangeKind implements NEnum<ChangeKind> {
        CREATE(1), MODIFY(2), DELETE(3);

        @Getter
        final int value;
    }

    @Getter
    private final String directoryPath;
    private volatile boolean keepHandle;
    private final WatchService service;
    private final Future<?> future;
    private final List<Tuple<TripleAction<ChangeKind, Path>, Predicate<Path>>> callback = new CopyOnWriteArrayList<>();

    @SneakyThrows
    public FileWatcher(String directoryPath) {
        this.directoryPath = directoryPath;

        Files.saveDirectory(directoryPath);
        service = FileSystems.getDefault().newWatchService();
        Paths.get(directoryPath).register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

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
        });
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        keepHandle = false;
        future.cancel(false);
        callback.clear();
        service.close();
    }

    public boolean tryPeek(@NonNull TripleAction<ChangeKind, Path> onChange) {
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

    public FileWatcher callback(TripleAction<ChangeKind, Path> onChange) {
        return callback(onChange, null);
    }

    public FileWatcher callback(@NonNull TripleAction<ChangeKind, Path> onChange, Predicate<Path> filter) {
        Objects.requireNonNull(keepHandle);

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
            changeKind = ChangeKind.CREATE;
        } else if ($event.kind().equals(StandardWatchEventKinds.ENTRY_MODIFY)) {
            changeKind = ChangeKind.MODIFY;
        } else {
            changeKind = ChangeKind.DELETE;
        }
        tuple.left.invoke(changeKind, absolutePath);
    }

    @SneakyThrows
    protected void raiseCallback(ChangeKind kind, Path changedPath) {
        for (Tuple<TripleAction<ChangeKind, Path>, Predicate<Path>> tuple : callback) {
            tuple.left.invoke(kind, changedPath);
        }
    }
}
