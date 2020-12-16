package org.rx.net;

import com.jcraft.jsch.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.io.Files;
import org.rx.io.IOStream;

import java.nio.file.Paths;
import java.util.*;

import static org.rx.core.Contract.require;

/**
 * "cd path                       Change remote directory to 'path'\n"+
 * "lcd path                      Change local directory to 'path'\n"+
 * "chgrp grp path                Change group of file 'path' to 'grp'\n"+
 * "chmod mode path               Change permissions of file 'path' to 'mode'\n"+
 * "chown own path                Change owner of file 'path' to 'own'\n"+
 * "df [path]                     Display statistics for current directory or\n"+
 * "                              filesystem containing 'path'\n"+
 * "help                          Display this help text\n"+
 * "get remote-path [local-path]  Download file\n"+
 * "get-resume remote-path [local-path]  Resume to download file.\n"+
 * "get-append remote-path [local-path]  Append remote file to local file\n"+
 * "hardlink oldpath newpath      Hardlink remote file\n"+
 * "*lls [ls-options [path]]      Display local directory listing\n"+
 * "ln oldpath newpath            Symlink remote file\n"+
 * "*lmkdir path                  Create local directory\n"+
 * "lpwd                          Print local working directory\n"+
 * "ls [path]                     Display remote directory listing\n"+
 * "*lumask umask                 Set local umask to 'umask'\n"+
 * "mkdir path                    Create remote directory\n"+
 * "put local-path [remote-path]  Upload file\n"+
 * "put-resume local-path [remote-path]  Resume to upload file\n"+
 * "put-append local-path [remote-path]  Append local file to remote file.\n"+
 * "pwd                           Display remote working directory\n"+
 * "stat path                     Display info about path\n"+
 * "exit                          Quit sftp\n"+
 * "quit                          Quit sftp\n"+
 * "rename oldpath newpath        Rename remote file\n"+
 * "rmdir path                    Remove remote directory\n"+
 * "rm path                       Delete remote file\n"+
 * "symlink oldpath newpath       Symlink remote file\n"+
 * "readlink path                 Check the target of a symbolic link\n"+
 * "realpath path                 Canonicalize the path\n"+
 * "rekey                         Key re-exchanging\n"+
 * "compression level             Packet compression will be enabled\n"+
 * "version                       Show SFTP version\n"+
 * "?                             Synonym for help";
 */
@Slf4j
public class SftpClient extends Disposable {
    @Getter
    @RequiredArgsConstructor
    public static class FileEntry {
        private final String path;
        private final String filename;
        private final String longname;
    }

    private static final List<String> skipDirectories = Arrays.toList(".", "..");

    private final JSch jsch = new JSch();
    private final Session session;
    private final ChannelSftp channel;

    public SftpClient(AuthenticEndpoint endpoint) {
        this(endpoint, App.getConfig().getNetTimeoutMillis());
    }

    @SneakyThrows
    public SftpClient(AuthenticEndpoint endpoint, int timeoutMillis) {
        require(endpoint);

        session = jsch.getSession(endpoint.getUsername(), endpoint.getEndpoint().getHostString(), endpoint.getEndpoint().getPort());
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.setPassword(endpoint.getPassword());
        session.connect(timeoutMillis);
        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(timeoutMillis);
    }

    @Override
    protected void freeObjects() {
        channel.disconnect();
        session.disconnect();
    }

    public void uploadFile(String localPath, String remotePath) {
        uploadFile(IOStream.wrap(localPath), remotePath);
    }

    @SneakyThrows
    public void uploadFile(IOStream<?, ?> stream, String remotePath) {
        require(stream, remotePath);

        if (!Files.isDirectory(remotePath)) {
            remotePath = Files.getFullPath(remotePath);
        }
        try {
            channel.mkdir(remotePath);
        } catch (Exception e) {
            log.warn("mkdir fail, {}", e.getMessage());
        }
        remotePath += stream.getName();

        channel.put(stream.getReader(), remotePath);
    }

    public void downloadFile(String remotePath, String localPath) {
        Files.createDirectory(Paths.get(localPath));

        downloadFile(remotePath, IOStream.wrap(localPath));
    }

    @SneakyThrows
    public void downloadFile(String remotePath, IOStream<?, ?> stream) {
        require(remotePath, stream);

        channel.get(remotePath, stream.getWriter());
    }

    public List<FileEntry> listDirectories(String remotePath, boolean recursive) {
        List<FileEntry> root = new ArrayList<>();
        listDirectories(root, recursive, remotePath);
        return root;
    }

    @SneakyThrows
    private List<ChannelSftp.LsEntry> listDirectories(List<FileEntry> root, boolean recursive, String directory) {
        List<ChannelSftp.LsEntry> list = channel.ls(directory);
        for (ChannelSftp.LsEntry entry : list) {
            if (skipDirectories.contains(entry.getFilename()) || !entry.getAttrs().isDir()) {
                continue;
            }
            root.add(new FileEntry(Files.concatPath(directory, entry.getFilename()), entry.getFilename(), entry.getLongname()));
            if (recursive) {
                listDirectories(root, recursive, Files.concatPath(directory, entry.getFilename()));
            }
        }
        return list;
    }

    public List<FileEntry> listFiles(String remotePath, boolean recursive) {
        List<FileEntry> root = new ArrayList<>();
        listFiles(root, recursive, remotePath);
        return root;
    }

    @SneakyThrows
    private List<ChannelSftp.LsEntry> listFiles(List<FileEntry> root, boolean recursive, String directory) {
        List<ChannelSftp.LsEntry> list = channel.ls(directory);
        for (ChannelSftp.LsEntry entry : list) {
            if (skipDirectories.contains(entry.getFilename())) {
                continue;
            }
            if (entry.getAttrs().isDir()) {
                if (recursive) {
                    listFiles(root, recursive, Files.concatPath(directory, entry.getFilename()));
                }
                continue;
            }
            root.add(new FileEntry(Files.concatPath(directory, entry.getFilename()), entry.getFilename(), entry.getLongname()));
        }
        return list;
    }

    @SneakyThrows
    public void delete(String remotePath) {
        if (Files.isDirectory(remotePath)) {
            for (FileEntry file : listFiles(remotePath, true)) {
                channel.rm(file.getPath());
            }
            channel.rmdir(remotePath);
            return;
        }
        channel.rm(remotePath);
    }
}
