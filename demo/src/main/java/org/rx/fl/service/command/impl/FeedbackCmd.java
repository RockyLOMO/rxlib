package org.rx.fl.service.command.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.service.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;

@Order(8)
@Component
@Scope("prototype")
@Slf4j
public class FeedbackCmd implements Command {
    @Resource
    private UserService userService;
    @Getter
    @Setter
    private int step = 1;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("反映问题", "4").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        switch (step) {
            case 1:
                this.step = 2;
                return HandleResult.ok("一一一一提 交 问 题一一一一\n" +
                        "亲，当您在使用中遇到问题可使用本命令提交问题，客服会在24小时内为您处理。\n" +
                        "请回复您的问题。\n" +
                        "\n" +
                        "注:本命令提交后需要等客服处理后才能再次提交，请将问题表达清楚，便于客服处理。", this);
            case 2:
                try {
                    String msg = message;
                    if (peek(message)) {
                        msg = msg.substring(4);
                    }
                    userService.feedback(userId, msg);
                    return HandleResult.ok("一一一一提 交 成 功一一一一\n" +
                            "亲，问题提交成功，客服会在24小时内为您处理，处理结果会以消息形式通知。");
                } catch (SystemException e) {
                    log.warn("FeedbackCmd", e);
                    return HandleResult.ok("一一一一提 交 失 败一一一一\n" + e.getFriendlyMessage());
                }
        }
        return HandleResult.fail();
    }
}
