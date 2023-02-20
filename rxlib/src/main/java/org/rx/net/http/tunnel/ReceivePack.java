package org.rx.net.http.tunnel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.io.IOStream;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Getter
public class ReceivePack {
    private final String socksId;
    private final List<IOStream> binaries = new ArrayList<>();
}
