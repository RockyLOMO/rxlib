package org.rx.fl.service.command;

import org.rx.App;
import org.rx.NQuery;
import org.rx.util.SpringContextUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CommandManager {
    private static final List<Class> allCmds;

    private static final ConcurrentHashMap<String,Command> user

    static {
        allCmds = NQuery.of(App.getClassesFromPackage("org.rx.fl.service.command.impl")).orderBy(p -> {
            Order order = (Order) p.getAnnotation(Order.class);
            return order == null ? 0 : order.value();
        }).toList();
    }

    public String handleMessage(String userId, String message) {
        for (Class type : allCmds) {
            Command cmd = (Command) SpringContextUtil.getBean(type);
            if (!cmd.peek(message)) {
                continue;
            }
            HandleResult<String> result = cmd.handleMessage(userId, message);
        }
    }
}
