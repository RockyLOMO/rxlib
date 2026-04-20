package org.rx.diagnostic;

import org.rx.core.RxConfig.DiagnosticConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class IncidentBundleWriter {
    private final DiagnosticConfig config;
    private final File diagnosticsDirectory;

    public IncidentBundleWriter(File diagnosticsDirectory) {
        this.config = new DiagnosticConfig();
        this.diagnosticsDirectory = diagnosticsDirectory;
        this.config.normalize();
    }

    public IncidentBundleWriter(DiagnosticConfig config) {
        this.config = config == null ? new DiagnosticConfig() : config;
        this.config.normalize();
        this.diagnosticsDirectory = this.config.getDiagnosticsDirectory();
    }

    public File createBundleDir(String incidentId, DiagnosticIncidentType type) {
        cleanup(System.currentTimeMillis(), null);
        if (!DiagnosticFileSupport.hasUsableSpace(diagnosticsDirectory, config.getEvidenceMinFreeBytes())) {
            return null;
        }
        File dir = new File(diagnosticsDirectory, incidentId + "-" + type.name().toLowerCase());
        if (!dir.exists()) {
            dir.mkdirs();
        }
        DiagnosticFileSupport.trimDirectory(diagnosticsDirectory, config.getDiagnosticsMaxBytes(), dir);
        return dir;
    }

    public File writeText(File dir, String name, String text) {
        if (dir == null || text == null || text.length() == 0) {
            return null;
        }
        if (!DiagnosticFileSupport.hasUsableSpace(dir, config.getEvidenceMinFreeBytes())) {
            return null;
        }
        File file = new File(dir, name);
        FileWriter writer = null;
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            writer = new FileWriter(file);
            writer.write(text);
            cleanup(System.currentTimeMillis(), dir);
            return file;
        } catch (IOException e) {
            return null;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void cleanup(long now) {
        cleanup(now, null);
    }

    private void cleanup(long now, File protectedPath) {
        DiagnosticFileSupport.deleteExpiredBundles(diagnosticsDirectory, config.getDiagnosticsTtlMillis(), protectedPath, now);
        DiagnosticFileSupport.trimDirectory(diagnosticsDirectory, config.getDiagnosticsMaxBytes(), protectedPath);
    }
}
