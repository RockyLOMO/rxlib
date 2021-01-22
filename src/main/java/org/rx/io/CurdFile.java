package org.rx.io;

import org.rx.core.NQuery;

import java.io.InputStream;

public interface CurdFile<T> {
    void createDirectory(String path);

    void saveFile(String filePath, InputStream in);

    void delete(String path);

    boolean isDirectory(String path);

    boolean exists(String path);

    NQuery<T> listDirectories(String directoryPath, boolean recursive);

    NQuery<T> listFiles(String directoryPath, boolean recursive);
}
