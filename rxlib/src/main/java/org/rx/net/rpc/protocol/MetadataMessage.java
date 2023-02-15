package org.rx.net.rpc.protocol;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class MetadataMessage implements Serializable {
    private static final long serialVersionUID = -3218524051027224831L;
    private int eventVersion;
}
