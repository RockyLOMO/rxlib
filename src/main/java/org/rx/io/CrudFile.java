package org.rx.io;

import org.apache.commons.io.FilenameUtils;
import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.io.InputStream;

public interface CrudFile<T> {
    default boolean isDirectoryPath(String path) {
        if (Strings.isEmpty(path)) {
            return false;
        }

        char ch = path.charAt(path.length() - 1);
        return ch == '/' || ch == '\\' || Strings.isEmpty(FilenameUtils.getExtension(path));
    }

    default String getDirectoryPath(String path) {
        return FilenameUtils.getFullPath(path);
    }

    default String padDirectoryPath(String path) {
        if (Strings.isEmpty(path)) {
            return Strings.EMPTY;
        }

        char ch = path.charAt(path.length() - 1);
        if (ch == '/' || ch == '\\') {
            return path;
        }
        char separatorChar = path.lastIndexOf('\\') != -1 ? '\\' : '/';
        return path + separatorChar;
    }

    void createDirectory(String path);

    NQuery<T> listDirectories(String directoryPath, boolean recursive);

    void saveFile(String filePath, InputStream in);

    NQuery<T> listFiles(String directoryPath, boolean recursive);

    boolean exists(String path);

    void delete(String path);
}
