package org.rx.io;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
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
        //do not use File parent = path.getParentFile();
        Files.createDirectories(new File(FilenameUtils.getFullPath(path)).toPath());
    }

    @SneakyThrows
    @Override
    public NQuery<File> listDirectories(String directoryPath, boolean recursive) {
        File dir = new File(directoryPath);
        if (!recursive) {
            return NQuery.of(Files.newDirectoryStream(dir.toPath(), java.nio.file.Files::isDirectory)).select(Path::toFile);
        }
        //FileUtils.listFiles() 有bug
        return NQuery.of(FileUtils.listFilesAndDirs(dir, FileFilterUtils.falseFileFilter(), FileFilterUtils.directoryFileFilter()));
    }

    @SneakyThrows
    @Override
    public void saveFile(String filePath, InputStream in) {
        //nio
        Files.copy(in, Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public NQuery<File> listFiles(String directoryPath, boolean recursive) {
        IOFileFilter ff = FileFilterUtils.fileFileFilter(), df = recursive ? FileFilterUtils.directoryFileFilter() : FileFilterUtils.falseFileFilter();
        return NQuery.of(FileUtils.listFiles(new File(directoryPath), ff, df));
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
//        java.nio.file.Files.delete(), FileUtils.deleteQuietly();  DirectoryNotEmptyException
        FileUtils.forceDelete(file);
    }
}
