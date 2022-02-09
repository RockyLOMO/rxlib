package org.rx.core;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.FileStream;
import org.rx.io.Files;
import org.rx.util.function.TripleAction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.*;

@Slf4j
public class ShellCommander extends Disposable implements EventTarget<ShellCommander> {
    @RequiredArgsConstructor
    @Getter
    public static class OutPrintEventArgs extends EventArgs {
        private static final long serialVersionUID = 4598104225029493537L;
        final int lineNumber;
        final String line;

        @Override
        public String toString() {
            return String.format("%s.\t%s\n", lineNumber, line);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class ExitedEventArgs extends EventArgs {
        private static final long serialVersionUID = 6563058539741657972L;
        final int exitValue;
    }

    public static class FileOutHandler extends Disposable implements TripleAction<ShellCommander, OutPrintEventArgs> {
        final FileStream fileStream;

        public FileOutHandler(String filePath) {
            Files.createDirectory(filePath);
            fileStream = new FileStream(filePath);
        }

        @Override
        protected void freeObjects() {
            fileStream.close();
        }

        @Override
        public void invoke(ShellCommander s, OutPrintEventArgs e) throws Throwable {
            //            System.out.print("[debug]:" + l.toString());
            ByteBuf buf = Bytes.directBuffer();
            buf.writeCharSequence(String.valueOf(e.lineNumber), StandardCharsets.UTF_8);
            buf.writeCharSequence(".\t", StandardCharsets.UTF_8);
            buf.writeCharSequence(e.line, StandardCharsets.UTF_8);
            buf.writeCharSequence("\n", StandardCharsets.UTF_8);
            try {
                fileStream.write(buf);
            } finally {
                buf.release();
            }
        }
    }

    public static final TripleAction<ShellCommander, OutPrintEventArgs> CONSOLE_OUT_HANDLER = (s, e) -> System.out.print(e.toString());
    static final String LINUX_BASH = "bash -c ", WIN_CMD = "cmd /c ";
    static final List<ShellCommander> KILL_LIST = newConcurrentList(true);

    static {
        Tasks.addShutdownHook(() -> {
            for (ShellCommander executor : KILL_LIST) {
                executor.kill();
            }
        });
    }

    public static int exec(String shell, String workspace) {
        return new ShellCommander(shell, workspace).start().waitFor();
    }

    public final Delegate<ShellCommander, OutPrintEventArgs> onOutPrint = Delegate.create();
    public final Delegate<ShellCommander, ExitedEventArgs> onExited = Delegate.create();

    @Getter
    private String shell;
    private final File workspace;
    private final long intervalPeriod;
    private Process process;
    private CompletableFuture<Void> daemonFuture;

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public ShellCommander setReadThenExit() {
        if (!Files.isPath(shell)) {
            if (App.IS_OS_WINDOWS && !Strings.startsWithIgnoreCase(shell, WIN_CMD)) {
                shell = WIN_CMD + shell;
            } else {
                shell = LINUX_BASH + shell;
            }
        }
        return this;
    }

    public ShellCommander setAutoRestart() {
        onExited.tail((s, e) -> restart());
        return this;
    }

    public ShellCommander(String shell) {
        this(shell, null);
    }

    public ShellCommander(String shell, String workspace) {
        this(shell, workspace, Constants.DEFAULT_INTERVAL, true);
    }

    public ShellCommander(@NonNull String shell, String workspace, long intervalPeriod, boolean killOnExited) {
        this.shell = shell = shell.trim();
        if (Files.isPath(shell)) {
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
        onOutPrint.tryClose();
        kill();
        KILL_LIST.remove(this);
    }

    @SneakyThrows
    public synchronized ShellCommander start() {
        if (isRunning()) {
            throw new InvalidException("already started");
        }

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
        log.debug("start {}", shell);

        if (daemonFuture != null) {
            daemonFuture.cancel(true);
        }
        daemonFuture = Tasks.run(() -> {
            LineNumberReader reader = null;
            try {
                if (!onOutPrint.isEmpty()) {
                    reader = new LineNumberReader(new InputStreamReader(tmp.getInputStream(), StandardCharsets.UTF_8));
                }

                while (tmp.isAlive()) {
                    try {
                        if (reader != null) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                raiseEvent(onOutPrint, new OutPrintEventArgs(reader.getLineNumber(), line));
                            }
                        }
                    } catch (Throwable e) {
                        Container.get(ExceptionHandler.class).uncaughtException("onOutPrint", e);
                    }
                    Thread.sleep(intervalPeriod);
                }
            } finally {
                tryClose(reader);
                int exitValue = tmp.exitValue();
                log.debug("exit={} {}", exitValue, shell);
                raiseEvent(onExited, new ExitedEventArgs(exitValue));
            }
        });
        return this;
    }

    @SneakyThrows
    public synchronized int waitFor() {
        if (!isRunning()) {
            return 0;
        }
        return process.waitFor();
    }

    @SneakyThrows
    public synchronized boolean waitFor(int timeoutSeconds) {
        return isRunning() && process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    }

    public synchronized void kill() {
        if (!isRunning()) {
            return;
        }

        log.debug("kill {}", shell);
        process.destroyForcibly();
        daemonFuture.cancel(true);
        raiseEvent(onExited, new ExitedEventArgs(process.exitValue()));
    }

    public synchronized void restart() {
        log.debug("restart {}", shell);
        kill();
        start();
    }
}
