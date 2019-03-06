package org.rx.fl.web;

import org.rx.common.UserConfig;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Controller
public class WebErrorController implements ErrorController {
    @Resource
    private UserConfig userConfig;

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        model.addAttribute("title", "404-小范省钱");
        model.addAttribute("intro", userConfig.getIntro());
        Integer statusCode = (Integer) request.getAttribute("javax.servlet.error.status_code");
        if (statusCode == 404) {
            return "404";
        } else {
            return "404";
        }
    }

    @Override
    public String getErrorPath() {
        return "/error";
    }
}
