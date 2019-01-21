package org.rx.fl.web;

import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.service.MediaService;
import org.rx.fl.util.HttpCaller;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Controller
@RequestMapping(value = "media", method = {RequestMethod.POST, RequestMethod.GET})
@Slf4j
public class MediaController {
    @Resource
    private HttpServletRequest request;
    @Resource
    private MediaService mediaService;

    @RequestMapping("findAdv")
    @ResponseBody
    public FindAdvResult findAdv(String content) {
        return mediaService.findAdv(content);
    }

    @RequestMapping("findOrders")
    @ResponseBody
    public List<OrderInfo> findOrders(MediaType type, int days) {
        DateTime now = DateTime.now();
        return mediaService.findOrders(type, now.addDays(-days), now);
    }

    @RequestMapping("index.html")
    public String index(String rx, Model model) {
        model.addAttribute("name", "rx");
        String rawCookie = request.getHeader("Cookie");
        model.addAttribute("rx", rawCookie);
        if ("1".equals(rx)) {
            String reqUrl = request.getRequestURL().toString();
            HttpCaller.saveRawCookies(reqUrl, rawCookie);
            log.info("{} save cookie: {}", reqUrl, rawCookie);
        }
        return "index";
    }
}
