package org.rx.fl.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Controller
public class MediaController {
    @Resource
    private HttpServletRequest request;
    @Resource
    private HttpServletResponse response;

    @RequestMapping("coupon.html")
    public String coupon(Model model) {
        model.addAttribute("k", "fl");
        return "coupon";
    }
}
