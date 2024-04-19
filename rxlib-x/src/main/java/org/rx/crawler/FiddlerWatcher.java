package org.rx.crawler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;

import java.util.List;

public interface FiddlerWatcher extends EventPublisher<FiddlerWatcher> {
    @Getter
    @RequiredArgsConstructor
    class CallbackEventArgs extends EventArgs {
        private final String key;
        private final List<String> content;

        //需要基础类别
        //        public Object state;
        public String state;
    }

    String EVENT_CALLBACK = "onCallback";
}
