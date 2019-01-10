package org.rx.fl.web;

import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.service.MediaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;

@Controller
@RequestMapping(value = "media", method = RequestMethod.POST)
public class MediaController {
    @Resource
    private MediaService mediaService;

    @RequestMapping("findAdv")
    @ResponseBody
    public FindAdvResult findAdv(String content) {
        return mediaService.findAdv(content);
    }

    @RequestMapping("coupon.html")
    public String coupon(Model model) {
        model.addAttribute("k", "fl");
        return "coupon";
    }
}
