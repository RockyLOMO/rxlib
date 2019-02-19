package org.rx.fl.service.command.impl;

import lombok.Getter;
import lombok.Setter;
import org.rx.common.NQuery;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.rx.common.Contract.require;

@Component
@Scope("prototype")
public class RelateUserCmd implements Command {
    @Getter
    @Setter
    private int step = 1;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("邀请伙伴", "5").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        switch (step) {
            case 1:
                this.step = 2;
                return HandleResult.ok("一一一一邀 请 伙 伴一一一一\n" +
                        "亲，当您在使用中遇到问题可使用本命令提交问题，客服会在24小时内为您处理。\n" +
                        "请回复您的问题。\n" +
                        "\n" +
                        "注:本命令提交后需要等客服处理后才能再次提交，请将问题表达清楚，便于客服处理。", this);
        }
        return HandleResult.fail();
    }
}
