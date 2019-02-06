package org.rx.fl.service.command;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.common.NQuery;
import org.rx.fl.service.command.impl.HelpCmd;
import org.rx.util.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.rx.common.Contract.require;

@Component
@Slf4j
public class CommandManager {
    @Resource
    private HelpCmd helpCmd;
    private final List<Class> allCmds;
    private final Cache<String, Command> userCmd;

    @Autowired
    public CommandManager(MediaConfig mediaConfig) {
        allCmds = NQuery.of(getClassesFromPackage())
                .where(p -> p.getAnnotation(Order.class) != null)
                .orderBy(p -> {
                    Order order = (Order) p.getAnnotation(Order.class);
                    return order.value();
                }).toList();
        log.info("load cmd {}", String.join(",", NQuery.of(allCmds).select(p -> p.getSimpleName())));
        userCmd = CacheBuilder.newBuilder().expireAfterAccess(mediaConfig.getCommandTimeout(), TimeUnit.SECONDS).build();
    }

    private List<Class> getClassesFromPackage() {
        String packName = "org.rx.fl.service.command.impl";
        List<Class> types = App.getClassesFromPackage(packName, this.getClass().getClassLoader());
        if (CollectionUtils.isEmpty(types)) {
            log.info("Use config to get classes from package");
            String commandList = App.readSetting("app.commandList");
            types = NQuery.of(commandList.split(",")).select(p -> (Class) App.loadClass(String.format("%s.%s", packName, p), false)).toList();
        }
        return types;
    }

    public String handleMessage(String userId, String message) {
        require(userId, message);

        HandleResult<String> result = HandleResult.fail();
        Command cmd = userCmd.getIfPresent(userId);
        if (cmd != null) {
            result = cmd.handleMessage(userId, message);
        }
        if (!result.isOk()) {
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
        }
        if (result.getNext() != null) {
            userCmd.put(userId, result.getNext());
        } else {
            userCmd.invalidate(userId);
        }
        if (!result.isOk()) {
            result = helpCmd.handleMessage(userId, message);
        }
        return result.getValue();
    }
}
