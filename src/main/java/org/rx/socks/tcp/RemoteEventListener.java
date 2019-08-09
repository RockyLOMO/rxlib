package org.rx.socks.tcp;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.rx.beans.Tuple;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

public class RemoteEventListener {
    @Getter
    @AllArgsConstructor
    public static class RaiseEntry {
        private TcpServer server;
        private SessionChannelId clientId;
        private RemotingFactor.RemoteEventFlag eventFlag;
    }

    public static final RemoteEventListener instance = new RemoteEventListener();

    private final Map<Object, Tuple<String, BiConsumer>> attachHost;
    private final Map<Object, RaiseEntry> raiseHost;

    private RemoteEventListener() {
        attachHost = Collections.synchronizedMap(new WeakHashMap<>());
        raiseHost = Collections.synchronizedMap(new WeakHashMap<>());
    }

    public void attach(Object target, String eventName, BiConsumer event) {
        attachHost.put(target, Tuple.of(eventName, event));
    }

    public Tuple<String, BiConsumer> getAttachEntry(Object target) {
        return attachHost.get(target);
    }

    public void raise(Object target, TcpServer server, SessionChannelId clientId, RemotingFactor.RemoteEventFlag eventFlag) {
        raiseHost.put(target, new RaiseEntry(server, clientId, eventFlag));
    }

    public RaiseEntry getRaiseEntry(Object target) {
        return raiseHost.get(target);
    }
}
