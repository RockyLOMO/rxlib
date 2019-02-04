package org.rx.fl.web;

import lombok.extern.slf4j.Slf4j;
import org.rx.fl.service.media.JdLogin;
import org.rx.fl.util.HttpCaller;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Controller
@Slf4j
public class HomeController {
    @Resource
    private HttpServletRequest request;

    @RequestMapping("/index.html")
    public String index(String cookie, Model model) {
        model.addAttribute("name", "王湵范");
        String rawCookie = request.getHeader("Cookie");
        model.addAttribute("cookie", rawCookie);
        if ("1".equals(cookie)) {
            String reqUrl = request.getRequestURL().toString();
            HttpCaller.saveRawCookies(reqUrl, rawCookie);
            log.info("{} save cookie: {}", reqUrl, rawCookie);
        }
        return "index";
    }

    @RequestMapping("/uc/nplogin")
    public String jdLogin(String test, Model model) throws Exception {
        if ("1".equals(test)) {
            JdLogin login = new JdLogin(8081);
            Thread.sleep(2000);
            log.info("jdLogin test result {}", login.produceKey());
        }
        log.info("jdLogin fake response ok..");
        model.addAttribute("script", "window.close();");
        return "index";
    }
}
