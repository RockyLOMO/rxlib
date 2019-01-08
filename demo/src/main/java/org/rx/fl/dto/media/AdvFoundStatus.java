package org.rx.fl.dto.media;

import lombok.Getter;
import org.rx.Description;
import org.rx.util.NEnum;

public enum AdvFoundStatus implements NEnum {
    @Description("有推广")
    Ok(1),
    @Description("无连接")
    NoLink(2),
    @Description("无商品")
    NoGoods(3),
    @Description("无推广")
    NoAdv(4);

    @Getter
    private int value;

    AdvFoundStatus(int value) {
        this.value = value;
    }
}
