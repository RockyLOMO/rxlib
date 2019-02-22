package org.rx.fl.service.command.impl;

import org.rx.common.MediaConfig;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;

//↓
@Component
public class HelpCmd implements Command {
    @Resource
    private MediaConfig mediaConfig;

    @Override
    public boolean peek(String message) {
        return true;
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId);

        return HandleResult.ok(String.format("一一一一帮 助 信 息一一一一\n" +
                "[1]----------查看个人信息\n" +
                "[2]----------签到随机红包\n" +
                "[3]----------绑定丢失订单\n" +
                "[4]----------提取返利红包\n" +
                "[5]----------查看历史订单\n" +
                "[6]----------关联上支付宝\n" +
                "[7]----------人工处理问题\n" +
                "亲，请输入[ ]内的数字序号。\n\n" +
                "淘宝返利教程：\n%s\n" +
                "京东返利教程：\n%s", mediaConfig.getTaobao().getGuideUrl(), mediaConfig.getJd().getGuideUrl()));
    }
}
