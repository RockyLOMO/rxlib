package org.rx.io;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.Serializable;

import static org.rx.core.Contract.require;

@Getter
@RequiredArgsConstructor
public class StreamFile implements Serializable {
    private static final long serialVersionUID = 3533819024393438066L;

    public static StreamFile wrap(File file) {
        require(file);

        return new StreamFile(file.getName(), new FileStream(file));
    }

    public static StreamFile wrap(String name, byte[] data) {
        require(data);

        return new StreamFile(name, new MemoryStream(data, 0, data.length));
    }

    private final String name;
    private final IOStream stream;
    private long length = -1L;

    public long getLength() {
        return length != -1L ? length : stream.getLength();
    }
}
