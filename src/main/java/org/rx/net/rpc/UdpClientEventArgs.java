package org.rx.net.rpc;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.EventArgs;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Data
@EqualsAndHashCode(callSuper = true)
public class UdpClientEventArgs extends EventArgs {
    private static final long serialVersionUID = -6262226614600511548L;
    final InetSocketAddress remoteAddress;
    final Serializable pack;
}
