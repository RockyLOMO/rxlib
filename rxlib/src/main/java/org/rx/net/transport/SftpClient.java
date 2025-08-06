package org.rx.net.transport;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.io.CrudFile;
import org.rx.io.Files;
import org.rx.io.IOStream;
import org.rx.net.AuthenticEndpoint;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SftpClient extends Disposable implements CrudFile<SftpFile> {
    static final List<String> skipDirectories = Arrays.toList(".", "..");

    final SshClient client = SshClient.setUpDefaultClient();
    final ClientSession session;
    final org.apache.sshd.sftp.client.SftpClient channel;

    public SftpClient(AuthenticEndpoint endpoint) {
        this(endpoint, RxConfig.INSTANCE.getNet().getConnectTimeoutMillis());
    }

    @SneakyThrows
    public SftpClient(@NonNull AuthenticEndpoint endpoint, int timeoutMillis) {
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        session = client.connect(endpoint.getUsername(), endpoint.getEndpoint().getHostString(), endpoint.getEndpoint().getPort())
                .verify(timeoutMillis)
                .getSession();
        session.addPasswordIdentity(endpoint.getPassword());
        if (!session.auth().verify(timeoutMillis).isSuccess()) {
            throw new InvalidException("auth failure");
        }
        channel = SftpClientFactory.instance().createSftpClient(session);
    }

    @Override
    protected void dispose() throws Throwable {
        channel.close();
        session.close();
        client.close();
    }

    @Override
    public boolean isDirectoryPath(String remotePath) {
        if (Strings.isEmpty(remotePath)) {
            throw new IllegalArgumentException("remotePath");
        }

        char ch = remotePath.charAt(remotePath.length() - 1);
        return ch == '/' || Strings.isEmpty(Files.getExtension(remotePath));
    }

    @Override
    public boolean exists(String path) {
        try {
            channel.stat(path);
            return true;
        } catch (IOException e) {
            log.warn("exists", e);
        }
        return false;
    }

    @SneakyThrows
    public void delete(String remotePath) {
        if (isDirectoryPath(remotePath)) {
            for (SftpFile file : listFiles(remotePath, true)) {
                channel.remove(file.getPath());
            }
            channel.rmdir(remotePath);
            return;
        }
        channel.remove(remotePath);
    }

    @SneakyThrows
    @Override
    public String createDirectory(String remotePath) {
        String dirPath = getDirectoryPath(remotePath);
        if (!exists(dirPath)) {
            channel.mkdir(dirPath);
        }
        return dirPath;
    }

    @Override
    public void saveFile(String remotePath, InputStream in) {
        try (IOStream stream = IOStream.wrap(Files.getName(remotePath), in)) {
            uploadFile(stream, remotePath);
        }
    }

    @Override
    public Linq<SftpFile> listDirectories(String remotePath, boolean recursive) {
        List<SftpFile> root = new ArrayList<>();
        listDirectories(root, recursive, remotePath);
        return Linq.from(root);
    }

    @SneakyThrows
    private void listDirectories(List<SftpFile> result, boolean recursive, String directory) {
        for (DirEntry entry : channel.readDir(directory)) {
            if (skipDirectories.contains(entry.getFilename()) || !entry.getAttributes().isDirectory()) {
                continue;
            }
            result.add(new SftpFile(Files.concatPath(directory, entry.getFilename()), entry.getFilename(), entry.getLongFilename()));
            if (recursive) {
                listDirectories(result, recursive, Files.concatPath(directory, entry.getFilename()));
            }
        }
    }

    @Override
    public Linq<SftpFile> listFiles(String remotePath, boolean recursive) {
        List<SftpFile> root = new ArrayList<>();
        listFiles(root, recursive, remotePath);
        return Linq.from(root);
    }

    @SneakyThrows
    private void listFiles(List<SftpFile> result, boolean recursive, String directory) {
        for (DirEntry entry : channel.readEntries(directory)) {
            if (skipDirectories.contains(entry.getFilename())) {
                continue;
            }
            if (entry.getAttributes().isDirectory()) {
                if (recursive) {
                    listFiles(result, recursive, Files.concatPath(directory, entry.getFilename()));
                }
                continue;
            }
            result.add(new SftpFile(Files.concatPath(directory, entry.getFilename()), entry.getFilename(), entry.getLongFilename()));
        }
    }

    public void uploadFile(String localPath, String remotePath) {
        uploadFile(IOStream.wrap(localPath), remotePath);
    }

    @SneakyThrows
    public void uploadFile(@NonNull IOStream stream, @NonNull String remotePath) {
        if (Files.isDirectory(remotePath)) {
            if (Strings.isEmpty(stream.getName())) {
                throw new InvalidException("Empty stream name");
            }
            remotePath = padDirectoryPath(remotePath) + stream.getName();
        }

        createDirectory(remotePath);
        stream.read(channel.write(remotePath));
    }

    public void downloadFile(String remotePath, String localPath) {
        Files.createDirectory(localPath);

        downloadFile(remotePath, IOStream.wrap(localPath));
    }

    @SneakyThrows
    public void downloadFile(@NonNull String remotePath, @NonNull IOStream stream) {
        stream.write(channel.read(remotePath));
    }
}

