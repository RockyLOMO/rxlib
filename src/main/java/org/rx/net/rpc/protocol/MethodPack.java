package org.rx.net.rpc.protocol;

import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@RequiredArgsConstructor
public class MethodPack implements Serializable {
    public static final long serialVersionUID = -1424164254938438910L;
    public final UUID id;
    public final String methodName;
    public final Object[] parameters;
    public Object returnValue;
    public String errorMessage;
}
