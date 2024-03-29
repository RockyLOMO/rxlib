package org.rx.net.rpc;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.NEventArgs;

import java.util.List;

@Getter
@Setter
@ToString
public class RemotingEventArgs<T> extends NEventArgs<T> {
    private static final long serialVersionUID = -9109001649701473119L;
    private List<Integer> broadcastVersions;
}
