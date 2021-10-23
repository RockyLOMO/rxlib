package org.rx.core;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.FileStream;
import org.rx.io.Files;
import org.rx.util.function.BiAction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
public class ShellCommander extends Disposable implements EventTarget<ShellCommander> {
    @RequiredArgsConstructor
    @Getter
    public static class LineBean implements Serializable {
        private static final long serialVersionUID = 4598104225029493537L;
        final int lineNumber;
        final String line;

        @Override
        public String toString() {
            return String.format("%s.\t%s", lineNumber, line);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class ExitedEventArgs extends EventArgs {
        private static final long serialVersionUID = 6563058539741657972L;
        final int exitValue;
    }

    @RequiredArgsConstructor
    static class FileOutHandler extends Disposable implements BiAction<LineBean> {
        final FileStream fileStream;

        @Override
        protected void freeObjects() {
            fileStream.close();
        }

        @Override
        public void invoke(LineBean l) throws Throwable {
//            System.out.print("[debug]:" + l.toString());
            ByteBuf buf = Bytes.directBuffer();
            buf.writeCharSequence(String.valueOf(l.lineNumber), StandardCharsets.UTF_8);
            buf.writeCharSequence(".\t", StandardCharsets.UTF_8);
            buf.writeCharSequence(l.line, StandardCharsets.UTF_8);
            buf.writeCharSequence("\n", StandardCharsets.UTF_8);
            try {
                fileStream.write(buf);
            } finally {
                buf.release();
            }
        }
    }

    public static final BiAction<LineBean> CONSOLE_OUT = l -> System.out.print(l.toString());
    static final String WIN_CMD = "cmd /c ";
    //        static final String WIN_CMD = "";
    static final List<ShellCommander> KILL_LIST = Collections.synchronizedList(new ArrayList<>());

    static {
        Tasks.addShutdownHook(() -> {
            for (ShellCommander executor : KILL_LIST) {
                executor.kill();
            }
        });
    }

    public static BiAction<LineBean> fileOut(String filePath) {
        Files.createDirectory(filePath);
        return new FileOutHandler(new FileStream(filePath));
    }

    public volatile BiConsumer<ShellCommander, ExitedEventArgs> Exited;

    @Getter
    private final String shell;
    private final File workspace;
    private final long intervalPeriod;
    private volatile Process process;
    private BiAction<LineBean> outHandler;
    private CompletableFuture<Void> daemonFuture;

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public ShellCommander(String shell) {
        this(shell, null);
    }

    public ShellCommander(String shell, String workspace) {
        this(shell, workspace, 500, true);
    }

    public ShellCommander(@NonNull String shell, String workspace, long intervalPeriod, boolean killOnExited) {
        boolean isPath = Files.isPath(shell);
        if (!isPath && App.IS_OS_WINDOWS && !shell.startsWith(WIN_CMD)) {
            shell = WIN_CMD + shell;
        }
        this.shell = shell;

        if (isPath) {
            workspace = FilenameUtils.getFullPathNoEndSeparator(shell);
        }
        this.workspace = workspace == null ? null : new File(workspace);

        this.intervalPeriod = Math.max(1, intervalPeriod);

        if (killOnExited) {
            KILL_LIST.add(this);
        }
    }

    @Override
    protected void freeObjects() {
        tryClose(outHandler);
        kill();
        KILL_LIST.remove(this);
    }

    public ShellCommander start() {
        return start(outHandler);
    }

    @SneakyThrows
    public synchronized ShellCommander start(BiAction<LineBean> outHandler) {
        if (isRunning()) {
            throw new InvalidException("already started");
        }

        this.outHandler = outHandler;

        //Runtime.getRuntime().exec(shell, null, workspace)
        StringTokenizer st = new StringTokenizer(shell);
        String[] cmdarray = new String[st.countTokens()];
        for (int i = 0; st.hasMoreTokens(); i++) {
            cmdarray[i] = st.nextToken();
        }
        Process tmp = process = new ProcessBuilder(cmdarray)
                .directory(workspace)
                .redirectErrorStream(true)  //合并getInputStream和getErrorStream
                .start();
        log.info("start {}", shell);

        if (daemonFuture != null) {
            daemonFuture.cancel(true);
        }
        daemonFuture = Tasks.run(() -> {
            LineNumberReader reader = null;
            try {
                if (outHandler != null) {
                    reader = new LineNumberReader(new InputStreamReader(tmp.getInputStream(), StandardCharsets.UTF_8));
                }

                while (tmp.isAlive()) {
                    LineNumberReader finalReader = reader;
                    quietly(() -> handleIn(finalReader));
                    Thread.sleep(intervalPeriod);
                }
            } finally {
                tryClose(reader);
                int exitValue = tmp.exitValue();
                log.info("exit={} {}", exitValue, shell);
                raiseEvent(Exited, new ExitedEventArgs(exitValue));
            }
        });
        return this;
    }

    @SneakyThrows
    private void handleIn(LineNumberReader reader) {
        if (reader == null) {
            return;
        }

        String line;
        while ((line = reader.readLine()) != null) {
            outHandler.invoke(new LineBean(reader.getLineNumber(), line));
        }
    }

    @SneakyThrows
    public int waitFor() {
        if (!isRunning()) {
            return 0;
        }
        return process.waitFor();
    }

    @SneakyThrows
    public boolean waitFor(int timeoutSeconds) {
        return isRunning() && process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    }

    public synchronized void kill() {
        if (!isRunning()) {
            return;
        }

        log.info("kill {}", shell);
        process.destroyForcibly();
        daemonFuture.cancel(true);
        raiseEvent(Exited, new ExitedEventArgs(process.exitValue()));
    }

    public synchronized void restart() {
        log.info("restart {}", shell);
        kill();
        start();
    }

    public ShellCommander autoRestart() {
        Exited = combine((s, e) -> restart(), Exited);
        return this;
    }
}
