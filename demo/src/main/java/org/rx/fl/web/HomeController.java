package org.rx.fl.web;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.App;
import org.rx.common.MediaConfig;
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
    public String invite(String id, String inviteId, Model model) {
        if (Strings.isNullOrEmpty(id) && Strings.isNullOrEmpty(inviteId)) {
            return "/error";
        }

        if (!Strings.isNullOrEmpty(id)) {
            model.addAttribute("fromUserName", UserApiController.getRenderUserName(userService, App.fromShorterUUID(id).toString()));
            model.addAttribute("code", id);
        } else if (!Strings.isNullOrEmpty(inviteId)) {
            model.addAttribute("toUserName", UserApiController.getRenderUserName(userService, App.fromShorterUUID(inviteId).toString()));
        }
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
