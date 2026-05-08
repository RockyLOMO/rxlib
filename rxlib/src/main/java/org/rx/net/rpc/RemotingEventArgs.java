package org.rx.net.rpc;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.NEventArgs;

import java.util.List;

@Getter
@Setter
@ToString
@NoArgsConstructor
public class RemotingEventArgs<T> extends NEventArgs<T> {
    private static final long serialVersionUID = -9109001649701473119L;
    private EventDispatchMode dispatchMode = EventDispatchMode.BROADCAST;
    private List<Integer> broadcastVersions;

    public RemotingEventArgs(T value) {
        super(value);
    }

    public static <T> RemotingEventArgs<T> broadcast(T value) {
        return new RemotingEventArgs<T>(value);
    }

    public static <T> RemotingEventArgs<T> compute(T value) {
        RemotingEventArgs<T> args = new RemotingEventArgs<T>(value);
        args.setDispatchMode(EventDispatchMode.COMPUTE);
        return args;
    }

    public static <T> RemotingEventArgs<T> direct(T value) {
        RemotingEventArgs<T> args = new RemotingEventArgs<T>(value);
        args.setDispatchMode(EventDispatchMode.DIRECT);
        return args;
    }
}
