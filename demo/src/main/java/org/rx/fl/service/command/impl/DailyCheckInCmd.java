package org.rx.fl.service.command.impl;

import lombok.Getter;
import lombok.Setter;
import org.rx.common.FlConfig;
import org.rx.fl.service.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.service.dto.UserInfo;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.fl.util.DbUtil.toMoney;

@Component
public class DailyCheckInCmd implements Command {
    @Resource
    private HelpCmd helpCmd;
    @Resource
    private UserService userService;
    @Getter
    @Setter
    private int step = 1;

    @Override
    public boolean peek(String message) {
        return message.startsWith("签到");
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        switch (step) {
            case 1:
                long bonus = userService.checkIn(userId, "0.0.0.0");
                UserInfo user = userService.queryUser(userId);
                return HandleResult.of(String.format("一一一一签 到 成 功一一一一\n" +
                        "\n" +
                        "本次签到获得: %.2f元\n" +
                        "    累计签到: %s次\n" +
                        "    累计奖励: %.2f元\n" +
                        "  可提现金额: %.2f元\n" +
                        "------------------------------------------\n" +
                        "\n" +
                        "荭苞来啦\n" +
                        "吱富寳搜索：%s\n" +
                        "最高99", toMoney(bonus), user.getTotalCheckInCount(), toMoney(user.getTotalCheckInAmount()), toMoney(user.getBalance()), FlConfig.AliPayCode));
            case Command.ErrorStep:
                return HandleResult.of("一一一一签 到 失 败一一一一\n" +
                        "    今天已经签到过了,请明天再来。");
        }
        return helpCmd.handleMessage(userId, message);
    }
}
