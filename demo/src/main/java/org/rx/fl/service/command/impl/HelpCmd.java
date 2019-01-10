package org.rx.fl.service.command.impl;

import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static org.rx.common.Contract.require;

//↓
@Order(9)
@Component
public class HelpCmd implements Command {
    @Override
    public boolean peek(String message) {
        return true;
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        return HandleResult.of("一一一一帮 助 信 息一一一一\n" +
                "\n" +
                "[个人信息]---查看账户信息\n" +
                "[绑定]----------关联上支付宝\n" +
                "[提现]----------提取返利红包\n" +
                "[反映问题]---人工处理问题\n" +
                "[关联下级]---邀请绑定下线\n" +
                "[查询订单]---查看历史订单\n" +
                "[绑定订单]---绑定丢失订单\n" +
                "[签到]----------签到随机红包\n" +
                "\n" +
                "    亲，请输入[ ]内的文字。");
    }
}
