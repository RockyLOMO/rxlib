package org.rx.io;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum FileMode {
    READ_ONLY("r"),
    READ_WRITE("rw"),
    READ_WRITE_AND_SYNC_CONTENT("rwd"),
    READ_WRITE_AND_SYNC_ALL("rws");

    final String value;
}
