package org.rx.io;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.rx.core.NQuery;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

class LocalCurdFile implements CurdFile<File> {
    @SneakyThrows
    @Override
    public void createDirectory(String path) {
        //Do not use FileUtils.createParentDirectories() || File.getParentFile();
        Files.createDirectories(Paths.get(getDirectoryPath(path)));
    }

    @SneakyThrows
    @Override
    public NQuery<File> listDirectories(String directoryPath, boolean recursive) {
        if (!recursive) {
            return NQuery.of(Files.newDirectoryStream(Paths.get(directoryPath), java.nio.file.Files::isDirectory)).select(Path::toFile);
        }
        return NQuery.of(FileUtils.listFilesAndDirs(new File(directoryPath), FileFilterUtils.falseFileFilter(), FileFilterUtils.directoryFileFilter()));
    }

    @SneakyThrows
    @Override
    public void saveFile(String filePath, InputStream in) {
        Files.copy(in, Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public NQuery<File> listFiles(String directoryPath, boolean recursive) {
        IOFileFilter df = recursive ? FileFilterUtils.directoryFileFilter() : FileFilterUtils.falseFileFilter();
        return NQuery.of(FileUtils.listFiles(new File(directoryPath), FileFilterUtils.fileFileFilter(), df));
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    @SneakyThrows
    @Override
    public void delete(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return;
        }
        //java.nio.file.Files.delete() || FileUtils.deleteQuietly() may throw DirectoryNotEmptyException
        FileUtils.forceDelete(file);
    }
}
