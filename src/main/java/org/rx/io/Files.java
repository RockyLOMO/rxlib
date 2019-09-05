package org.rx.io;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.rx.core.Contract.require;

public class Files {
    public static Path path(String root, String... paths) {
        return Paths.get(root, paths);
    }

    @SneakyThrows
    public static void createDirectory(Path directory) {
        require(directory);

        java.nio.file.Files.createDirectories(checkDirectory(directory));
    }

    private static Path checkDirectory(Path dirOrFile) {
        String sp = dirOrFile.toString();
        String np = FilenameUtils.getBaseName(sp);
        if (Strings.isEmpty(np) || sp.equals(np)) {
            return dirOrFile;
        }
        return path(np);
    }

    @SneakyThrows
    public static NQuery<Path> listDirectories(Path directory, boolean recursive) {
        directory = checkDirectory(directory);
        if (!recursive) {
            return NQuery.of(NQuery.toList(java.nio.file.Files.newDirectoryStream(directory, java.nio.file.Files::isDirectory)));
        }
        //FileUtils.listFiles() 有bug
        return NQuery.of(FileUtils.listFilesAndDirs(directory.toFile(), FileFilterUtils.falseFileFilter(), FileFilterUtils.directoryFileFilter())).select(p -> p.toPath());
    }

    public static NQuery<Path> listFiles(Path directory, boolean recursive) {
        directory = checkDirectory(directory);
        File f = directory.toFile();
        IOFileFilter ff = FileFilterUtils.fileFileFilter(), df = recursive ? FileFilterUtils.directoryFileFilter() : FileFilterUtils.falseFileFilter();
        return NQuery.of(FileUtils.listFiles(f, ff, df)).select(p -> p.toPath());
    }
}
