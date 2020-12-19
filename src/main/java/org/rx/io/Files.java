package org.rx.io;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.core.exception.InvalidException;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.rx.core.Contract.require;

public class Files extends FilenameUtils {
    public static String concatPath(String root, String... paths) {
        require(root);

        StringBuilder p = new StringBuilder(checkDirectoryPath(root));
        if (!Arrays.isEmpty(paths)) {
            int l = paths.length - 1;
            for (int i = 0; i < l; i++) {
                p.append(checkDirectoryPath(paths[i]));
            }
            p.append(paths[l]);
        }
        return p.toString();
    }

    private static String checkDirectoryPath(String path) {
        if (Strings.isEmpty(path)) {
            return Strings.EMPTY;
        }
        char ch = path.charAt(path.length() - 1);
        if (ch == '/' || ch == '\\') {
            return path;
        }
        return path + File.separatorChar;
    }

    public static boolean isDirectory(String path) {
        if (Strings.isEmpty(path)) {
            throw new IllegalArgumentException("path");
        }

        char ch = path.charAt(path.length() - 1);
        return ch == '/' || ch == '\\' || Strings.isEmpty(getExtension(path));
    }

    public static Path path(String root, String... paths) {
        return Paths.get(root, paths);
    }

    @SneakyThrows
    public static byte[] readAllBytes(Path filePath) {
        return java.nio.file.Files.readAllBytes(filePath);
    }

    @SneakyThrows
    public static List<String> readAllLines(Path filePath) {
        return readAllLines(filePath, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static List<String> readAllLines(Path filePath, Charset charset) {
        return java.nio.file.Files.readAllLines(filePath, charset);
    }

    @SneakyThrows
    public static void createDirectory(String path) {
        java.nio.file.Files.createDirectories(getDirectory(new File(path)).toPath());
    }

    private static File getDirectory(File path) {
        if (path.isDirectory()) {
            return path;
        }
        File parent = path.getParentFile();
        if (parent == null) {
            throw new InvalidException("%s parent path is null", path.getPath());
        }
        return parent;
    }

    @SneakyThrows
    public static boolean isEmptyDirectory(String directoryPath) {
        Path dir = Paths.get(directoryPath);
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new InvalidException("%s is not a directory", directoryPath);
        }
        try (Stream<Path> entries = java.nio.file.Files.list(dir)) {
            return !entries.findFirst().isPresent();
        }
    }

    @SneakyThrows
    public static NQuery<File> listDirectories(String path, boolean recursive) {
        File dir = getDirectory(new File(path));
        if (!recursive) {
            return NQuery.of(NQuery.toList(java.nio.file.Files.newDirectoryStream(dir.toPath(), java.nio.file.Files::isDirectory))).select(Path::toFile);
        }
        //FileUtils.listFiles() 有bug
        return NQuery.of(FileUtils.listFilesAndDirs(dir, FileFilterUtils.falseFileFilter(), FileFilterUtils.directoryFileFilter()));
    }

    public static NQuery<File> listFiles(String path, boolean recursive) {
        File p = getDirectory(new File(path));
        IOFileFilter ff = FileFilterUtils.fileFileFilter(), df = recursive ? FileFilterUtils.directoryFileFilter() : FileFilterUtils.falseFileFilter();
        return NQuery.of(FileUtils.listFiles(p, ff, df));
    }

    @SneakyThrows
    public static void copy(String srcPath, String destPath) {
        File src = new File(srcPath), dest = new File(destPath);
        if (src.isDirectory()) {
            FileUtils.copyDirectoryToDirectory(src, dest);
            return;
        }
        if (dest.isDirectory()) {
            FileUtils.copyFileToDirectory(src, dest);
            return;
        }
        FileUtils.copyFile(src, dest);
    }

    //慎用java的File#renameTo
    @SneakyThrows
    public static void move(String srcPath, String destPath) {
        File src = new File(srcPath), dest = new File(destPath);
        if (src.isDirectory()) {
//            FileUtils.moveDirectoryToDirectory(src, dest, true);
            FileUtils.moveDirectory(src, dest);
            return;
        }
        if (dest.isDirectory()) {
            FileUtils.moveFileToDirectory(src, dest, true);
            return;
        }
        FileUtils.moveFile(src, dest);
    }

    public static void deleteBefore(String directoryPath, Date time) {
        File dir = new File(directoryPath);
        for (File file : FileUtils.listFiles(dir, FileFilterUtils.ageFileFilter(time), FileFilterUtils.directoryFileFilter())) {
            delete(file.getPath());
        }
        if (isEmptyDirectory(directoryPath)) {
            dir.delete();
        }
    }

    public static boolean delete(String path) {
        return delete(path, false);
    }

    @SneakyThrows
    public static boolean delete(String path, boolean force) {
        File p = new File(path);
        if (force) {
            FileUtils.forceDelete(p);
            return true;
        }
//        java.nio.file.Files.delete();  DirectoryNotEmptyException
        return FileUtils.deleteQuietly(p);
    }
}
