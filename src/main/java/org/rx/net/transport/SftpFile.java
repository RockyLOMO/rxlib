package org.rx.net.transport;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class SftpFile {
    private final String path;
    private final String name;
    private final String longname;
}
