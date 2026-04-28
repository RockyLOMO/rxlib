package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.util.function.TripleAction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelegateTest {
    static class Publisher implements EventPublisher<Publisher> {
        final Delegate<Publisher, Args> onChanged = Delegate.create();
    }

    static class Args extends EventArgs {
        final List<String> calls = new ArrayList<String>();
    }

    static class CloseableHandler implements TripleAction<Publisher, Args>, AutoCloseable {
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public void invoke(Publisher publisher, Args args) {
            args.calls.add("closeable");
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    @Test
    void addOrdersAndDeduplicatesHandlers() {
        Publisher publisher = new Publisher();
        Args args = new Args();
        TripleAction<Publisher, Args> same = (s, e) -> e.calls.add("same");

        publisher.onChanged.add((s, e) -> e.calls.add("default"));
        publisher.onChanged.add(Delegate.Order.Last, (s, e) -> e.calls.add("last"));
        publisher.onChanged.add(Delegate.Order.First, (s, e) -> e.calls.add("first"));
        publisher.onChanged.add(same);
        publisher.onChanged.add(Delegate.Order.First, same);

        publisher.publishEvent(publisher.onChanged, args);

        assertEquals("[first, same, default, last]", args.calls.toString());
    }

    @Test
    void purgeCanCloseHandlers() {
        Publisher publisher = new Publisher();
        CloseableHandler handler = new CloseableHandler();

        publisher.onChanged.add(handler);
        publisher.onChanged.purge(true);

        assertEquals(1, handler.closed.get());
        assertEquals(true, publisher.onChanged.isEmpty());
    }
}
