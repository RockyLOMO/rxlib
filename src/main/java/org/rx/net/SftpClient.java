package org.rx.net;

import com.jcraft.jsch.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.core.exception.InvalidException;
import org.rx.io.CurdFile;
import org.rx.io.Files;
import org.rx.io.IOStream;

import java.io.InputStream;
import java.util.*;

import static org.rx.core.Contract.quietly;
import static org.rx.core.Contract.require;

/**
 * "cd remotePath                       Change remote directory to 'remotePath'\n"+
 * "lcd remotePath                      Change local directory to 'remotePath'\n"+
 * "chgrp grp remotePath                Change group of file 'remotePath' to 'grp'\n"+
 * "chmod mode remotePath               Change permissions of file 'remotePath' to 'mode'\n"+
 * "chown own remotePath                Change owner of file 'remotePath' to 'own'\n"+
 * "df [remotePath]                     Display statistics for current directory or\n"+
 * "                              filesystem containing 'remotePath'\n"+
 * "help                          Display this help text\n"+
 * "get remote-remotePath [local-remotePath]  Download file\n"+
 * "get-resume remote-remotePath [local-remotePath]  Resume to download file.\n"+
 * "get-append remote-remotePath [local-remotePath]  Append remote file to local file\n"+
 * "hardlink oldpath newpath      Hardlink remote file\n"+
 * "*lls [ls-options [remotePath]]      Display local directory listing\n"+
 * "ln oldpath newpath            Symlink remote file\n"+
 * "*lmkdir remotePath                  Create local directory\n"+
 * "lpwd                          Print local working directory\n"+
 * "ls [remotePath]                     Display remote directory listing\n"+
 * "*lumask umask                 Set local umask to 'umask'\n"+
 * "mkdir remotePath                    Create remote directory\n"+
 * "put local-remotePath [remote-remotePath]  Upload file\n"+
 * "put-resume local-remotePath [remote-remotePath]  Resume to upload file\n"+
 * "put-append local-remotePath [remote-remotePath]  Append local file to remote file.\n"+
 * "pwd                           Display remote working directory\n"+
 * "stat remotePath                     Display info about remotePath\n"+
 * "exit                          Quit sftp\n"+
 * "quit                          Quit sftp\n"+
 * "rename oldpath newpath        Rename remote file\n"+
 * "rmdir remotePath                    Remove remote directory\n"+
 * "rm remotePath                       Delete remote file\n"+
 * "symlink oldpath newpath       Symlink remote file\n"+
 * "readlink remotePath                 Check the target of a symbolic link\n"+
 * "realpath remotePath                 Canonicalize the remotePath\n"+
 * "rekey                         Key re-exchanging\n"+
 * "compression level             Packet compression will be enabled\n"+
 * "version                       Show SFTP version\n"+
 * "?                             Synonym for help";
 */
@Slf4j
public class SftpClient extends Disposable implements CurdFile<SftpFile> {
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

    @SneakyThrows
    @Override
    public void createDirectory(String remotePath) {
        String dirPath = FilenameUtils.getFullPath(remotePath);
        if (exists(dirPath)) {
            return;
        }
        channel.mkdir(dirPath);
    }

    @Override
    public void saveFile(String remotePath, InputStream in) {
        uploadFile(IOStream.wrap(FilenameUtils.getName(remotePath), in), remotePath);
    }

    @SneakyThrows
    public void delete(String remotePath) {
        if (isDirectory(remotePath)) {
            for (SftpFile file : listFiles(remotePath, true)) {
                channel.rm(file.getPath());
            }
            channel.rmdir(remotePath);
            return;
        }
        channel.rm(remotePath);
    }

    @Override
    public boolean isDirectory(String remotePath) {
        if (Strings.isEmpty(remotePath)) {
            throw new IllegalArgumentException("remotePath");
        }

        char ch = remotePath.charAt(remotePath.length() - 1);
        return ch == '/' || Strings.isEmpty(FilenameUtils.getExtension(remotePath));
    }

    @Override
    public boolean exists(String path) {
        try {
            channel.stat(path);
            return true;
        } catch (SftpException e) {
            log.warn("exists", e);
        }
        return false;
    }

    @Override
    public NQuery<SftpFile> listDirectories(String remotePath, boolean recursive) {
        List<SftpFile> root = new ArrayList<>();
        listDirectories(root, recursive, remotePath);
        return NQuery.of(root);
    }

    @SneakyThrows
    private void listDirectories(List<SftpFile> result, boolean recursive, String directory) {
        List<ChannelSftp.LsEntry> list = channel.ls(directory);
        for (ChannelSftp.LsEntry entry : list) {
            if (skipDirectories.contains(entry.getFilename()) || !entry.getAttrs().isDir()) {
                continue;
            }
            result.add(new SftpFile(Files.concatPath(directory, entry.getFilename()), entry.getFilename(), entry.getLongname()));
            if (recursive) {
                listDirectories(result, recursive, Files.concatPath(directory, entry.getFilename()));
            }
        }
    }

    @Override
    public NQuery<SftpFile> listFiles(String remotePath, boolean recursive) {
        List<SftpFile> root = new ArrayList<>();
        listFiles(root, recursive, remotePath);
        return NQuery.of(root);
    }

    @SneakyThrows
    private void listFiles(List<SftpFile> result, boolean recursive, String directory) {
        List<ChannelSftp.LsEntry> list = channel.ls(directory);
        for (ChannelSftp.LsEntry entry : list) {
            if (skipDirectories.contains(entry.getFilename())) {
                continue;
            }
            if (entry.getAttrs().isDir()) {
                if (recursive) {
                    quietly(() -> listFiles(result, recursive, Files.concatPath(directory, entry.getFilename())));
                }
                continue;
            }
            result.add(new SftpFile(Files.concatPath(directory, entry.getFilename()), entry.getFilename(), entry.getLongname()));
        }
    }

    public void uploadFile(String localPath, String remotePath) {
        uploadFile(IOStream.wrap(localPath), remotePath);
    }

    @SneakyThrows
    public void uploadFile(IOStream<?, ?> stream, String remotePath) {
        require(stream, remotePath);

        if (Files.isDirectory(remotePath)) {
            if (Strings.isEmpty(stream.getName())) {
                throw new InvalidException("stream name is empty");
            }
            remotePath = padDirectoryPath(remotePath) + stream.getName();
        }

        createDirectory(remotePath);
        channel.put(stream.getReader(), remotePath);
    }

    public void downloadFile(String remotePath, String localPath) {
        Files.createDirectory(localPath);

        downloadFile(remotePath, IOStream.wrap(localPath));
    }

    @SneakyThrows
    public void downloadFile(String remotePath, IOStream<?, ?> stream) {
        require(remotePath, stream);

        channel.get(remotePath, stream.getWriter());
    }
}
