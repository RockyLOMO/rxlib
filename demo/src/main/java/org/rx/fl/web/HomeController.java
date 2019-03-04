package org.rx.fl.web;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.service.BotService;
import org.rx.fl.service.user.UserService;
import org.rx.fl.util.HttpCaller;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

@Controller
@Slf4j
@RequestMapping(method = {RequestMethod.POST, RequestMethod.GET})
public class HomeController {
    @Resource
    private HttpServletRequest request;
    @Resource
    private HttpServletResponse response;
    @Resource
    private BotService botService;
    @Resource
    private UserService userService;
    @Resource
    private MediaConfig mediaConfig;

    @RequestMapping("/index.html")
    public String index(Model model) {
        model.addAttribute("jdGuideUrl", mediaConfig.getJd().getGuideUrl());
        model.addAttribute("tbGuideUrl", mediaConfig.getTaobao().getGuideUrl());
        model.addAttribute("fzGuideUrl", mediaConfig.getTaobao().getFzGuideUrl());
        return "index";
    }

    @RequestMapping("/invite.html")
    public String invite(String id, Model model) {
        if (Strings.isNullOrEmpty(id)) {
            return "/error";
        }
        OpenIdInfo openId = userService.getOpenId(App.fromShorterUUID(id).toString(), BotType.Wx);
        if (Strings.isNullOrEmpty(openId.getNickname())) {
            openId.setNickname("");
        }
        model.addAttribute("fromUserName", String.format("%s %s", openId.getNickname(), openId.getOpenId()));
        model.addAttribute("code", App.toShorterUUID(UUID.randomUUID()));
        return "invite";
    }

    @RequestMapping("/rx.html")
    public String rx(String cookie, Model model) {
        model.addAttribute("name", "王湵范");
        String rawCookie = request.getHeader("Cookie");
        model.addAttribute("cookie", rawCookie);
        if ("1".equals(cookie)) {
            String reqUrl = request.getRequestURL().toString();
            HttpCaller.saveRawCookies(reqUrl, rawCookie);
            log.info("{} save cookie: {}", reqUrl, rawCookie);
        }
        return "rx";
    }

    @RequestMapping("/wx")
    public void wx() {
        botService.getWxBot().handleCallback(request, response);
    }
}
