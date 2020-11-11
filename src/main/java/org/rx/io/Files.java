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

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
        return path + '/';
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

    public static Path path(Path root, String... paths) {
        return Paths.get(root.toString(), paths);
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
    public static void createDirectory(Path directory) {
        require(directory);

        java.nio.file.Files.createDirectories(checkDirectory(directory));
    }

    private static Path checkDirectory(Path path) {
        String p = path.toString();
        if (Strings.isEmpty(FilenameUtils.getExtension(p))) {
            return path;
        }
        String parentPath = FilenameUtils.getFullPathNoEndSeparator(p);
        if (Strings.equals(p, parentPath)) {
            return path;
        }
        return path(parentPath);
    }

    @SneakyThrows
    public static NQuery<Path> listDirectories(Path directory, boolean recursive) {
        directory = checkDirectory(directory);
        if (!recursive) {
            return NQuery.of(NQuery.toList(java.nio.file.Files.newDirectoryStream(directory, java.nio.file.Files::isDirectory)));
        }
        //FileUtils.listFiles() 有bug
        return NQuery.of(FileUtils.listFilesAndDirs(directory.toFile(), FileFilterUtils.falseFileFilter(), FileFilterUtils.directoryFileFilter())).select(File::toPath);
    }

    public static NQuery<Path> listFiles(Path directory, boolean recursive) {
        directory = checkDirectory(directory);
        File f = directory.toFile();
        IOFileFilter ff = FileFilterUtils.fileFileFilter(), df = recursive ? FileFilterUtils.directoryFileFilter() : FileFilterUtils.falseFileFilter();
        return NQuery.of(FileUtils.listFiles(f, ff, df)).select(File::toPath);
    }

    @SneakyThrows
    public static boolean delete(Path path) {
        return FileUtils.deleteQuietly(path.toFile());
//        return java.nio.file.Files.deleteIfExists(dirOrFile);  //java.nio.file.directorynotemptyexception
    }
}
