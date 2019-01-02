package org.rx.fl.web;

import org.rx.App;
import org.rx.fl.service.MediaService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequestMapping(value = "media", method = RequestMethod.POST)
public class MediaApiController {
    @Resource
    private HttpServletRequest request;
    @Resource
    private HttpServletResponse response;
    @Resource
    private MediaService mediaService;

    @RequestMapping("findAdv")
    public List<String> findAdv(String sourceArray) {
        return mediaService.findAdv(App.split(sourceArray, ","));
    }
}
