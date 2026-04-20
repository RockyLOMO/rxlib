package org.rx.diagnostic;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

final class DiagnosticFileSupport {
    private DiagnosticFileSupport() {
    }

    static boolean hasUsableSpace(File path, long minFreeBytes) {
        if (minFreeBytes <= 0L) {
            return true;
        }
        File dir = writableDirectory(path);
        return dir != null && dir.getUsableSpace() >= minFreeBytes;
    }

    static File writableDirectory(File path) {
        if (path == null) {
            return null;
        }
        File dir = path.isDirectory() ? path : path.getParentFile();
        while (dir != null && !dir.exists()) {
            dir = dir.getParentFile();
        }
        return dir;
    }

    static long h2StorageBytes(File h2File) {
        if (h2File == null) {
            return -1L;
        }
        File abs = h2File.getAbsoluteFile();
        File parent = abs.getParentFile();
        if (parent == null || !parent.exists()) {
            return 0L;
        }
        String baseName = abs.getName();
        File[] files = parent.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File file : files) {
            String name = file.getName();
            if (name.equals(baseName) || name.startsWith(baseName + ".")) {
                total += fileTreeBytes(file, 1024);
            }
        }
        return total;
    }

    static long fileTreeBytes(File root, int maxFiles) {
        Counter counter = new Counter(maxFiles <= 0 ? Integer.MAX_VALUE : maxFiles);
        accumulate(root, counter);
        return counter.bytes;
    }

    static void trimDirectory(File root, long maxBytes, File protectedPath) {
        if (root == null || maxBytes <= 0L || !root.exists()) {
            return;
        }
        File absRoot;
        try {
            absRoot = root.getCanonicalFile();
        } catch (IOException e) {
            return;
        }
        File[] children = absRoot.listFiles();
        if (children == null || children.length == 0) {
            return;
        }
        long used = fileTreeBytes(absRoot, 200000);
        if (used <= maxBytes) {
            return;
        }
        final File protectedCanonical = canonicalOrNull(protectedPath);
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return Long.compare(o1.lastModified(), o2.lastModified());
            }
        });
        for (File child : children) {
            if (used <= maxBytes) {
                return;
            }
            File canonical = canonicalOrNull(child);
            if (canonical == null || isSameOrParent(canonical, protectedCanonical)) {
                continue;
            }
            long childBytes = fileTreeBytes(canonical, 200000);
            if (deleteTree(canonical, absRoot)) {
                used -= childBytes;
            }
        }
    }

    private static void accumulate(File file, Counter counter) {
        if (file == null || !file.exists() || counter.files >= counter.maxFiles) {
            return;
        }
        counter.files++;
        if (file.isFile()) {
            counter.bytes += Math.max(0L, file.length());
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            accumulate(child, counter);
            if (counter.files >= counter.maxFiles) {
                return;
            }
        }
    }

    private static boolean deleteTree(File file, File allowedRoot) {
        try {
            File canonical = file.getCanonicalFile();
            File root = allowedRoot.getCanonicalFile();
            if (!isSameOrChild(root, canonical)) {
                return false;
            }
            if (canonical.isDirectory()) {
                File[] children = canonical.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteTree(child, root);
                    }
                }
            }
            return canonical.delete() || !canonical.exists();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSameOrChild(File root, File child) throws IOException {
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
    }

    private static boolean isSameOrParent(File file, File maybeChild) {
        if (file == null || maybeChild == null) {
            return false;
        }
        try {
            return isSameOrChild(file, maybeChild);
        } catch (IOException e) {
            return false;
        }
    }

    private static File canonicalOrNull(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return null;
        }
    }

    private static final class Counter {
        final int maxFiles;
        int files;
        long bytes;

        Counter(int maxFiles) {
            this.maxFiles = maxFiles;
        }
    }
}

