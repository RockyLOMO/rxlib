package org.rx.core;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.FileStream;
import org.rx.io.Files;
import org.rx.util.function.TripleAction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.newConcurrentList;

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
                buf.writeCharSequence(String.valueOf(e.lineNumber), StandardCharsets.UTF_8);
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
            cmd.onPrintOut.add(outHandler);
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

    @Getter
    String shell;
    File workspace;
    Process process;
    Future<Void> daemonFuture;
    boolean closeFlag;
    Long processId;
    List<String> processCommand;

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized ShellCommand withCloseFlag() {
        closeFlag = true;
        return this;
    }

    public synchronized ShellCommand withAutoRestart() {
        onExited.add(Delegate.Order.Last, (s, e) -> restart());
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
        if (killOnExited) {
            KILL_LIST.add(this);
        }
    }

    @Override
    protected void dispose() {
        onPrintOut.purge(true);
        kill();
        KILL_LIST.remove(this);
    }

    @SneakyThrows
    public synchronized ShellCommand start() {
        if (isRunning()) {
            throw new InvalidException("Already started");
        }

        log.debug("start {}", shell);
        Long currentJvmPid = currentJvmPid();
        Set<Long> existingChildPids = currentJvmPid == null ? Collections.<Long>emptySet() : new HashSet<>(listDirectChildPids(currentJvmPid));
        List<String> command = buildCommand();
        processCommand = command;
        if (!command.isEmpty() && Files.isPath(command.get(0))) {
            workspace = new File(Files.getFullPathNoEndSeparator(command.get(0)));
        }
        Process tmp = process = new ProcessBuilder(command).directory(workspace).redirectErrorStream(true)  //combine inputStream and errorStream
                .start();
        processId = resolveProcessId(tmp, command, currentJvmPid, existingChildPids);

        daemonFuture = Tasks.run(() -> {
            try (LineNumberReader reader = new LineNumberReader(new InputStreamReader(tmp.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (onPrintOut.isEmpty()) {
                        continue;
                    }
                    try {
                        publishEvent(onPrintOut, new PrintOutEventArgs(reader.getLineNumber(), line));
                    } catch (Throwable e) {
                        log.error("onPrintOut", e);
                    }
                }
            } finally {
                int exitValue = tmp.waitFor();
                log.debug("exit={} {}", exitValue, shell);
                publishEvent(onExited, exitValue);
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
        Long pid = processId;
        if (pid == null && processCommand != null) {
            Long currentJvmPid = currentJvmPid();
            if (currentJvmPid != null) {
                pid = processId = resolveProcessId(process, processCommand, currentJvmPid, Collections.<Long>emptySet());
            }
        }
        boolean killed = pid != null && killProcessTree(pid, new HashSet<Long>());
        if (killed && waitForExit(process, 1000L)) {
            waitReaderExit(2000L);
            return;
        }
        process.destroyForcibly();
        waitForExit(process, 1000L);
        waitReaderExit(2000L);
    }

    public synchronized void restart() {
        kill();
        start();
    }

    List<String> buildCommand() {
        if (!closeFlag) {
            return translateCommandline(shell);
        }

        List<String> command = new ArrayList<>(3);
        if (Sys.IS_OS_WINDOWS) {
            command.add("cmd");
            command.add("/c");
        } else {
            command.add("bash");
            command.add("-c");
        }
        command.add(shell);
        return command;
    }

    @SneakyThrows
    Long resolveProcessId(Process target, List<String> command, Long currentJvmPid, Set<Long> existingChildPids) {
        try {
            return ((Number) Process.class.getMethod("pid").invoke(target)).longValue();
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Number pid = Reflects.readField(target, "pid");
            if (pid != null) {
                return pid.longValue();
            }
        } catch (Throwable ignored) {
        }

        if (currentJvmPid == null) {
            return null;
        }
        if (Sys.IS_OS_WINDOWS) {
            return resolveWindowsProcessId(currentJvmPid, existingChildPids, command);
        }
        return resolveUnixProcessId(currentJvmPid);
    }

    Long resolveWindowsProcessId(long parentPid, Set<Long> existingChildPids, List<String> command) {
        String commandText = String.join(" ", command).toLowerCase();
        Long powershellPid = resolveWindowsProcessIdByPowerShell(parentPid, existingChildPids, commandText);
        if (powershellPid != null) {
            return powershellPid;
        }

        List<String> lines = runCommand(Arrays.asList("wmic", "process", "where", String.format("ParentProcessId=%d", parentPid),
                "get", "ProcessId,CommandLine", "/format:csv"));
        Long fallback = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("Node,") || !line.contains(",")) {
                continue;
            }
            String[] arr = line.split(",", 3);
            if (arr.length < 3) {
                continue;
            }
            Long pid = parseLong(arr[1]);
            if (pid == null) {
                continue;
            }
            if (existingChildPids.contains(pid)) {
                continue;
            }
            String commandLine = arr[2] == null ? "" : arr[2].toLowerCase();
            if (isProcessLookupCommand(commandLine)) {
                continue;
            }
            fallback = pid;
            if (commandLine.contains(commandText)) {
                return pid;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return null;
    }

    Long resolveWindowsProcessIdByPowerShell(long parentPid, Set<Long> existingChildPids, String commandText) {
        List<String> lines = runCommand(Arrays.asList("powershell", "-NoProfile", "-Command",
                String.format("Get-CimInstance Win32_Process -Filter 'ParentProcessId=%d' | Select-Object ProcessId,CommandLine | ConvertTo-Csv -NoTypeInformation", parentPid)));
        Long fallback = null;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("\"ProcessId\"")) {
                continue;
            }
            String[] arr = parseCsvPair(line);
            if (arr == null) {
                continue;
            }
            Long pid = parseLong(arr[0]);
            if (pid == null || existingChildPids.contains(pid)) {
                continue;
            }
            String commandLine = arr[1] == null ? "" : arr[1].toLowerCase();
            if (isProcessLookupCommand(commandLine)) {
                continue;
            }
            fallback = pid;
            if (commandLine.contains(commandText)) {
                return pid;
            }
        }
        return fallback;
    }

    boolean isProcessLookupCommand(String commandLine) {
        return commandLine.contains("get-ciminstance win32_process")
                || commandLine.contains("convertto-csv -notypeinformation")
                || commandLine.contains("wmic process where");
    }

    Long resolveUnixProcessId(long parentPid) {
        List<Long> children = listChildPids(parentPid);
        return children.isEmpty() ? null : children.get(0);
    }

    boolean killProcessTree(long pid, Set<Long> visited) {
        if (!visited.add(pid)) {
            return true;
        }
        try {
            if (Sys.IS_OS_WINDOWS) {
                return runExitCode(Arrays.asList("taskkill", "/T", "/F", "/PID", String.valueOf(pid))) == 0;
            }

            for (Long childPid : listChildPids(pid)) {
                killProcessTree(childPid, visited);
            }
            sendSignal(pid, "-TERM");
            Thread.sleep(200L);
            sendSignal(pid, "-KILL");
            return true;
        } catch (Throwable e) {
            log.warn("killProcessTree {}", pid, e);
            return false;
        }
    }

    List<Long> listChildPids(long parentPid) {
        List<String> lines = runCommand(Arrays.asList("ps", "-o", "pid=", "--ppid", String.valueOf(parentPid)));
        List<Long> pids = new ArrayList<>(lines.size());
        for (String line : lines) {
            Long pid = parseLong(line.trim());
            if (pid != null) {
                pids.add(pid);
            }
        }
        return pids;
    }

    List<Long> listDirectChildPids(long parentPid) {
        if (!Sys.IS_OS_WINDOWS) {
            return listChildPids(parentPid);
        }

        List<String> lines = runCommand(Arrays.asList("wmic", "process", "where", String.format("ParentProcessId=%d", parentPid),
                "get", "ProcessId", "/format:csv"));
        List<Long> pids = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("Node,")) {
                continue;
            }
            int index = line.lastIndexOf(',');
            Long pid = parseLong(index == -1 ? line : line.substring(index + 1));
            if (pid != null) {
                pids.add(pid);
            }
        }
        return pids;
    }

    void sendSignal(long pid, String signal) {
        runExitCode(Arrays.asList("kill", signal, String.valueOf(pid)));
    }

    Long currentJvmPid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int index = runtimeName.indexOf('@');
        if (index <= 0) {
            return null;
        }
        return parseLong(runtimeName.substring(0, index));
    }

    Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    String[] parseCsvPair(String line) {
        if (line.length() < 2 || line.charAt(0) != '"') {
            return null;
        }
        List<String> values = new ArrayList<>(2);
        StringBuilder buf = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    buf.append('"');
                    i++;
                    continue;
                }
                inQuote = !inQuote;
                continue;
            }
            if (ch == ',' && !inQuote) {
                values.add(buf.toString());
                buf.setLength(0);
                continue;
            }
            buf.append(ch);
        }
        values.add(buf.toString());
        return values.size() < 2 ? null : new String[]{values.get(0), values.get(1)};
    }

    List<String> runCommand(List<String> command) {
        List<String> lines = new ArrayList<>();
        Process cmd = null;
        try {
            cmd = new ProcessBuilder(command).redirectErrorStream(true).start();
            readAllLines(cmd.getInputStream(), lines);
            cmd.waitFor(10, TimeUnit.SECONDS);
        } catch (Throwable e) {
            log.debug("runCommand {}", command, e);
        } finally {
            if (cmd != null && cmd.isAlive()) {
                cmd.destroyForcibly();
            }
        }
        return lines;
    }

    int runExitCode(List<String> command) {
        Process cmd = null;
        try {
            cmd = new ProcessBuilder(command).redirectErrorStream(true).start();
            drain(cmd.getInputStream());
            if (!cmd.waitFor(10, TimeUnit.SECONDS)) {
                cmd.destroyForcibly();
                return -1;
            }
            return cmd.exitValue();
        } catch (Throwable e) {
            log.debug("runExitCode {}", command, e);
            return -1;
        } finally {
            if (cmd != null && cmd.isAlive()) {
                cmd.destroyForcibly();
            }
        }
    }

    void readAllLines(InputStream in, List<String> lines) throws IOException {
        try (LineNumberReader reader = new LineNumberReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line.trim());
            }
        }
    }

    void drain(InputStream in) throws IOException {
        byte[] buf = new byte[512];
        while (in.read(buf) != -1) {
            // discard
        }
    }

    @SneakyThrows
    boolean waitForExit(Process target, long timeoutMillis) {
        return !target.isAlive() || target.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    void waitReaderExit(long timeoutMillis) {
        Future<Void> reader = daemonFuture;
        if (reader == null || reader.isDone()) {
            return;
        }
        try {
            reader.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("waitReaderExit timeout {}", shell);
        } catch (Throwable e) {
            log.debug("waitReaderExit {}", shell, e);
        }
    }
}
