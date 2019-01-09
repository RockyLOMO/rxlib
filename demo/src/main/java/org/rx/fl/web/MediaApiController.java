package org.rx.fl.web;

import org.rx.common.App;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.service.MediaService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping(value = "media", method = RequestMethod.POST)
public class MediaApiController {
    @Resource
    private MediaService mediaService;

    @RequestMapping("findAdv")
    public List<FindAdvResult> findAdv(String contentArray) {
        return mediaService.findAdv(App.split(contentArray, ","));
    }
}
