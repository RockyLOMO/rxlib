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
import org.rx.core.Linq;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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

    public static Linq<File> listDirectories(String directoryPath, boolean recursive) {
        return CURD_FILE.listDirectories(directoryPath, recursive);
    }

    public static Linq<File> listFiles(String directoryPath, boolean recursive) {
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

//    public static String changeBaseName(@NonNull String filePath, String newName) {
//        get getBaseName (filePath);
//    }

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

    public static void move(String srcPath, String destPath) {
        File src = new File(srcPath), dest = new File(destPath);
        move(src, dest);
    }

    //Don't use File.renameTo()
    @SneakyThrows
    public static void move(File src, File dest) {
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

    public static File moveFile(File src, File dest) throws IOException {
        IOException last = null;
        for (int i = 0; i < 2; i++) {
            try {
                FileUtils.moveFile(src, dest, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return dest;
            } catch (IOException e) {
                String dn = dest.getName();
                String newName = getBaseName(dn) + "_" + i + getExtension(dn);
                dest = new File(dest.getParentFile(), newName);
                last = e;
            }
        }
        throw last;
    }

//    public static void replaceFileAtomically(String originalPath, byte[] newContent) throws IOException {
//        Path original = Paths.get(originalPath);
//
//        // 创建临时文件（同一目录，确保跨设备移动支持）
//        Path temp = Files.createTempFile(original.getParent(), "tmp_", ".tmp");
//        // 写入新内容
//        Files.write(temp, newContent);
//
//        // 原子移动替换（REPLACE_EXISTING 覆盖原文件）
//        Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
//    }

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
    //https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Common_types
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

    public static void writeLines(String filePath, Iterable<CharSequence> lines) {
        writeLines(filePath, lines, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static void writeLines(String filePath, Iterable<CharSequence> lines, Charset charset, StandardOpenOption... options) {
        java.nio.file.Files.write(Paths.get(filePath), lines, charset, options);
    }

    public static void zip(String zipFile, String srcPath) {
        zip(new File(zipFile), null, Collections.singletonList(new File(srcPath)), Collections.emptyList());
    }

    public static void zip(String zipFile, IOStream srcStream) {
        zip(new File(zipFile), null, Collections.emptyList(), Collections.singletonList(srcStream));
    }

    @SneakyThrows
    public static <T extends IOStream> void zip(File zipFile, String password, List<File> srcPaths, List<T> srcStreams) {
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
                for (IOStream srcStream : srcStreams) {
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

    @SneakyThrows
    public static MemoryStream createTextImage(String text, String fontName, int fontSize, float fontOpacity,
                                               int paddingX, int paddingY) {
        // 设置字体
        Font font = new Font(fontName, Font.PLAIN, fontSize);

        // 创建临时图片以测量文本大小
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setFont(font);
        // 计算文字的像素大小
        FontMetrics metrics = g2d.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int textHeight = metrics.getHeight();
        int ascent = metrics.getAscent(); // 上部高度，用于基线定位
        g2d.dispose();

        int width = textWidth + 2 * paddingX; // 总宽度 = 文字宽度 + 左右内边距
        int height = textHeight + 2 * paddingY; // 总高度 = 文字高度 + 上下内边距

        // 创建透明图片
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        g2d = image.createGraphics();
        //设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 显式填充透明背景
        g2d.setColor(new Color(0, 0, 0, 0)); // 透明色
        g2d.fillRect(0, 0, width, height);
        // 设置文字透明度
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fontOpacity));

        // 绘制文本
        g2d.setFont(font);
        g2d.setColor(Color.BLACK);
        int x = (width - textWidth) / 2; // 水平居中
        g2d.drawString(text, x, ascent + paddingY);

        // 保存为 PNG
        MemoryStream s = new MemoryStream();
        ImageIO.write(image, "png", s.getWriter());
        g2d.dispose();
        return s;
    }
}
