package org.rx.net.rpc;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.NEventArgs;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class RemotingEventArgs<T> extends NEventArgs<T> {
    private static final long serialVersionUID = -9109001649701473119L;
    private List<Integer> broadcastVersions;
}
