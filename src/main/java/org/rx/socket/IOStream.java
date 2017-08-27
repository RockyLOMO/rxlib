package org.rx.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Predicate;

import static org.rx.common.Contract.require;

public final class IOStream implements AutoCloseable {
    private final InputStream  inputStream;
    private final OutputStream outputStream;
    private final byte[]       buffer;

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public IOStream(InputStream inputStream, OutputStream outputStream) {
        require(inputStream, outputStream);

        this.inputStream = inputStream;
        this.outputStream = outputStream;
        buffer = new byte[1024];
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

    public int directDate(Predicate<IOStream> checkFunc) {
        if (checkFunc == null) {
            checkFunc = p -> true;
        }

        try {
            int recv = -1;
            while (checkFunc.test(this) && (recv = inputStream.read(buffer, 0, buffer.length)) > 0) {
                outputStream.write(buffer, 0, recv);
            }
            outputStream.flush();
            return recv;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
