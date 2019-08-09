package org.rx.common;

import lombok.SneakyThrows;
import org.rx.socks.tcp.RemoteEventListener;
import org.rx.socks.tcp.TcpServer;

import java.lang.reflect.Field;
import java.util.function.BiConsumer;

import static org.rx.common.Contract.require;

public interface EventTarget<TSender extends EventTarget<TSender>> {
    @SneakyThrows
    default <TArgs extends EventArgs> void attachEvent(String eventName, BiConsumer<TSender, TArgs> event) {
        require(eventName, event);

        Field field = this.getClass().getField(eventName);
        if (field == null) {
            throw new InvalidOperationException(String.format("Event %s not defined", eventName));
        }
        field.set(this, event);
    }

    @SneakyThrows
    default <TArgs extends EventArgs> void raiseEvent(String eventName, TArgs args) {
        require(eventName);

        Field field = this.getClass().getField(eventName);
        if (field == null) {
            throw new InvalidOperationException(String.format("Event %s not defined", eventName));
        }
        BiConsumer<TSender, TArgs> event = (BiConsumer<TSender, TArgs>) field.get(this);
        raiseEvent(event, args);
    }

    default <TArgs extends EventArgs> void raiseEvent(BiConsumer<TSender, TArgs> event, TArgs args) {
        require(event, args);

        RemoteEventListener.RaiseEntry raiseEntry = RemoteEventListener.instance.getRaiseEntry(this);
        if (raiseEntry != null) {
            TcpServer server = raiseEntry.getServer();
            if (server.isStarted()) {
                server.send(raiseEntry.getClientId(), raiseEntry.getEventFlag());
            }
            return;
        }

        if (event == null) {
            return;
        }
        event.accept((TSender) this, args);
    }
}
