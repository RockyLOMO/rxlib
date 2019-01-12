package org.rx.fl.service.command;

import lombok.extern.slf4j.Slf4j;
import org.rx.cache.LRUCache;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.common.NQuery;
import org.rx.util.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.rx.common.Contract.require;

@Component
@Slf4j
public class CommandManager {
    private final List<Class> allCmds;
    private final LRUCache<String, Command> userCmd;

    @Autowired
    public CommandManager(MediaConfig mediaConfig) {
        allCmds = NQuery.of(App.getClassesFromPackage("org.rx.fl.service.command.impl")).orderBy(p -> {
            Order order = (Order) p.getAnnotation(Order.class);
            return order == null ? 0 : order.value();
        }).toList();
        log.info("load cmd {}", String.join(",", NQuery.of(allCmds).select(p -> p.getSimpleName())));
        userCmd = new LRUCache<>(mediaConfig.getMaxUserCount(), mediaConfig.getCommandTimeout(), 10 * 1000, p -> log.info("Command {} timeout", p));
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
                log.info("Try peek cmd {}", cmd);
                if (!cmd.peek(message)) {
                    continue;
                }
                log.info("Handle cmd {}", cmd);
                result = cmd.handleMessage(userId, message);
                break;
            }
            if (result == null) {
                log.info("Not found cmd");
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
