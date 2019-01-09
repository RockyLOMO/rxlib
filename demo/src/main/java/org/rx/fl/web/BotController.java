package org.rx.fl.web;

import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.service.BotService;
import org.rx.fl.service.bot.WxBot;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
//@RequestMapping(value = "bot", method = RequestMethod.POST)
public class BotController {
    @Resource
    private HttpServletRequest request;
    @Resource
    private HttpServletResponse response;
    @Resource
    private BotService botService;

    @RequestMapping("/handleMessage")
    public String handleMessage(@RequestBody MessageInfo msg) {
        return botService.handleMessage(msg);
    }

    @RequestMapping("/wx")
    public void wx() {
        WxBot.Instance.handleCallback(request, response);
    }
}
