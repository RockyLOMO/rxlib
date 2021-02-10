package org.rx.io;

import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.io.InputStream;

public interface CurdFile<T> {
    void createDirectory(String path);

    void saveFile(String filePath, InputStream in);

    void delete(String path);

    boolean isDirectory(String path);

    boolean exists(String path);

    NQuery<T> listDirectories(String directoryPath, boolean recursive);

    NQuery<T> listFiles(String directoryPath, boolean recursive);

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
}
