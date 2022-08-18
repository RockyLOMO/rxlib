package org.rx.io;

import lombok.NonNull;
import lombok.SneakyThrows;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.springframework.http.MediaType;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

public class Files extends FilenameUtils {
    public static final CrudFile<File> CURD_FILE = new LocalCrudFile();

    public static boolean isDirectory(String path) {
        return CURD_FILE.isDirectoryPath(path);
    }

    public static String createDirectory(String path) {
        return CURD_FILE.createDirectory(path);
    }

    public static void saveFile(String filePath, InputStream in) {
        CURD_FILE.saveFile(filePath, in);
    }

    public static void delete(String path) {
        CURD_FILE.delete(path);
    }

    public static NQuery<File> listDirectories(String directoryPath, boolean recursive) {
        return CURD_FILE.listDirectories(directoryPath, recursive);
    }

    public static NQuery<File> listFiles(String directoryPath, boolean recursive) {
        return CURD_FILE.listFiles(directoryPath, recursive);
    }

    public static boolean isPath(String str) {
        return str != null && (str.startsWith("/") || str.startsWith("\\") || str.startsWith(":\\", 1));
    }

    public static String concatPath(@NonNull String root, String... paths) {
        StringBuilder p = new StringBuilder(CURD_FILE.padDirectoryPath(root));
        if (!Arrays.isEmpty(paths)) {
            int l = paths.length - 1;
            for (int i = 0; i < l; i++) {
                p.append(CURD_FILE.padDirectoryPath(paths[i]));
            }
            p.append(paths[l]);
        }
        return p.toString();
    }

    public static String changeExtension(@NonNull String filePath, String ext) {
        return String.format("%s.%s", removeExtension(filePath), ext);
    }

    @SneakyThrows
    public static boolean isEmptyDirectory(String directoryPath) {
        Path dir = Paths.get(directoryPath);
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new InvalidException("Path {} is not a directory", directoryPath);
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

    //慎用File.renameTo
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
        deleteBefore(directoryPath, time, null);
    }

    public static void deleteBefore(@NonNull String directoryPath, @NonNull Date time, String wildcard) {
        File dir = new File(directoryPath);
        if (!dir.exists()) {
            return;
        }

        IOFileFilter fileFilter = FileFilterUtils.ageFileFilter(time);
        if (wildcard != null) {
            fileFilter = fileFilter.and(new WildcardFileFilter(wildcard));
        }
        for (File file : FileUtils.listFiles(dir, fileFilter, FileFilterUtils.directoryFileFilter())) {
            delete(file.getPath());
        }
        for (File directory : listDirectories(directoryPath, true)) {
            if (isEmptyDirectory(directory.getPath())) {
                directory.delete();
            }
        }
    }

    //MimeTypeUtils
    public static String getMediaTypeFromName(String fileName) {
        String ext = getExtension(fileName);
        if (Strings.isEmpty(ext)) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        if (Strings.equalsIgnoreCase(ext, "png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (Strings.equalsIgnoreCase(ext, "gif")) {
            return MediaType.IMAGE_GIF_VALUE;
        }
        if (Strings.equalsIgnoreCase(ext, "jpg") || Strings.equalsIgnoreCase(ext, "jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (Strings.equalsIgnoreCase(ext, "pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        if (Strings.equalsIgnoreCase(ext, "txt")) {
            return MediaType.TEXT_MARKDOWN_VALUE;
        }
        if (Strings.equalsIgnoreCase(ext, "md")) {
            return MediaType.TEXT_PLAIN_VALUE;
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    @SneakyThrows
    public static Stream<String> readLines(String filePath) {
        return readLines(filePath, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static Stream<String> readLines(String filePath, Charset charset) {
        return java.nio.file.Files.lines(Paths.get(filePath), charset);
    }

    public static void zip(String zipFile, String srcPath) {
        zip(new File(zipFile), null, Collections.singletonList(new File(srcPath)), Collections.emptyList());
    }

    public static void zip(String zipFile, IOStream<?, ?> srcStream) {
        zip(new File(zipFile), null, Collections.emptyList(), Collections.singletonList(srcStream));
    }

    @SneakyThrows
    public static <T extends IOStream<?, ?>> void zip(File zipFile, String password, List<File> srcPaths, List<T> srcStreams) {
        try (ZipFile zip = new ZipFile(zipFile, password == null ? null : password.toCharArray())) {
            ZipParameters zipParameters = new ZipParameters();
            zipParameters.setCompressionLevel(CompressionLevel.HIGHER);
            if (password != null) {
                zipParameters.setEncryptFiles(true);
                zipParameters.setEncryptionMethod(EncryptionMethod.AES);
            }

            if (!CollectionUtils.isEmpty(srcPaths)) {
                for (File srcPath : srcPaths) {
                    if (srcPath.isDirectory()) {
                        zip.addFolder(srcPath, zipParameters);
                    } else {
                        zip.addFile(srcPath, zipParameters);
                    }
                }
            }

            if (!CollectionUtils.isEmpty(srcStreams)) {
                for (IOStream<?, ?> srcStream : srcStreams) {
                    zipParameters.setFileNameInZip(srcStream.getName());
                    zip.addStream(srcStream.getReader(), zipParameters);
                }
            }
        }
    }

    public static void unzip(String zipFile) {
        unzip(zipFile, "./");
    }

    public static void unzip(String zipFile, String destPath) {
        unzip(new File(zipFile), null, destPath);
    }

    @SneakyThrows
    public static void unzip(File zipFile, String password, String destPath) {
        try (ZipFile zip = new ZipFile(zipFile, password == null ? null : password.toCharArray())) {
            zip.extractAll(destPath);
        }
    }
}
