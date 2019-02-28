package org.rx.fl.web;

import org.rx.beans.DateTime;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.service.MediaService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping(value = "media", method = {RequestMethod.POST})
public class MediaController {
    @Resource
    private MediaService mediaService;

    @RequestMapping("/findAdv")
    public FindAdvResult findAdv(String content) {
        return mediaService.findAdv(content, null);
    }

    @RequestMapping("/findOrders")
    public List<OrderInfo> findOrders(MediaType type, int daysAgo) {
        DateTime now = DateTime.now();
        return mediaService.findOrders(type, now.addDays(-daysAgo), now);
    }
}
