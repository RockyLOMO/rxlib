package org.rx.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandTest {
    @Test
    void fileOutHandlerWritesPlainText() throws Throwable {
        File file = File.createTempFile("shell-command-", ".log");
        try (ShellCommand.FileOutHandler handler = new ShellCommand.FileOutHandler(file.getAbsolutePath())) {
            handler.invoke(null, new ShellCommand.PrintOutEventArgs(7, "hello"));
        }

        try {
            assertEquals("7.\thello\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } finally {
            file.delete();
        }
    }

    @Test
    void withCloseFlagExecutesWholeShellCommand() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch exited = new CountDownLatch(1);
        try (ShellCommand command = new ShellCommand(chainedEchoCommand(), null, Constants.DEFAULT_INTERVAL, false).withCloseFlag()) {
            command.onPrintOut.combine((s, e) -> lines.add(e.getLine().trim()));
            command.onExited.combine((s, e) -> exited.countDown());

            command.start();
            assertEquals(0, command.waitFor());
            assertTrue(exited.await(5, TimeUnit.SECONDS));
        }

        assertTrue(lines.contains("first"));
        assertTrue(lines.contains("second"));
    }

    @Test
    void killWrappedShellStopsWholeProcessTreeAndRaisesExitedOnce() throws Exception {
        AtomicInteger exits = new AtomicInteger();
        CountDownLatch exited = new CountDownLatch(1);
        try (ShellCommand command = new ShellCommand(longRunningShellCommand(), null, Constants.DEFAULT_INTERVAL, false).withCloseFlag()) {
            command.onExited.combine((s, e) -> {
                exits.incrementAndGet();
                exited.countDown();
            });

            command.start();
            Thread.sleep(300);
            command.kill();

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(1, exits.get());
            assertFalse(command.isRunning());
        }
    }

    @Test
    void outputIsDrainedEvenWithoutPrintHandler() {
        try (ShellCommand command = new ShellCommand(spamOutputCommand(), null, Constants.DEFAULT_INTERVAL, false).withCloseFlag()) {
            command.start();
            assertTrue(command.waitFor(10000));
            assertEquals(0, command.exitValue());
        }
    }

    private static String chainedEchoCommand() {
        if (Sys.IS_OS_WINDOWS) {
            return "echo first && echo second";
        }
        return "echo first && echo second";
    }

    private static String longRunningShellCommand() {
        if (Sys.IS_OS_WINDOWS) {
            return "ping -t 127.0.0.1 >nul";
        }
        return "while true; do sleep 1; done";
    }

    private static String spamOutputCommand() {
        if (Sys.IS_OS_WINDOWS) {
            return "for /L %i in (1,1,25000) do @echo 1234567890123456789012345678901234567890";
        }
        return "i=0; while [ $i -lt 25000 ]; do echo 1234567890123456789012345678901234567890; i=$((i+1)); done";
    }
}
