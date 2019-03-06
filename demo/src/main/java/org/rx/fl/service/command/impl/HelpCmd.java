package org.rx.fl.service.command.impl;

import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.util.ImageUtil;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.util.Arrays;
import java.util.UUID;

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

        String url = String.format("http://f-li.cn/invite.html?inviteId=%s", App.toShorterUUID(UUID.fromString(userId)));
        String imageBase64 = ImageUtil.createQRCodeImageBase64(300, url);
        return HandleResult.ok(Arrays.asList(String.format("一一一一帮 助 信 息一一一一\n" +
                        "[1]----------查看个人信息\n" +
                        "[2]----------签到随机红包\n" +
                        "[3]----------绑定丢失订单\n" +
                        "[4]----------提取返现补贴\n" +
                        "[5]----------查看历史订单\n" +
                        "[6]----------关联上支付宝\n" +
                        "[7]----------人工处理问题\n" +
                        "亲，请输入[ ]内的数字序号。\n" +
                        "\n" +
                        "淘宝返现教程：\n%s\n" +
                        "飞猪返现教程：\n%s\n" +
                        "京东返现教程：\n%s\n" +
                        "%s" +
                        "识别下方↓二维码绑定伙伴，首单购物成功\n" +
                        "★您将获得【1元】红包，自动转入可提现余额",
                mediaConfig.getTaobao().getGuideUrl(), mediaConfig.getTaobao().getFzGuideUrl(),
                mediaConfig.getJd().getGuideUrl(), splitText),
                imageBase64), null);
    }
}
