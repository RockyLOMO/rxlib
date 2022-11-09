/*
 * Copyright (c) 2017-20xx Andrey D. Shindarev (ashindarev@gmail.com)
 * This program is made available under the terms of the BSD 3-Clause License.
 */
package org.rx.codec;

import lombok.EqualsAndHashCode;
import lombok.Generated;
import lombok.Getter;

import java.util.Objects;
import java.util.Optional;

import static org.rx.codec.CrcModel.crc_general_combine;

/**
 * CRC Implementation
 *
 * @author Andrey D. Shindarev
 * @since 1.0.0
 */
@EqualsAndHashCode
public final class CRC {

    @Getter
    private final CrcModel model;
    @SuppressWarnings("java:S1700")
    long crc;
    @Getter
    private int length;

    /**
     * Construct CRC as copy of CRC
     *
     * @param crc CRC value as base to create current object
     */
    CRC(final CRC crc) {
        Objects.requireNonNull(crc, "CRC::new - crc is null");
        this.model = crc.model;
        this.crc = crc.crc;
        this.length = crc.length;
    }

    /**
     * Construct CRC for defined model with defined crc value and length
     *
     * @param model  CRC model
     * @param crc    crc value
     * @param length length of calculated buffer
     */
    CRC(final CrcModel model, long crc, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("CRC::new - length less than 0");
        }
        this.model = Objects.requireNonNull(model, "CRC::new - model is null");
        this.crc = crc;
        this.length = length;
    }

    /**
     * Construct CRC for defined model
     *
     * @param model CrcModel to use as base for current object
     */
    CRC(final CrcModel model) {
        this(model, model.init, 0);
    }

    /**
     * Copy CRC
     *
     * @return copy of current CRC
     */
    @Generated
    public CRC copy() {
        return new CRC(this);
    }

    /**
     * Get current crc value
     *
     * @return current crc value
     */
    public long getCrc() {
        return this.length == 0 ? model.getInit() : this.crc;
    }

    /**
     * Update CRC by calculating value of buffer
     *
     * @param buff   buffer
     * @param offset start offset on buffer
     * @param len    count of bytes to calculate
     * @return CRC with updated result (same as this)
     */
    public CRC update(byte[] buff, int offset, int len) {
        if (buff != null && len > 0) {
            if (this.length == 0) {
                this.crc = model.init;
            }
            this.crc = model.crcBytewise(this.crc, buff, offset, len);
            this.length += len;
        }
        return this;
    }

    /**
     * Update CRC by calculating buffer from start of it
     *
     * @param buff buffer to calculate
     * @param len  count of bytes to calculate
     * @return CRC with updated result (same as this)
     */
    public CRC update(byte[] buff, int len) {
        return update(buff, 0, len);
    }

    /**
     * Update CRC by calculating buffer from start of it to the end
     *
     * @param buff buffer to calculate
     * @return CRC with updated result (same as this)
     */
    public CRC update(byte[] buff) {
        return update(buff, buff.length);
    }

    /**
     * Combine current crc value with crc value of the nuxt buff
     *
     * @param crc2 crc value of the next buffer
     * @param len2 length of the next buffer
     * @return combined crc value
     */
    public long combine(long crc2, int len2) {
        return crc_general_combine(
                length == 0 ? model.init : getCrc(), crc2, len2,
                model.getWidth(), model.getInit(), model.getPoly(), model.getXorot(), model.isRefot()
        );
    }

    /**
     * Combine current CRC with CRC on next buffer
     *
     * @param crc CRC of the next buffer
     * @return current CRC, combinet with CRC of parameter
     */
    public CRC combine(CRC crc) {
        Optional.ofNullable(crc)
                .filter(c -> c.length > 0)
                .ifPresent(c -> {
                    this.crc = combine(c.getCrc(), c.length);
                    this.length += c.length;
                });
        return this;
    }

    /**
     * String representation of CRC
     *
     * @return String value
     */
    @Override
    public String toString() {
        return this.getModel().toString() + "[crc:" + getCrc() + ",len:" + getLength() + "]";
    }

}
