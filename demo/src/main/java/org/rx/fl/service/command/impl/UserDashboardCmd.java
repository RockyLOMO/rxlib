package org.rx.fl.service.command.impl;

import org.rx.common.NQuery;
import org.rx.fl.service.user.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.dto.repo.UserInfo;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.DbUtil.toMoney;

@Order(3)
@Component
public class UserDashboardCmd implements Command {
    @Resource
    private UserService userService;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("个人信息", "1").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        UserInfo user = userService.queryUser(userId);
        return HandleResult.ok(String.format("一一一一个 人 信 息一一一一\n" +
                        "总提现金额: %.2f元\n" +
                        "可提现金额: %.2f元\n" +
                        "    冻结金额: %.2f元\n" +
                        "未收货金额: %.2f元\n" +
                        "总成功订单: %s单\n" +
                        "\n" +
                        "签到次数: %s次\n" +
                        "签到奖励: %.2f元\n" +
                        "    提现中: %.2f元", toMoney(-user.getTotalWithdrawAmount()), toMoney(user.getBalance()),
                toMoney(user.getFreezeAmount()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount(),
                user.getCheckInCount(), toMoney(user.getCheckInAmount()), toMoney(user.getWithdrawingAmount())));
    }
}
