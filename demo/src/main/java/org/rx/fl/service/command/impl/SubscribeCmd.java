package org.rx.fl.service.command.impl;

import lombok.extern.slf4j.Slf4j;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.fl.service.DbCache;
import org.rx.fl.service.bot.Bot;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.service.user.UserService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.UUID;

@Order(20)
@Component
@Slf4j
public class SubscribeCmd implements Command {
    @Resource
    private MediaConfig mediaConfig;
    @Resource
    private UserService userService;
    @Resource
    private DbCache dbCache;

    @Override
    public boolean peek(String message) {
        return Bot.SubscribeContent.equals(message) || "我通过了你的朋友验证请求，现在我们可以开始聊天了".equals(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        if (!userService.isNoob(userId)) {
            log.info("not noob and skip");
            //转账之类消息忽略
            return HandleResult.ok("");
        }

        String url = String.format("http://f-li.cn/invite.html?inviteId=%s", App.toShorterUUID(UUID.fromString(userId)));
        return HandleResult.ok(String.format("一一一一系 统 消 息一一一一\n" +
                        "亲，您可算来啦～\n" +
                        "\n" +
                        "点击 %s 绑定伙伴，首单购物成功\n" +
                        "★您将获得【1元】红包，自动转入可提现余额\n" +
                        "\n" +
                        "淘宝返利教程：\n%s\n" +
                        "飞猪返利教程：\n%s\n" +
                        "京东返利教程：\n%s", dbCache.getShortUrl(url),
                mediaConfig.getTaobao().getGuideUrl(), mediaConfig.getTaobao().getFzGuideUrl(),
                mediaConfig.getJd().getGuideUrl()));
    }
}
