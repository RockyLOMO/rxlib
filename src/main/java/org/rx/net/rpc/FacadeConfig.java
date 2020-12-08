package org.rx.net.rpc;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Builder
@Data
public class FacadeConfig implements Serializable {
    private static final long serialVersionUID = -8961985437593676759L;
    private final RpcClientConfig clientConfig = new RpcClientConfig();
    private int minPoolSize;
    private int maxPoolSize;
}
