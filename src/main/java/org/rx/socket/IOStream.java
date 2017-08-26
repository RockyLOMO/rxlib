package org.rx.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.rx.common.Contract.require;

public final class IOStream implements AutoCloseable {
    private InputStream  inputStream;
    private OutputStream outputStream;

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public IOStream(InputStream inputStream, OutputStream outputStream) {
        require(inputStream, outputStream);

        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    @Override
    public void close() {
        try {
            inputStream.close();
            outputStream.close();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public int available() {
        try {
            return inputStream.available();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
