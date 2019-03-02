package org.rx.fl.service.command.impl;

import org.rx.common.NQuery;
import org.rx.fl.service.user.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.dto.repo.UserInfo;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

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
        List<String> contents = new ArrayList<>();
        String[] relationMessage = userService.getRelationMessage(user.getUserId());
        contents.add(String.format("一一一一个 人 信 息一一一一\n" +
                        "总提现金额: %.2f元\n" +
                        "可提现金额: %.2f元\n" +
                        "    冻结金额: %.2f元\n" +
                        "未收货金额: %.2f元\n" +
                        "总成功订单: %s单\n" +
                        "\n" +
                        "签到次数: %s次\n" +
                        "签到奖励: %.2f元\n" +
                        "    提现中: %.2f元\n" +
                        "%s" +
                        "%s",
                toMoney(-user.getTotalWithdrawAmount()), toMoney(user.getBalance()),
                toMoney(user.getFreezeAmount()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount(),
                user.getCheckInCount(), toMoney(user.getCheckInAmount()), toMoney(user.getWithdrawingAmount()), splitText, relationMessage[0]));
        contents.add(relationMessage[1]);
        return HandleResult.ok(contents, null);
    }
}
