package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import io.netty.util.ReferenceCounted;
import org.rx.core.RxConfig;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DnsResponseCacheEntry implements Serializable {
    private static final long serialVersionUID = -6384440696278742988L;

    static final class RecordEntry implements Serializable {
        private static final long serialVersionUID = -1476093579681822406L;

        final String name;
        final int type;
        final int dnsClass;
        final long ttl;
        final byte[] content;

        RecordEntry(String name, int type, int dnsClass, long ttl, byte[] content) {
            this.name = name;
            this.type = type;
            this.dnsClass = dnsClass;
            this.ttl = ttl;
            this.content = content;
        }

        int sizeInBytes() {
            return 64 + (name == null ? 0 : (name.length() << 1)) + (content == null ? 0 : content.length);
        }
    }

    final long createdMillis;
    final int freshTtlSeconds;
    final int opCode;
    final int responseCode;
    final boolean authoritativeAnswer;
    final boolean truncated;
    final boolean recursionAvailable;
    final boolean recursionDesired;
    final int z;
    final List<RecordEntry> answers;
    final List<RecordEntry> authorities;
    final List<RecordEntry> additionals;

    DnsResponseCacheEntry(long createdMillis, int freshTtlSeconds, DnsResponse response,
                          List<RecordEntry> answers, List<RecordEntry> authorities,
                          List<RecordEntry> additionals) {
        this.createdMillis = createdMillis;
        this.freshTtlSeconds = freshTtlSeconds;
        opCode = response.opCode().byteValue();
        responseCode = response.code().intValue();
        authoritativeAnswer = response.isAuthoritativeAnswer();
        truncated = response.isTruncated();
        recursionAvailable = response.isRecursionAvailable();
        recursionDesired = response.isRecursionDesired();
        z = response.z();
        this.answers = answers;
        this.authorities = authorities;
        this.additionals = additionals;
    }

    static DnsResponseCacheEntry tryCreate(DnsResponse response, int fallbackTtlSeconds) {
        if (response == null || response.isTruncated()
                || (response.code() != DnsResponseCode.NOERROR && response.code() != DnsResponseCode.NXDOMAIN)) {
            return null;
        }

        List<RecordEntry> answers = copySection(response, DnsSection.ANSWER);
        List<RecordEntry> authorities = copySection(response, DnsSection.AUTHORITY);
        List<RecordEntry> additionals = copySection(response, DnsSection.ADDITIONAL);
        if (answers == null || authorities == null || additionals == null) {
            return null;
        }

        int ttlSeconds = minPositiveTtl(answers, authorities, additionals);
        if (ttlSeconds <= 0) {
            ttlSeconds = Math.max(1, fallbackTtlSeconds);
        }
        return new DnsResponseCacheEntry(System.currentTimeMillis(), ttlSeconds, response,
                answers, authorities, additionals);
    }

    static List<RecordEntry> copySection(DnsResponse response, DnsSection section) {
        int count = response.count(section);
        if (count == 0) {
            return Collections.emptyList();
        }

        List<RecordEntry> records = new ArrayList<RecordEntry>(count);
        for (int i = 0; i < count; i++) {
            DnsRecord record = response.recordAt(section, i);
            if (!(record instanceof DnsRawRecord)) {
                return null;
            }

            DnsRawRecord rawRecord = (DnsRawRecord) record;
            ByteBuf content = rawRecord.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bytes);
            records.add(new RecordEntry(rawRecord.name(), rawRecord.type().intValue(), rawRecord.dnsClass(),
                    rawRecord.timeToLive(), bytes));
        }
        return records;
    }

    static int minPositiveTtl(List<RecordEntry> answers, List<RecordEntry> authorities,
                              List<RecordEntry> additionals) {
        long min = Long.MAX_VALUE;
        min = minPositiveTtl(min, answers);
        min = minPositiveTtl(min, authorities);
        min = minPositiveTtl(min, additionals);
        if (min == Long.MAX_VALUE) {
            return 0;
        }
        return min > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) min;
    }

    static long minPositiveTtl(long min, List<RecordEntry> records) {
        for (int i = 0; i < records.size(); i++) {
            long ttl = records.get(i).ttl;
            if (ttl > 0 && ttl < min) {
                min = ttl;
            }
        }
        return min;
    }

    boolean isFresh(long nowMillis) {
        return nowMillis < freshExpireMillis();
    }

    long freshExpireMillis() {
        return createdMillis + ((long) freshTtlSeconds * 1000L);
    }

    boolean isServeExpiredAllowed(RxConfig.DnsCacheConfig config, long nowMillis) {
        if (!config.isServeExpired() || isFresh(nowMillis)) {
            return false;
        }
        int staleSeconds = config.getServeExpiredTtlSeconds();
        return staleSeconds == 0 || nowMillis <= freshExpireMillis() + ((long) staleSeconds * 1000L);
    }

    boolean shouldPrefetch(RxConfig.DnsCacheConfig config, long nowMillis) {
        if (!config.isPrefetch() || !isFresh(nowMillis)) {
            return false;
        }
        long freshMillis = (long) freshTtlSeconds * 1000L;
        long remainingMillis = freshExpireMillis() - nowMillis;
        long thresholdMillis = Math.max(1L, freshMillis * config.getPrefetchThresholdPercent() / 100L);
        return remainingMillis <= thresholdMillis;
    }

    int freshReplyTtl(long nowMillis) {
        long remainingSeconds = (freshExpireMillis() - nowMillis + 999L) / 1000L;
        if (remainingSeconds <= 0) {
            return 1;
        }
        return remainingSeconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remainingSeconds;
    }

    DefaultDnsResponse newResponse(DefaultDnsQuery query, boolean isTcp, boolean stale, int staleReplyTtlSeconds,
                                   long nowMillis) {
        DefaultDnsResponse response = DnsMessageUtil.newResponse(query, isTcp);
        response.setOpCode(DnsOpCode.valueOf(opCode)).setCode(DnsResponseCode.valueOf(responseCode))
                .setAuthoritativeAnswer(authoritativeAnswer)
                .setTruncated(truncated)
                .setRecursionAvailable(recursionAvailable)
                .setRecursionDesired(recursionDesired)
                .setZ(z);

        DefaultDnsQuestion question = query.recordAt(DnsSection.QUESTION);
        if (question instanceof ReferenceCounted) {
            ((ReferenceCounted) question).retain();
        }
        response.addRecord(DnsSection.QUESTION, question);

        int ttl = stale ? staleReplyTtlSeconds : freshReplyTtl(nowMillis);
        addRecords(response, DnsSection.ANSWER, answers, ttl, stale, nowMillis);
        addRecords(response, DnsSection.AUTHORITY, authorities, ttl, stale, nowMillis);
        addRecords(response, DnsSection.ADDITIONAL, additionals, ttl, stale, nowMillis);
        return response;
    }

    void addRecords(DefaultDnsResponse response, DnsSection section, List<RecordEntry> records, int ttl,
                    boolean stale, long nowMillis) {
        for (int i = 0; i < records.size(); i++) {
            RecordEntry record = records.get(i);
            int recordTtl = ttl;
            if (!stale) {
                long elapsedSeconds = Math.max(0L, (nowMillis - createdMillis) / 1000L);
                long remain = record.ttl - elapsedSeconds;
                recordTtl = remain <= 0 ? 1 : remain > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remain;
            }
            response.addRecord(section, new DefaultDnsRawRecord(record.name, DnsRecordType.valueOf(record.type),
                    record.dnsClass, recordTtl, Unpooled.wrappedBuffer(record.content)));
        }
    }

    int sizeInBytes(String key) {
        int bytes = 96 + (key == null ? 0 : (key.length() << 1));
        bytes += recordsSize(answers);
        bytes += recordsSize(authorities);
        bytes += recordsSize(additionals);
        return bytes;
    }

    int recordsSize(List<RecordEntry> records) {
        int bytes = 24;
        for (int i = 0; i < records.size(); i++) {
            bytes += records.get(i).sizeInBytes();
        }
        return bytes;
    }
}
