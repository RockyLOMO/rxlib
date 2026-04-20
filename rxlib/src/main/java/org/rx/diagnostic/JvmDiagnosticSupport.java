package org.rx.diagnostic;

import com.sun.management.HotSpotDiagnosticMXBean;

import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.lang.management.ManagementFactory;

public final class JvmDiagnosticSupport {
    private static final ObjectName DIAGNOSTIC_COMMAND;

    static {
        ObjectName name = null;
        try {
            name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        } catch (Exception ignored) {
        }
        DIAGNOSTIC_COMMAND = name;
    }

    private JvmDiagnosticSupport() {
    }

    public static boolean isDiagnosticCommandAvailable(String operationName) {
        if (DIAGNOSTIC_COMMAND == null) {
            return false;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            if (!server.isRegistered(DIAGNOSTIC_COMMAND)) {
                return false;
            }
            MBeanOperationInfo[] operations = server.getMBeanInfo(DIAGNOSTIC_COMMAND).getOperations();
            for (MBeanOperationInfo operation : operations) {
                if (operationName.equals(operation.getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String invokeDiagnosticCommand(String operationName, String... args) {
        if (!isDiagnosticCommandAvailable(operationName)) {
            return null;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object result = server.invoke(DIAGNOSTIC_COMMAND, operationName,
                    new Object[]{args == null ? new String[0] : args},
                    new String[]{String[].class.getName()});
            return result == null ? null : String.valueOf(result);
        } catch (Throwable e) {
            return null;
        }
    }

    public static String threadPrint() {
        return invokeDiagnosticCommand("threadPrint", "-l");
    }

    public static String classHistogram(boolean allObjects) {
        return allObjects ? invokeDiagnosticCommand("gcClassHistogram", "-all")
                : invokeDiagnosticCommand("gcClassHistogram");
    }

    public static String nativeMemorySummary() {
        return invokeDiagnosticCommand("vmNativeMemory", "summary");
    }

    public static boolean dumpHeap(File file, boolean live) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            bean.dumpHeap(file.getAbsolutePath(), live);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isJfrAvailable() {
        return isDiagnosticCommandAvailable("jfrStart")
                || isDiagnosticCommandAvailable("jfrCheck")
                || isDiagnosticCommandAvailable("jfrDump");
    }

    public static String startJfr(String name, File file, String settings, int durationSeconds) {
        if (!isDiagnosticCommandAvailable("jfrStart")) {
            return null;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return invokeDiagnosticCommand("jfrStart",
                "name=" + name,
                "settings=" + (settings == null || settings.length() == 0 ? "profile" : settings),
                "duration=" + Math.max(1, durationSeconds) + "s",
                "filename=" + file.getAbsolutePath(),
                "disk=true");
    }

    public static boolean shouldTryJfr(String mode) {
        if (mode == null || "off".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("on".equalsIgnoreCase(mode)) {
            return true;
        }
        return isJfrAvailable();
    }
}

