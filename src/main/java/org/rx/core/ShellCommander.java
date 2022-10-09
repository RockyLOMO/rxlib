package org.rx.core;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.rx.exception.TraceHandler;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.FileStream;
import org.rx.io.Files;
import org.rx.util.function.TripleAction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.*;

@Slf4j
public class ShellCommander extends Disposable implements EventTarget<ShellCommander> {
    @RequiredArgsConstructor
    @Getter
    public static class PrintOutEventArgs extends EventArgs {
        private static final long serialVersionUID = 4598104225029493537L;
        final int lineNumber;
        final String line;

        @Override
        public String toString() {
            return String.format("%s.\t%s\n", lineNumber, line);
        }
    }

    public static class FileOutHandler extends Disposable implements TripleAction<ShellCommander, PrintOutEventArgs> {
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
        public void invoke(ShellCommander s, PrintOutEventArgs e) throws Throwable {
            ByteBuf buf = Bytes.directBuffer();
            buf.writeInt(e.lineNumber);
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

    @RequiredArgsConstructor
    @Getter
    public static class ExitedEventArgs extends EventArgs {
        private static final long serialVersionUID = 6563058539741657972L;
        final int exitValue;
    }

    public static final TripleAction<ShellCommander, PrintOutEventArgs> CONSOLE_OUT_HANDLER = (s, e) -> System.out.print(e.toString());
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

    public final Delegate<ShellCommander, PrintOutEventArgs> onPrintOut = Delegate.create();
    public final Delegate<ShellCommander, ExitedEventArgs> onExited = Delegate.create();

    final File workspace;
    final long daemonPeriod;
    @Getter
    String shell;
    Process process;
    Future<Void> daemonFuture;
    @Getter
    Integer exitValue;

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized ShellCommander setReadFullyThenExit() {
        if (!Files.isPath(shell)) {
            if (App.IS_OS_WINDOWS && !Strings.startsWithIgnoreCase(shell, WIN_CMD)) {
                shell = WIN_CMD + shell;
            } else {
                shell = LINUX_BASH + shell;
            }
        }
        return this;
    }

    public synchronized ShellCommander setAutoRestart() {
        onExited.tail((s, e) -> restart());
        return this;
    }

    public ShellCommander(String shell) {
        this(shell, null);
    }

    public ShellCommander(String shell, String workspace) {
        this(shell, workspace, Constants.DEFAULT_INTERVAL, true);
    }

    public ShellCommander(@NonNull String shell, String workspace, long daemonPeriod, boolean killOnExited) {
        this.shell = shell = shell.trim();
        if (Files.isPath(shell)) {
            workspace = FilenameUtils.getFullPathNoEndSeparator(shell);
        }
        this.workspace = workspace == null ? null : new File(workspace);

        this.daemonPeriod = Math.max(1, daemonPeriod);
        if (killOnExited) {
            KILL_LIST.add(this);
        }
    }

    @Override
    protected void freeObjects() {
        onPrintOut.close();
        kill();
        KILL_LIST.remove(this);
    }

    @SneakyThrows
    public synchronized ShellCommander start() {
        if (isRunning()) {
            throw new InvalidException("Already started");
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
        exitValue = null;

        if (daemonFuture != null) {
            daemonFuture.cancel(true);
        }
        daemonFuture = Tasks.run(() -> {
            LineNumberReader reader = null;
            try {
                if (!onPrintOut.isEmpty()) {
                    reader = new LineNumberReader(new InputStreamReader(tmp.getInputStream(), StandardCharsets.UTF_8));
                }

                while (tmp.isAlive()) {
                    Thread.sleep(daemonPeriod);
                    try {
                        if (reader != null) {
                            String line;
                            while (tmp.isAlive() && (line = reader.readLine()) != null) {
                                raiseEvent(onPrintOut, new PrintOutEventArgs(reader.getLineNumber(), line));
                            }
                        }
                    } catch (Throwable e) {
                        TraceHandler.INSTANCE.log("onPrintOut", e);
                    }
                }
            } finally {
                tryClose(reader);
                synchronized (this) {
                    exitValue = tmp.exitValue();
                    log.debug("exit={} {}", exitValue, shell);
                    raiseEvent(onExited, new ExitedEventArgs(exitValue));
                }
            }
        });
        return this;
    }

    @SneakyThrows
    public synchronized int waitFor() {
        if (!isRunning()) {
            if (exitValue == null) {
                throw new InvalidException("Not start");
            }
            return exitValue;
        }
        return process.waitFor();
    }

    @SneakyThrows
    public synchronized boolean waitFor(int timeoutSeconds) {
        if (!isRunning()) {
            if (exitValue == null) {
                throw new InvalidException("Not start");
            }
            return true;
        }
        return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public synchronized void inputKeys(String keys) {
        if (!isRunning()) {
            throw new InvalidException("Not start");
        }

        OutputStream out = process.getOutputStream();
        out.write(keys.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public synchronized void kill() {
        if (!isRunning()) {
            return;
        }

        log.debug("kill {}", shell);
        process.destroyForcibly();
        daemonFuture.cancel(true);
        raiseEvent(onExited, new ExitedEventArgs(exitValue = process.exitValue()));
    }

    public synchronized void restart() {
        kill();
        start();
    }
}
