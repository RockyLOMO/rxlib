package org.rx.spring;

import lombok.RequiredArgsConstructor;
import org.rx.bean.RxConfig;
import org.rx.util.BeanMapper;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("apix")
public class ApiController {
    private final RxConfig rxConfig;

    @PostMapping("config")
    public RxConfig config(@RequestBody RxConfig config) {
        return BeanMapper.getInstance().map(config, rxConfig);
    }
}
