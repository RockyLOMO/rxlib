package org.rx.fl.service.command.impl;

import lombok.Getter;
import lombok.Setter;
import org.rx.fl.repository.model.Feedback;
import org.rx.fl.service.dto.FeedbackStatus;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.util.DbUtil;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class FeedbackCmd implements Command {
    @Resource
    private HelpCmd helpCmd;
    @Resource
    private DbUtil dbUtil;
    @Getter
    @Setter
    private int step = 1;

    @Override
    public boolean peek(String message) {
        return message.startsWith("反映问题");
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        switch (step) {
            case 1:
                this.step = 2;
                return HandleResult.of("一一一一提 交 问 题一一一一\n" +
                        "亲，当您在使用中遇到任何问题时可使用本命令提交问题，客服会在24小时内为您处理。\n" +
                        "回复格式:\n" +
                        "反映问题************\n" +
                        "\n" +
                        "注:本命令提交后需要等客服处理后才能再次提交，请将问题表达清楚，便于客服处理。", this);
            case 2:
                String msg = message.substring(4);
                Feedback feedback = new Feedback();
                feedback.setUserId(userId);
                feedback.setContent(msg);
                feedback.setStatus(FeedbackStatus.WaitReply.getValue());
                dbUtil.save(feedback);
                return HandleResult.of("一一一一提 交 问 题一一一一\n" +
                        "亲，问题提交成功，客服会在24小时内为您处理，处理结果会以消息形式通知。");
            case Command.ErrorStep:
                return HandleResult.of("一一一一提 交 问 题一一一一\n" +
                        "亲，您已经提交过问题，请等待问题处理完毕后再提交。");
        }
        return helpCmd.handleMessage(userId, message);
    }
}
