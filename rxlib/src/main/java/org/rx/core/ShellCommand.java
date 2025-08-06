package org.rx.core;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.io.FileStream;
import org.rx.io.Files;
import org.rx.util.function.TripleAction;

import java.io.File;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.newConcurrentList;
import static org.rx.core.Extends.tryClose;

@Slf4j
public class ShellCommand extends Disposable implements EventPublisher<ShellCommand> {
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

    public static class FileOutHandler extends Disposable implements TripleAction<ShellCommand, PrintOutEventArgs> {
        final FileStream fileStream;

        public FileOutHandler(String filePath) {
            Files.createDirectory(filePath);
            fileStream = new FileStream(filePath);
        }

        @Override
        protected void dispose() {
            fileStream.close();
        }

        @Override
        public void invoke(ShellCommand s, PrintOutEventArgs e) throws Throwable {
            ByteBuf buf = Bytes.directBuffer();
            try {
                buf.writeInt(e.lineNumber);
                buf.writeCharSequence(".\t", StandardCharsets.UTF_8);
                buf.writeCharSequence(e.line, StandardCharsets.UTF_8);
                buf.writeCharSequence("\n", StandardCharsets.UTF_8);
                fileStream.write(buf);
            } finally {
                buf.release();
            }
        }
    }

    public static final TripleAction<ShellCommand, PrintOutEventArgs> CONSOLE_OUT_HANDLER = (s, e) -> System.out.print(e.toString());
    static final String LINUX_BASH = "bash -c ", WIN_CMD = "cmd /c ";
    static final List<ShellCommand> KILL_LIST = newConcurrentList(true);

    static {
        Tasks.addShutdownHook(() -> {
            for (ShellCommand executor : KILL_LIST) {
                executor.kill();
            }
        });
    }

    public static int exec(String shell, String workspace) {
        return exec(shell, workspace, Constants.TIMEOUT_INFINITE);
    }

    public static int exec(String shell, String workspace, long timeoutMillis) {
        return exec(shell, workspace, timeoutMillis, CONSOLE_OUT_HANDLER);
    }

    public static int exec(String shell, String workspace, long timeoutMillis, TripleAction<ShellCommand, PrintOutEventArgs> outHandler) {
        try (ShellCommand cmd = new ShellCommand(shell, workspace)) {
            cmd.onPrintOut.combine(outHandler);
            cmd.start();
            if (timeoutMillis == Constants.TIMEOUT_INFINITE) {
                return cmd.waitFor();
            }

            if (!cmd.waitFor(timeoutMillis)) {
                cmd.kill();
            }
            return cmd.exitValue();
        }
    }

    /**
     * Crack a command line.
     *
     * @param toProcess the command line to process
     * @return the command line broken into strings. An empty or null toProcess parameter results in a zero sized array
     */
    static List<String> translateCommandline(final String toProcess) {
        if (toProcess == null || toProcess.isEmpty()) {
            // no command? no string
            return Collections.emptyList();
        }

        // parse with a simple finite state machine

        final int normal = 0;
        final int inQuote = 1;
        final int inDoubleQuote = 2;
        int state = normal;
        final StringTokenizer tok = new StringTokenizer(toProcess, "\"\' ", true);
        final ArrayList<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean lastTokenHasBeenQuoted = false;

        while (tok.hasMoreTokens()) {
            final String nextTok = tok.nextToken();
            switch (state) {
                case inQuote:
                    if ("\'".equals(nextTok)) {
                        lastTokenHasBeenQuoted = true;
                        state = normal;
                    } else {
                        current.append(nextTok);
                    }
                    break;
                case inDoubleQuote:
                    if ("\"".equals(nextTok)) {
                        lastTokenHasBeenQuoted = true;
                        state = normal;
                    } else {
                        current.append(nextTok);
                    }
                    break;
                default:
                    if ("\'".equals(nextTok)) {
                        state = inQuote;
                    } else if ("\"".equals(nextTok)) {
                        state = inDoubleQuote;
                    } else if (" ".equals(nextTok)) {
                        if (lastTokenHasBeenQuoted || current.length() != 0) {
                            list.add(current.toString());
                            current = new StringBuilder();
                        }
                    } else {
                        current.append(nextTok);
                    }
                    lastTokenHasBeenQuoted = false;
                    break;
            }
        }

        if (lastTokenHasBeenQuoted || current.length() != 0) {
            list.add(current.toString());
        }

        if (state == inQuote || state == inDoubleQuote) {
            throw new IllegalArgumentException("Unbalanced quotes in " + toProcess);
        }
        return list;
    }

    public final Delegate<ShellCommand, PrintOutEventArgs> onPrintOut = Delegate.create();
    public final Delegate<ShellCommand, Integer> onExited = Delegate.create();

    final long daemonPeriod;
    @Getter
    String shell;
    File workspace;
    Process process;
    Future<Void> daemonFuture;

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized ShellCommand withCloseFlag() {
        shell = (Sys.IS_OS_WINDOWS ? WIN_CMD : LINUX_BASH) + shell;
        return this;
    }

    public synchronized ShellCommand withAutoRestart() {
        onExited.last((s, e) -> restart());
        return this;
    }

    public ShellCommand(String shell) {
        this(shell, null);
    }

    public ShellCommand(String shell, String workspace) {
        this(shell, workspace, Constants.DEFAULT_INTERVAL, true);
    }

    public ShellCommand(@NonNull String shell, String workspace, long daemonPeriod, boolean killOnExited) {
        this.shell = shell.trim();
        this.workspace = workspace == null ? null : new File(workspace);

        this.daemonPeriod = Math.max(1, daemonPeriod);
        if (killOnExited) {
            KILL_LIST.add(this);
        }
    }

    @Override
    protected void dispose() {
        onPrintOut.close();
        kill();
        KILL_LIST.remove(this);
    }

    @SneakyThrows
    public synchronized ShellCommand start() {
        if (isRunning()) {
            throw new InvalidException("Already started");
        }

        log.debug("start {}", shell);
        List<String> command = translateCommandline(shell);
        if (!command.isEmpty() && Files.isPath(command.get(0))) {
            workspace = new File(Files.getFullPathNoEndSeparator(command.get(0)));
        }
        Process tmp = process = new ProcessBuilder(command).directory(workspace).redirectErrorStream(true)  //combine inputStream and errorStream
                .start();

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
                    try {
                        if (reader != null) {
                            String line;
                            while (
//                                    tmp.isAlive() &&
                                    (line = reader.readLine()) != null) {
                                raiseEvent(onPrintOut, new PrintOutEventArgs(reader.getLineNumber(), line));
                            }
                        }
                    } catch (Throwable e) {
                        TraceHandler.INSTANCE.log("onPrintOut", e);
                    }
                    if (!tmp.isAlive()) {
                        break;
                    }
                    Thread.sleep(daemonPeriod);
                }
            } finally {
                tryClose(reader);
                synchronized (this) {
                    int exitValue = tmp.exitValue();
                    log.debug("exit={} {}", exitValue, shell);
                    raiseEvent(onExited, exitValue);
                }
            }
        });
        return this;
    }

    public synchronized int exitValue() {
        if (process == null) {
            throw new InvalidException("Not start");
        }
        return process.exitValue();
    }

    @SneakyThrows
    public synchronized int waitFor() {
        if (!isRunning()) {
            return exitValue();
        }
        return process.waitFor();
    }

    @SneakyThrows
    public synchronized boolean waitFor(long timeoutMillis) {
        if (!isRunning()) {
            return true;
        }
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
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
        raiseEvent(onExited, process.exitValue());
    }

    public synchronized void restart() {
        kill();
        start();
    }
}
