package org.rx.util.rss;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Only owns its own process and (on Linux) its newly isolated process group. */
@Slf4j
final class LocalDdnsCommand {
    static void run(RssClientConf conf, Map<String, String> environment) throws Exception {
        boolean windows = File.separatorChar == '\\';
        List<String> command = new ArrayList<>();
        if (!windows) {
            File setsid = new File("/usr/bin/setsid");
            if (!setsid.canExecute()) throw new UnsupportedOperationException("isolated process groups unavailable");
            command.add(setsid.getPath()); command.add("--");
        } else if (!modernPidAvailable()) {
            // Java 8 Windows has no supported child PID API. Do not launch an unkillable process tree.
            throw new UnsupportedOperationException("Windows process-tree supervision requires Java 9+");
        }
        command.addAll(Arrays.asList(conf.ddnsLocalCommand.clone()));
        for (String argument : command) if (argument == null || argument.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid local command argument");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        File nullFile = new File(windows ? "NUL" : "/dev/null");
        builder.redirectOutput(nullFile); builder.redirectError(nullFile); builder.redirectInput(nullFile);
        int timeout = Math.max(1, Math.min(60, conf.ddnsLocalCommandTimeoutSeconds));
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                terminate(process, windows);
                throw new java.util.concurrent.TimeoutException("local hook exceeded configured deadline");
            }
            if (process.exitValue() != 0) log.warn("ddns local hook exited code={}", process.exitValue());
        } finally {
            if (process.isAlive()) terminate(process, windows);
        }
    }

    private static boolean modernPidAvailable() {
        try { Process.class.getMethod("pid"); return true; } catch (NoSuchMethodException ignored) { return false; }
    }

    private static long pid(Process process) throws Exception {
        try {
            Method pid = Process.class.getMethod("pid"); return ((Number) pid.invoke(process)).longValue();
        } catch (NoSuchMethodException ignored) {
            Field pid = process.getClass().getDeclaredField("pid"); pid.setAccessible(true); return pid.getLong(process);
        }
    }

    private static void terminate(Process process, boolean windows) throws Exception {
        // Require the original Process handle still alive before using its PID; no saved PID reuse.
        if (!process.isAlive()) return;
        try {
        long pid = pid(process);
        List<String> kill;
        if (windows) kill = Arrays.asList("taskkill.exe", "/PID", Long.toString(pid), "/T", "/F");
        else {
            String stat = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/" + pid + "/stat")), java.nio.charset.StandardCharsets.US_ASCII);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            if (fields.length < 4 || !Long.toString(pid).equals(fields[2]) || !Long.toString(pid).equals(fields[3])) {
                process.destroyForcibly(); throw new IllegalStateException("child process group isolation not confirmed");
            }
            kill = Arrays.asList("/bin/kill", "-KILL", "--", "-" + pid);
        }
        if (!process.isAlive()) return;
        Process killer = new ProcessBuilder(kill).redirectOutput(new File(windows ? "NUL" : "/dev/null")).redirectError(new File(windows ? "NUL" : "/dev/null")).start();
        try { killer.waitFor(3, TimeUnit.SECONDS); }
        finally { if (killer.isAlive()) killer.destroyForcibly(); }
        } finally { if (process.isAlive()) process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
    }
}
