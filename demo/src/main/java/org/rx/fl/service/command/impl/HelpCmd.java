package org.rx.fl.service.command.impl;

import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.stereotype.Component;

import static org.rx.common.Contract.require;

//↓
@Component
public class HelpCmd implements Command {
    @Override
    public boolean peek(String message) {
        return true;
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId);

        return HandleResult.ok("一一一一帮 助 信 息一一一一\n" +
                "[1]----------查看个人信息\n" +
                "[2]----------签到随机红包\n" +
                "[3]----------绑定丢失订单\n" +
                "[4]----------提取返利红包\n" +
                "[5]----------查看历史订单\n" +
                "[6]----------邀请关联下级\n" +
                "[7]----------关联上支付宝\n" +
                "[8]----------人工处理问题\n" +
                "亲，请输入[ ]内的数字序号。\n\n" +
                "淘宝返利教程：\nhttp://t.cn/EcS0bfI\n" +
                "京东返利教程：\nhttp://t.cn/EcSjhnd");
    }
}
