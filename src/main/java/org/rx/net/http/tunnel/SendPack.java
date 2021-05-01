package org.rx.net.http.tunnel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
public class SendPack {
    private final String appName;
    private final String socksId;
    private final InetSocketAddress remoteEndpoint;
    @Setter
    private MultipartFile binary;
}
