package org.rx.fl.service.command.impl;

import lombok.extern.slf4j.Slf4j;
import org.rx.common.SystemException;
import org.rx.fl.service.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.dto.repo.WithdrawResult;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.DbUtil.toMoney;

@Order(5)
@Component
@Scope("prototype")
@Slf4j
public class WithdrawCmd implements Command {
    @Resource
    private BindPaymentCmd bindPaymentCmd;
    @Resource
    private UserService userService;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return message.equals("提现");
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        try {
            WithdrawResult result = userService.withdraw(userId, "0.0.0.0");
            String msg = "一一一一申 请 成 功一一一一\n" +
                    "申请提现金额: %.2f元\n" +
                    "    冻结金额: %.2f元\n";
            if (result.isHasAliPay()) {
                msg += "\n" +
                        "亲，为方便您第一时间收到返现，请您回复如下格式绑定支付宝账号:\n" +
                        "姓名***支付宝*********\n" +
                        "\n" +
                        "如:姓名小范支付宝15888888888\n";
            }
            return HandleResult.of(String.format(msg, toMoney(result.getWithdrawAmount()), toMoney(result.getFreezeAmount())), result.isHasAliPay() ? null : bindPaymentCmd);
        } catch (SystemException e) {
            log.warn("WithdrawCmd", e);
            return HandleResult.of("一一一一申 请 失 败一一一一\n" +
                    "申请提现失败！\n" + e.getFriendlyMessage());
        }
    }
}
