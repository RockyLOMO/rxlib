package org.rx.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.common.Contract.require;

public final class IOStream implements AutoCloseable {
    private static final int   DefaultBufferSize = 1024 * 2;
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
        buffer = new byte[DefaultBufferSize];
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

    public int directData(Predicate<IOStream> checkFunc, Function<Integer, Boolean> eachFunc) {
        try {
            int recv = -1;
            while ((checkFunc == null || checkFunc.test(this))
                    && (recv = inputStream.read(buffer, 0, buffer.length)) >= 0) {
                if (recv == 0) {
                    break;
                }
                outputStream.write(buffer, 0, recv);
                if (eachFunc != null) {
                    if (!eachFunc.apply(recv)) {
                        break;
                    }
                }
            }
            outputStream.flush();
            return recv;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
