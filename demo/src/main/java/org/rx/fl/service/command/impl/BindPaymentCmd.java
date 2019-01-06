package org.rx.fl.service.command.impl;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.stereotype.Component;

@Component
public class BindPaymentCmd implements Command {
    @Getter
    @Setter
    private int step = 1;

    @Override
    public String getName() {
        return null;
    }

    @Override
    public HandleResult<String> handleMessage(String message, Object argument) {
        return null;
    }
}
