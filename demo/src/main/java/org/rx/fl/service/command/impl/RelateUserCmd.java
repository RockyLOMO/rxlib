package org.rx.fl.service.command.impl;

import org.rx.common.NQuery;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.stereotype.Component;

import static org.rx.common.Contract.require;

@Component
public class RelateUserCmd implements Command {
    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("关联下级", "5").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        return null;
    }
}
