package org.rx.util.rss;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class V2RayUserRule implements Serializable {
    private static final long serialVersionUID = -8357195601839740866L;

    public Boolean enabled;
    // 有序规则，格式："<目标规则> <动作>"，例如 "192.168.31.1 direct"、"geoip:cn proxy"。
    public List<String> rules;
}
