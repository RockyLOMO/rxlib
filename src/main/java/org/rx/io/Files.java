package org.rx.io;

import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.StringBuilder;
import org.rx.core.exception.InvalidException;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.rx.core.Contract.require;

public class Files extends FilenameUtils {
    @Getter
    private static final CurdFile<File> curdFile = new LocalCurdFile();

    public static void createDirectory(String path) {
        curdFile.createDirectory(path);
    }

    public static void saveFile(String filePath, InputStream in) {
        curdFile.saveFile(filePath, in);
    }

    public static void delete(String path) {
        curdFile.delete(path);
    }

    public static boolean isDirectory(String path) {
        return curdFile.isDirectory(path);
    }

    public static NQuery<File> listDirectories(String directoryPath, boolean recursive) {
        return curdFile.listDirectories(directoryPath, recursive);
    }

    public static NQuery<File> listFiles(String directoryPath, boolean recursive) {
        return curdFile.listFiles(directoryPath, recursive);
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

    public static String concatPath(String root, String... paths) {
        require(root);

        StringBuilder p = new StringBuilder(curdFile.padDirectoryPath(root));
        if (!Arrays.isEmpty(paths)) {
            int l = paths.length - 1;
            for (int i = 0; i < l; i++) {
                p.append(curdFile.padDirectoryPath(paths[i]));
            }
            p.append(paths[l]);
        }
        return p.toString();
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
        for (File directory : listDirectories(directoryPath, true)) {
            if (isEmptyDirectory(directory.getPath())) {
                directory.delete();
            }
        }
    }
}
