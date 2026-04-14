package org.rx.net;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * FEC (Forward Error Correction) 配置
 * 用于游戏加速场景的 UDP 丢包恢复
 */
@Getter
@Setter
public class FecConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 每组数据包数量 (K)，每 K 个数据包生成 1 个冗余包
     */
    private short groupSize = 3;

    /**
     * 不完整组的刷新超时 (ms)，防止游戏数据被延迟
     */
    private short flushTimeoutMs = 10;

    /**
     * 解码端过期组的清理超时 (ms)
     */
    private short staleGroupTimeoutMs = 500;
}
