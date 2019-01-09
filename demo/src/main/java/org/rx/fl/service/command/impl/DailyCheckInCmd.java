package org.rx.fl.service.command.impl;

import lombok.extern.slf4j.Slf4j;
import org.rx.common.FlConfig;
import org.rx.common.SystemException;
import org.rx.fl.service.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.dto.repo.UserDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.DbUtil.toMoney;

@Order(6)
@Component
@Slf4j
public class DailyCheckInCmd implements Command {
    @Resource
    private UserService userService;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return message.equals("签到");
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        try {
            long bonus = userService.checkIn(userId, "0.0.0.0");
            UserDto user = userService.queryUser(userId);
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
                    "最高99", toMoney(bonus), user.getCheckInCount(), toMoney(user.getCheckInAmount()), toMoney(user.getBalance()), FlConfig.AliPayCode));

        } catch (SystemException e) {
            log.warn("DailyCheckInCmd", e);
            return HandleResult.of("一一一一签 到 失 败一一一一\n" + e.getFriendlyMessage());
        }
    }
}
