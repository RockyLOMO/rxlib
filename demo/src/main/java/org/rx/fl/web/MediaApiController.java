package org.rx.fl.web;

import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.service.BotService;
import org.rx.fl.service.MediaService;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.util.validator.EnableValid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import java.util.List;

@EnableValid
@RestController
@RequestMapping(value = "media", method = {RequestMethod.POST})
public class MediaApiController {
    @Resource
    private MediaService mediaService;
    @Resource
    private BotService botService;

    @RequestMapping("/findAdv")
    public FindAdvResult findAdv(@NotNull String content) {
        return mediaService.findAdv(content, null);
    }

    @RequestMapping("/findOrders")
    public List<OrderInfo> findOrders(@NotNull MediaType type, int daysAgo) {
        DateTime now = DateTime.now();
        return mediaService.findOrders(type, now.addDays(-daysAgo), now);
    }

    @RequestMapping("/handleMessage")
    public List<String> handleMessage(@RequestBody MessageInfo message) {
        List<String> messages = botService.handleMessage(message);
        botService.pushMessages(NQuery.of(messages).select(p -> {
            MessageInfo msg = App.deepClone(message);
            msg.setContent(p);
            return msg;
        }).toList());
        return messages;
    }

    @RequestMapping("/wxBotAction")
    public void wxBotAction(Integer action) {
        if (action == null) {
            action = 2;
        }
        WxMobileBot bot = botService.getWxMobileBot();
        if (bot == null) {
            return;
        }

        switch (action) {
            case 2:
                bot.stop();
                break;
            default:
                bot.start();
                break;
        }
    }
}
