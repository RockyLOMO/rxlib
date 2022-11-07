/*
 * Copyright (c) 2017-20xx Andrey D. Shindarev (ashindarev@gmail.com)
 * This program is made available under the terms of the BSD 3-Clause License.
 */
package org.rx.codec;

import lombok.EqualsAndHashCode;
import lombok.Generated;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

/**
 * CRC algorithm parameters holder
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class CrcDescription {

    @Getter
    protected final int width;
    @Getter
    protected final long poly;
    @Getter
    protected final long init;
    @Getter
    protected final boolean refin;
    @Getter
    protected final boolean refot;
    @Getter
    protected final long xorot;

    @SuppressWarnings("CopyConstructorMissesField")
    public CrcDescription(final CrcDescription model) {
        this(Objects.requireNonNull(model, "CRC_model_d::new - model is null").getWidth(),
                model.getPoly(), model.getInit(), model.isRefin(), model.isRefot(), model.getXorot());
    }

    @Generated
    @Override
    public String toString() {
        return "CRC-" + getWidth() + "/P" + Long.toHexString(poly).toUpperCase()
                + "_I" + (init == 0 ? "0" : Long.toHexString(init).toUpperCase())
                + (refin ? "_RI" : "")
                + (refot ? "_RO" : "")
                + (xorot == 0 ? "" : "_X" + Long.toHexString(xorot).toUpperCase());
    }

    public CRC getCRC() {
        return CrcModel.construct(this).getCRC();
    }

    public CRC getCRC(long crc, int len) {
        return CrcModel.construct(this).getCRC(crc, len);
    }

    @SuppressWarnings({
            // java:S116 Field names should comply with a naming convention
            // Names of original C algorithm methods has been saved
            "java:S116",
            // java:S3077 Non-primitive fields should not be "volatile"
            // table items are not mutable and use of table is controlled in CrcModel carefully
            "java:S3077"
    })
    protected volatile long[] table_byte = null;

}
