package org.rx.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class MemoryStream extends IOStream {
    public MemoryStream() {
super.writer =;byte[] data = s.getBytes();
        int i, skip = 0;
        for (i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == line) {
                skip = 1;
                break;
            }
            if (b == line2) {
                skip = 2;
                break;
            }
        }
        if (skip > 0) {
            System.out.println(new String(data, 0, i));
        }
    }
}
