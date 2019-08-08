package org.rx.common;

import com.google.common.eventbus.EventBus;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;

import static org.rx.common.Contract.require;

public class EventArgs implements Serializable {
    public interface Attachable {
        @SneakyThrows
        default <TSender, TArgs extends EventArgs> void attachEvent(String eventName, BiConsumer<TSender, TArgs> event) {
            require(eventName, event);

            Field field = this.getClass().getField(eventName);
            if (field == null) {
                throw new InvalidOperationException(String.format("Event %s not defined", eventName));
            }
            field.set(this, event);
        }
    }

    public static final EventBus bus = new EventBus();
    public static final EventArgs empty = new EventArgs();

    public static <TSender, TArgs extends EventArgs> void raiseEvent(BiConsumer<TSender, TArgs> event, TSender sender, TArgs args) {
        if (event == null) {
            return;
        }
        event.accept(sender, args);
    }

//    @SneakyThrows
//    private static Object getCglibProxyTargetObject(Object proxy) {
//        require(proxy);
//
//        Field field = proxy.getClass().getDeclaredField("CGLIB$CALLBACK_0");
//        field.setAccessible(true);
//        Object dynamicAdvisedInterceptor = field.get(proxy);
//
//        Field advised = dynamicAdvisedInterceptor.getClass().getDeclaredField("advised");
//        advised.setAccessible(true);
//
//        Object target = ((AdvisedSupport) advised.get(dynamicAdvisedInterceptor)).getTargetSource().getTarget();
//
//        return target;
//    }

    @Getter
    @Setter
    private boolean cancel;

    protected EventArgs() {
    }
}
