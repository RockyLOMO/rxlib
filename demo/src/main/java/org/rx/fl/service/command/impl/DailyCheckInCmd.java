package org.rx.fl.service.command.impl;

import lombok.extern.slf4j.Slf4j;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.service.user.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.dto.repo.UserInfo;
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
    @Resource
    private AliPayCmd aliPayCmd;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("签到", "2").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        String aliCode = String.join("", aliPayCmd.handleMessage(userId, message).getValues());
        try {
            long bonus = userService.checkIn(userId, "0.0.0.0");
            UserInfo user = userService.queryUser(userId);
            return HandleResult.ok(String.format("一一一一签 到 成 功一一一一\n" +
                    "本次签到获得: %.2f元\n" +
                    "        累计签到: %s次\n" +
                    "        累计奖励: %.2f元\n" +
                    "    可提现金额: %.2f元\n" +
                    "%s", toMoney(bonus), user.getCheckInCount(), toMoney(user.getCheckInAmount()), toMoney(user.getBalance()), aliCode));

        } catch (SystemException e) {
            log.warn("DailyCheckInCmd", e);
            return HandleResult.ok(String.format("一一一一签 到 失 败一一一一\n" +
                    "%s\n" +
                    "%s", e.getFriendlyMessage(), aliCode));
        }
    }
}
