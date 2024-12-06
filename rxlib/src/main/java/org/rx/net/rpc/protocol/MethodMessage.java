package org.rx.net.rpc.protocol;

import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Arrays;

@RequiredArgsConstructor
public class MethodMessage implements Serializable {
    private static final long serialVersionUID = -1424164254938438910L;
    public final int id;
    public final String methodName;
    public final Object[] parameters;
    public final String traceId;
    public Object returnValue;
    public String errorMessage;

    @Override
    public String toString() {
        return "MethodMessage[" + id + "]{" +
                "methodName='" + methodName + '\'' +
                ", parameters=" + Arrays.toString(parameters) +
                ", returnValue='" + returnValue + '\'' +
                '}';
    }
}
