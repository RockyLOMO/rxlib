package org.rx.fl.service.command;

import org.rx.cache.LRUCache;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.service.UserService;
import org.rx.util.SpringContextUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.rx.common.Contract.require;

@Component
public class CommandManager {
    private static final List<Class> allCmds;
    private static final LRUCache<String, Command> userCmd;

    static {
        allCmds = NQuery.of(App.getClassesFromPackage("org.rx.fl.service.command.impl")).orderBy(p -> {
            Order order = (Order) p.getAnnotation(Order.class);
            return order == null ? 0 : order.value();
        }).toList();
        userCmd = new LRUCache<>(UserService.MaxUserCount, 120, 70 * 1000);
    }

    public String handleMessage(String userId, String message) {
        require(userId, message);

        HandleResult<String> result = null;
        Command cmd = userCmd.get(userId);
        if (cmd != null) {
            result = cmd.handleMessage(userId, message);
        } else {
            for (Class type : allCmds) {
                cmd = (Command) SpringContextUtil.getBean(type);
                if (!cmd.peek(message)) {
                    continue;
                }
                result = cmd.handleMessage(userId, message);
            }
            if (result == null) {
                return "";
            }
        }
        if (result.getNext() != null) {
            userCmd.add(userId, result.getNext());
        } else {
            userCmd.remove(userId);
        }
        return result.getValue();
    }
}
