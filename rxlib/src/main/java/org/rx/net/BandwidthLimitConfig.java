package org.rx.net;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * Bidirectional bandwidth limit configuration in KiB/s (1 KiB = 1024 bytes).
 * Zero or unset means unlimited for that direction; negative values are invalid.
 */
@Getter
@Setter
@ToString
public class BandwidthLimitConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final long MAX_KIBPS = Long.MAX_VALUE / 1024L;

    private long uploadKiBps;
    private long downloadKiBps;

    public BandwidthLimitConfig() {
    }

    public BandwidthLimitConfig(long uploadKiBps, long downloadKiBps) {
        this.uploadKiBps = uploadKiBps;
        this.downloadKiBps = downloadKiBps;
    }

    public BandwidthLimitConfig(BandwidthLimitConfig source) {
        if (source != null) {
            this.uploadKiBps = source.uploadKiBps;
            this.downloadKiBps = source.downloadKiBps;
        }
    }

    public static BandwidthLimitConfig unlimited() {
        return new BandwidthLimitConfig(0L, 0L);
    }

    public boolean isValid() {
        return uploadKiBps >= 0L && downloadKiBps >= 0L;
    }

    public void validate() {
        if (uploadKiBps < 0L) {
            throw new IllegalArgumentException("uploadKiBps must be >= 0: " + uploadKiBps);
        }
        if (downloadKiBps < 0L) {
            throw new IllegalArgumentException("downloadKiBps must be >= 0: " + downloadKiBps);
        }
    }

    public boolean isUploadEnabled() {
        return uploadKiBps > 0L;
    }

    public boolean isDownloadEnabled() {
        return downloadKiBps > 0L;
    }

    public boolean isEnabled() {
        return uploadKiBps > 0L || downloadKiBps > 0L;
    }

    public long uploadBytesPerSecond() {
        return kiBpsToBytesPerSecond(uploadKiBps);
    }

    public long downloadBytesPerSecond() {
        return kiBpsToBytesPerSecond(downloadKiBps);
    }

    public BandwidthLimitConfig copy() {
        return new BandwidthLimitConfig(this);
    }

    public static long kiBpsToBytesPerSecond(long kiBps) {
        if (kiBps <= 0L) {
            return 0L;
        }
        return kiBps >= MAX_KIBPS ? Long.MAX_VALUE : kiBps * 1024L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BandwidthLimitConfig)) {
            return false;
        }
        BandwidthLimitConfig that = (BandwidthLimitConfig) o;
        return uploadKiBps == that.uploadKiBps && downloadKiBps == that.downloadKiBps;
    }

    @Override
    public int hashCode() {
        int result = (int) (uploadKiBps ^ (uploadKiBps >>> 32));
        result = 31 * result + (int) (downloadKiBps ^ (downloadKiBps >>> 32));
        return result;
    }
}
