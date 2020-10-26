package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.io.IOStream;

import java.io.Serializable;

@Getter
@RequiredArgsConstructor
public class StreamFile implements Serializable {
    private static final long serialVersionUID = 3533819024393438066L;
    private final String name;
    private final IOStream stream;

    public long getLength() {
        return stream != null ? stream.getLength() : 0;
    }
}
