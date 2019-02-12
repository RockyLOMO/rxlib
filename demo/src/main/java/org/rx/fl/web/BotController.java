package org.rx.fl.web;

import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.service.BotService;
import org.rx.fl.service.bot.WxMobileBot;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequestMapping(method = {RequestMethod.POST, RequestMethod.GET})
public class BotController {
    @Resource
    private HttpServletRequest request;
    @Resource
    private HttpServletResponse response;
    @Resource
    private BotService botService;

    @RequestMapping("/handleMessage")
    public List<String> handleMessage(@RequestBody MessageInfo msg) {
        return botService.handleMessage(msg);
    }

    @RequestMapping("/wxMobileBotAction")
    public void wxMobileBotAction(Integer action) {
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

    @RequestMapping("/wx")
    public void wx() {
        botService.getWxBot().handleCallback(request, response);
    }
}
