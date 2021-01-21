package org.rx.io;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

class LocalCurdFile implements CurdFile<File> {
    @SneakyThrows
    @Override
    public void createDirectory(String path) {
        //!File parent = path.getParentFile();!
        java.nio.file.Files.createDirectories(new File(FilenameUtils.getFullPath(path)).toPath());
    }

    @Override
    public void saveFile(String filePath, InputStream in) {
        try (FileStream fileStream = new FileStream(filePath)) {
            IOStream.copyTo(in, fileStream.getWriter());
        }
    }

    @SneakyThrows
    @Override
    public void delete(String path) {
        FileUtils.forceDelete(new File(path));
//        java.nio.file.Files.delete();  DirectoryNotEmptyException
//        FileUtils.deleteQuietly(p);
    }

    @Override
    public boolean isDirectory(String path) {
        if (Strings.isEmpty(path)) {
            throw new IllegalArgumentException("path");
        }

        char ch = path.charAt(path.length() - 1);
        return ch == '/' || ch == '\\' || Strings.isEmpty(FilenameUtils.getExtension(path));
    }

    @SneakyThrows
    @Override
    public NQuery<File> listDirectories(String directoryPath, boolean recursive) {
        File dir = new File(directoryPath);
        if (!recursive) {
            return NQuery.of(java.nio.file.Files.newDirectoryStream(dir.toPath(), java.nio.file.Files::isDirectory)).select(Path::toFile);
        }
        //FileUtils.listFiles() 有bug
        return NQuery.of(FileUtils.listFilesAndDirs(dir, FileFilterUtils.falseFileFilter(), FileFilterUtils.directoryFileFilter()));
    }

    @Override
    public NQuery<File> listFiles(String directoryPath, boolean recursive) {
        IOFileFilter ff = FileFilterUtils.fileFileFilter(), df = recursive ? FileFilterUtils.directoryFileFilter() : FileFilterUtils.falseFileFilter();
        return NQuery.of(FileUtils.listFiles(new File(directoryPath), ff, df));
    }
}
