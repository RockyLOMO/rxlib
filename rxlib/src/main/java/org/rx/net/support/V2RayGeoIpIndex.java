package org.rx.net.support;

import org.rx.exception.InvalidException;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class V2RayGeoIpIndex implements Closeable {
    private static final String GEOIP_PREFIX = "geoip:";
    private static final long IPV4_MAX = 0xffffffffL;

    final Map<String, CodeMatcher> codeMatchers;
    final EntryMatcher[] orderedEntries;
    final Ipv4LookupSet ipv4Lookup;
    final Ipv6LookupSet ipv6Lookup;

    V2RayGeoIpIndex(V2RayGeoDataReader.GeoIpListData data) {
        ArrayList<EntryMatcher> ordered = new ArrayList<>(data.entries.size());
        LinkedHashMap<String, ArrayList<EntryMatcher>> byCode = new LinkedHashMap<>();
        ArrayList<Ipv4LookupCandidate> ipv4Candidates = new ArrayList<>(data.entries.size());
        ArrayList<Ipv6LookupCandidate> ipv6Candidates = new ArrayList<>(data.entries.size());
        int candidateId = 0;
        for (V2RayGeoDataReader.GeoIpEntry entry : data.entries) {
            String code = normalizeCode(entry.code, entry.countryCode);
            if (code == null) {
                continue;
            }
            EntryMatcher matcher = buildEntryMatcher(code, entry);
            int order = ordered.size();
            ordered.add(matcher);
            candidateId = addLookupCandidates(matcher, order, candidateId, ipv4Candidates, ipv6Candidates);
            ArrayList<EntryMatcher> list = byCode.get(code);
            if (list == null) {
                list = new ArrayList<>(1);
                byCode.put(code, list);
            }
            list.add(matcher);
        }

        LinkedHashMap<String, CodeMatcher> codes = new LinkedHashMap<>(byCode.size());
        for (Map.Entry<String, ArrayList<EntryMatcher>> e : byCode.entrySet()) {
            ArrayList<EntryMatcher> entries = e.getValue();
            codes.put(e.getKey(), buildCodeMatcher(e.getKey(), entries));
        }
        codeMatchers = Collections.unmodifiableMap(codes);
        orderedEntries = ordered.toArray(new EntryMatcher[ordered.size()]);
        ipv4Lookup = Ipv4LookupSet.build(ipv4Candidates);
        ipv6Lookup = Ipv6LookupSet.build(ipv6Candidates);
    }

    public boolean isEmpty() {
        return codeMatchers.isEmpty();
    }

    public Set<String> codes() {
        return codeMatchers.keySet();
    }

    boolean matches(String code, byte[] ipBytes) {
        if (!isIpBytes(ipBytes)) {
            return false;
        }
        CodeMatcher matcher = matcher(code);
        return matcher != null && matcher.matches(ipBytes);
    }

    CodeMatcher matcher(String code) {
        String normalizedCode = normalizeCode(code, null);
        return normalizedCode == null ? null : codeMatchers.get(normalizedCode);
    }

    String lookupCode(byte[] ipBytes) {
        if (!isIpBytes(ipBytes)) {
            return null;
        }
        return ipBytes.length == 4 ? ipv4Lookup.lookup(ipBytes) : ipv6Lookup.lookup(ipBytes);
    }

    @Override
    public void close() {
    }

    private static EntryMatcher buildEntryMatcher(String code, V2RayGeoDataReader.GeoIpEntry entry) {
        ArrayList<Ipv4Range> ipv4 = new ArrayList<>();
        ArrayList<Ipv6Range> ipv6 = new ArrayList<>();
        for (V2RayGeoDataReader.Cidr cidr : entry.cidrs) {
            if (cidr.ip.length == 4) {
                ipv4.add(toIpv4Range(cidr.ip, cidr.prefix));
            } else if (cidr.ip.length == 16) {
                ipv6.add(toIpv6Range(cidr.ip, cidr.prefix));
            } else {
                throw new InvalidException("v2ray geoip cidr ip length invalid {}", cidr.ip.length);
            }
        }
        return new EntryMatcher(code, entry.inverseMatch, Ipv4RangeSet.build(ipv4), Ipv6RangeSet.build(ipv6));
    }

    private static CodeMatcher buildCodeMatcher(String code, ArrayList<EntryMatcher> entries) {
        int normalCount = 0;
        for (EntryMatcher entry : entries) {
            if (!entry.inverseMatch) {
                normalCount++;
            }
        }
        if (normalCount <= 1) {
            return new CodeMatcher(entries.toArray(new EntryMatcher[entries.size()]));
        }

        ArrayList<Ipv4Range> ipv4 = new ArrayList<>();
        ArrayList<Ipv6Range> ipv6 = new ArrayList<>();
        ArrayList<EntryMatcher> optimized = new ArrayList<>(entries.size() - normalCount + 1);
        for (EntryMatcher entry : entries) {
            if (entry.inverseMatch) {
                optimized.add(entry);
                continue;
            }
            addIpv4Ranges(ipv4, entry.ipv4);
            addIpv6Ranges(ipv6, entry.ipv6);
        }
        optimized.add(0, new EntryMatcher(code, false, Ipv4RangeSet.build(ipv4), Ipv6RangeSet.build(ipv6)));
        return new CodeMatcher(optimized.toArray(new EntryMatcher[optimized.size()]));
    }

    private static void addIpv4Ranges(ArrayList<Ipv4Range> target, Ipv4RangeSet ranges) {
        for (int i = 0; i < ranges.starts.length; i++) {
            target.add(new Ipv4Range(ranges.starts[i], ranges.ends[i]));
        }
    }

    private static void addIpv6Ranges(ArrayList<Ipv6Range> target, Ipv6RangeSet ranges) {
        for (int i = 0; i < ranges.startHighs.length; i++) {
            target.add(new Ipv6Range(ranges.startHighs[i], ranges.startLows[i],
                    ranges.endHighs[i], ranges.endLows[i]));
        }
    }

    private static int addLookupCandidates(EntryMatcher matcher, int order, int candidateId,
            ArrayList<Ipv4LookupCandidate> ipv4Candidates, ArrayList<Ipv6LookupCandidate> ipv6Candidates) {
        if (matcher.inverseMatch) {
            candidateId = addIpv4LookupComplement(matcher.code, order, candidateId, matcher.ipv4, ipv4Candidates);
            return addIpv6LookupComplement(matcher.code, order, candidateId, matcher.ipv6, ipv6Candidates);
        }
        candidateId = addIpv4LookupRanges(matcher.code, order, candidateId, matcher.ipv4, ipv4Candidates);
        return addIpv6LookupRanges(matcher.code, order, candidateId, matcher.ipv6, ipv6Candidates);
    }

    private static int addIpv4LookupRanges(String code, int order, int candidateId, Ipv4RangeSet ranges,
            ArrayList<Ipv4LookupCandidate> candidates) {
        for (int i = 0; i < ranges.starts.length; i++) {
            candidateId = addIpv4LookupRange(code, order, candidateId, ranges.starts[i], ranges.ends[i], candidates);
        }
        return candidateId;
    }

    private static int addIpv4LookupComplement(String code, int order, int candidateId, Ipv4RangeSet ranges,
            ArrayList<Ipv4LookupCandidate> candidates) {
        long start = 0L;
        for (int i = 0; i < ranges.starts.length; i++) {
            long rangeStart = ranges.starts[i];
            if (start < rangeStart) {
                candidateId = addIpv4LookupRange(code, order, candidateId, start, rangeStart - 1L, candidates);
            }
            if (ranges.ends[i] == IPV4_MAX) {
                return candidateId;
            }
            start = ranges.ends[i] + 1L;
        }
        if (start <= IPV4_MAX) {
            candidateId = addIpv4LookupRange(code, order, candidateId, start, IPV4_MAX, candidates);
        }
        return candidateId;
    }

    private static int addIpv4LookupRange(String code, int order, int candidateId, long start, long end,
            ArrayList<Ipv4LookupCandidate> candidates) {
        if (start <= end) {
            candidates.add(new Ipv4LookupCandidate(start, end, order, candidateId++, code));
        }
        return candidateId;
    }

    private static int addIpv6LookupRanges(String code, int order, int candidateId, Ipv6RangeSet ranges,
            ArrayList<Ipv6LookupCandidate> candidates) {
        for (int i = 0; i < ranges.startHighs.length; i++) {
            candidateId = addIpv6LookupRange(code, order, candidateId,
                    ranges.startHighs[i], ranges.startLows[i], ranges.endHighs[i], ranges.endLows[i], candidates);
        }
        return candidateId;
    }

    private static int addIpv6LookupComplement(String code, int order, int candidateId, Ipv6RangeSet ranges,
            ArrayList<Ipv6LookupCandidate> candidates) {
        long startHigh = 0L;
        long startLow = 0L;
        for (int i = 0; i < ranges.startHighs.length; i++) {
            long rangeStartHigh = ranges.startHighs[i];
            long rangeStartLow = ranges.startLows[i];
            if (compareUnsigned(startHigh, startLow, rangeStartHigh, rangeStartLow) < 0) {
                long endLow = rangeStartLow - 1L;
                long endHigh = rangeStartHigh - (rangeStartLow == 0L ? 1L : 0L);
                candidateId = addIpv6LookupRange(code, order, candidateId,
                        startHigh, startLow, endHigh, endLow, candidates);
            }
            if (isIpv6Max(ranges.endHighs[i], ranges.endLows[i])) {
                return candidateId;
            }
            startLow = ranges.endLows[i] + 1L;
            startHigh = ranges.endHighs[i] + (startLow == 0L ? 1L : 0L);
        }
        candidateId = addIpv6LookupRange(code, order, candidateId, startHigh, startLow, -1L, -1L, candidates);
        return candidateId;
    }

    private static int addIpv6LookupRange(String code, int order, int candidateId,
            long startHigh, long startLow, long endHigh, long endLow, ArrayList<Ipv6LookupCandidate> candidates) {
        if (compareUnsigned(startHigh, startLow, endHigh, endLow) <= 0) {
            candidates.add(new Ipv6LookupCandidate(startHigh, startLow, endHigh, endLow, order, candidateId++, code));
        }
        return candidateId;
    }

    private static Ipv4Range toIpv4Range(byte[] ip, int prefix) {
        if (prefix < 0 || prefix > 32) {
            throw new InvalidException("v2ray geoip ipv4 prefix invalid {}", prefix);
        }
        long value = toIpv4Long(ip);
        long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long start = value & mask;
        long end = start | (~mask & 0xffffffffL);
        return new Ipv4Range(start, end);
    }

    private static Ipv6Range toIpv6Range(byte[] ip, int prefix) {
        if (prefix < 0 || prefix > 128) {
            throw new InvalidException("v2ray geoip ipv6 prefix invalid {}", prefix);
        }
        long high = toLong(ip, 0);
        long low = toLong(ip, 8);
        if (prefix == 0) {
            return new Ipv6Range(0L, 0L, -1L, -1L);
        }
        if (prefix < 64) {
            long highMask = -1L << (64 - prefix);
            long startHigh = high & highMask;
            return new Ipv6Range(startHigh, 0L, startHigh | ~highMask, -1L);
        }
        if (prefix == 64) {
            return new Ipv6Range(high, 0L, high, -1L);
        }
        if (prefix < 128) {
            int lowPrefix = prefix - 64;
            long lowMask = -1L << (64 - lowPrefix);
            long startLow = low & lowMask;
            return new Ipv6Range(high, startLow, high, startLow | ~lowMask);
        }
        return new Ipv6Range(high, low, high, low);
    }

    private static long toIpv4Long(byte[] ip) {
        return ((long) ip[0] & 0xff) << 24
                | ((long) ip[1] & 0xff) << 16
                | ((long) ip[2] & 0xff) << 8
                | ((long) ip[3] & 0xff);
    }

    private static long toLong(byte[] ip, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | ((long) ip[offset + i] & 0xff);
        }
        return value;
    }

    private static String normalizeCode(String code, String fallback) {
        String value = GeoIPSearcher.trimAscii(code);
        if (value == null || value.isEmpty()) {
            value = GeoIPSearcher.trimAscii(fallback);
        }
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() >= GEOIP_PREFIX.length()
                && value.regionMatches(true, 0, GEOIP_PREFIX, 0, GEOIP_PREFIX.length())) {
            value = GeoIPSearcher.trimAscii(value.substring(GEOIP_PREFIX.length()));
            if (value == null || value.isEmpty()) {
                return null;
            }
        }
        return GeoSiteMatcher.toLowerAscii(value);
    }

    static boolean isIpBytes(byte[] ipBytes) {
        return ipBytes != null && (ipBytes.length == 4 || ipBytes.length == 16);
    }

    private static int compareUnsigned(long aHigh, long aLow, long bHigh, long bLow) {
        int high = Long.compareUnsigned(aHigh, bHigh);
        return high != 0 ? high : Long.compareUnsigned(aLow, bLow);
    }

    private static boolean isIpv6Max(long high, long low) {
        return high == -1L && low == -1L;
    }

    private static void appendIpv4LookupSegment(ArrayList<Ipv4LookupSegment> segments, long start, long end,
            String code) {
        if (segments.isEmpty()) {
            segments.add(new Ipv4LookupSegment(start, end, code));
            return;
        }
        Ipv4LookupSegment last = segments.get(segments.size() - 1);
        if (last.end != IPV4_MAX && last.end + 1L == start && last.code.equals(code)) {
            last.end = end;
            return;
        }
        segments.add(new Ipv4LookupSegment(start, end, code));
    }

    private static void appendIpv6LookupSegment(ArrayList<Ipv6LookupSegment> segments, long startHigh, long startLow,
            long endHigh, long endLow, String code) {
        if (segments.isEmpty()) {
            segments.add(new Ipv6LookupSegment(startHigh, startLow, endHigh, endLow, code));
            return;
        }
        Ipv6LookupSegment last = segments.get(segments.size() - 1);
        if (isIpv6Adjacent(last.endHigh, last.endLow, startHigh, startLow) && last.code.equals(code)) {
            last.endHigh = endHigh;
            last.endLow = endLow;
            return;
        }
        segments.add(new Ipv6LookupSegment(startHigh, startLow, endHigh, endLow, code));
    }

    private static boolean isIpv6Adjacent(long leftEndHigh, long leftEndLow, long rightStartHigh,
            long rightStartLow) {
        if (isIpv6Max(leftEndHigh, leftEndLow)) {
            return false;
        }
        long nextLow = leftEndLow + 1L;
        long nextHigh = leftEndHigh + (nextLow == 0L ? 1L : 0L);
        return nextHigh == rightStartHigh && nextLow == rightStartLow;
    }

    static final class CodeMatcher {
        final EntryMatcher[] entries;

        CodeMatcher(EntryMatcher[] entries) {
            this.entries = entries;
        }

        boolean matches(byte[] ipBytes) {
            for (EntryMatcher entry : entries) {
                if (entry.matches(ipBytes)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class EntryMatcher {
        final String code;
        final boolean inverseMatch;
        final Ipv4RangeSet ipv4;
        final Ipv6RangeSet ipv6;

        EntryMatcher(String code, boolean inverseMatch, Ipv4RangeSet ipv4, Ipv6RangeSet ipv6) {
            this.code = code;
            this.inverseMatch = inverseMatch;
            this.ipv4 = ipv4;
            this.ipv6 = ipv6;
        }

        boolean matches(byte[] ipBytes) {
            boolean matched = ipBytes.length == 4 ? ipv4.matches(ipBytes) : ipv6.matches(ipBytes);
            return inverseMatch ? !matched : matched;
        }
    }

    static final class Ipv4LookupCandidate {
        final long start;
        final long end;
        final int order;
        final int id;
        final String code;

        Ipv4LookupCandidate(long start, long end, int order, int id, String code) {
            this.start = start;
            this.end = end;
            this.order = order;
            this.id = id;
            this.code = code;
        }
    }

    static final class Ipv4LookupEvent {
        final long point;
        final boolean add;
        final Ipv4LookupCandidate candidate;

        Ipv4LookupEvent(long point, boolean add, Ipv4LookupCandidate candidate) {
            this.point = point;
            this.add = add;
            this.candidate = candidate;
        }
    }

    static final class Ipv4LookupSegment {
        final long start;
        long end;
        final String code;

        Ipv4LookupSegment(long start, long end, String code) {
            this.start = start;
            this.end = end;
            this.code = code;
        }
    }

    static final class Ipv4LookupSet {
        static final Ipv4LookupSet EMPTY = new Ipv4LookupSet(new long[0], new long[0], new String[0]);

        final long[] starts;
        final long[] ends;
        final String[] codes;

        Ipv4LookupSet(long[] starts, long[] ends, String[] codes) {
            this.starts = starts;
            this.ends = ends;
            this.codes = codes;
        }

        static Ipv4LookupSet build(ArrayList<Ipv4LookupCandidate> candidates) {
            if (candidates.isEmpty()) {
                return EMPTY;
            }
            ArrayList<Ipv4LookupEvent> events = new ArrayList<>(candidates.size() << 1);
            for (Ipv4LookupCandidate candidate : candidates) {
                events.add(new Ipv4LookupEvent(candidate.start, true, candidate));
                if (candidate.end != IPV4_MAX) {
                    events.add(new Ipv4LookupEvent(candidate.end + 1L, false, candidate));
                }
            }
            Collections.sort(events, new Comparator<Ipv4LookupEvent>() {
                @Override
                public int compare(Ipv4LookupEvent a, Ipv4LookupEvent b) {
                    return Long.compare(a.point, b.point);
                }
            });

            TreeSet<Ipv4LookupCandidate> active = new TreeSet<>(new Comparator<Ipv4LookupCandidate>() {
                @Override
                public int compare(Ipv4LookupCandidate a, Ipv4LookupCandidate b) {
                    int c = Integer.compare(a.order, b.order);
                    return c != 0 ? c : Integer.compare(a.id, b.id);
                }
            });
            ArrayList<Ipv4LookupSegment> segments = new ArrayList<>(events.size());
            long current = 0L;
            int i = 0;
            while (i < events.size()) {
                long point = events.get(i).point;
                if (point > current && !active.isEmpty()) {
                    appendIpv4LookupSegment(segments, current, point - 1L, active.first().code);
                }
                do {
                    Ipv4LookupEvent event = events.get(i++);
                    if (event.add) {
                        active.add(event.candidate);
                    } else {
                        active.remove(event.candidate);
                    }
                } while (i < events.size() && events.get(i).point == point);
                current = point;
            }
            if (current <= IPV4_MAX && !active.isEmpty()) {
                appendIpv4LookupSegment(segments, current, IPV4_MAX, active.first().code);
            }
            if (segments.isEmpty()) {
                return EMPTY;
            }
            long[] starts = new long[segments.size()];
            long[] ends = new long[segments.size()];
            String[] codes = new String[segments.size()];
            for (int j = 0; j < segments.size(); j++) {
                Ipv4LookupSegment segment = segments.get(j);
                starts[j] = segment.start;
                ends[j] = segment.end;
                codes[j] = segment.code;
            }
            return new Ipv4LookupSet(starts, ends, codes);
        }

        String lookup(byte[] ipBytes) {
            long value = toIpv4Long(ipBytes);
            int low = 0;
            int high = starts.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (value < starts[mid]) {
                    high = mid - 1;
                } else if (value > ends[mid]) {
                    low = mid + 1;
                } else {
                    return codes[mid];
                }
            }
            return null;
        }
    }

    static final class Ipv6LookupCandidate {
        final long startHigh;
        final long startLow;
        final long endHigh;
        final long endLow;
        final int order;
        final int id;
        final String code;

        Ipv6LookupCandidate(long startHigh, long startLow, long endHigh, long endLow, int order, int id, String code) {
            this.startHigh = startHigh;
            this.startLow = startLow;
            this.endHigh = endHigh;
            this.endLow = endLow;
            this.order = order;
            this.id = id;
            this.code = code;
        }
    }

    static final class Ipv6LookupEvent {
        final long pointHigh;
        final long pointLow;
        final boolean add;
        final Ipv6LookupCandidate candidate;

        Ipv6LookupEvent(long pointHigh, long pointLow, boolean add, Ipv6LookupCandidate candidate) {
            this.pointHigh = pointHigh;
            this.pointLow = pointLow;
            this.add = add;
            this.candidate = candidate;
        }
    }

    static final class Ipv6LookupSegment {
        final long startHigh;
        final long startLow;
        long endHigh;
        long endLow;
        final String code;

        Ipv6LookupSegment(long startHigh, long startLow, long endHigh, long endLow, String code) {
            this.startHigh = startHigh;
            this.startLow = startLow;
            this.endHigh = endHigh;
            this.endLow = endLow;
            this.code = code;
        }
    }

    static final class Ipv6LookupSet {
        static final Ipv6LookupSet EMPTY = new Ipv6LookupSet(new long[0], new long[0], new long[0], new long[0],
                new String[0]);

        final long[] startHighs;
        final long[] startLows;
        final long[] endHighs;
        final long[] endLows;
        final String[] codes;

        Ipv6LookupSet(long[] startHighs, long[] startLows, long[] endHighs, long[] endLows, String[] codes) {
            this.startHighs = startHighs;
            this.startLows = startLows;
            this.endHighs = endHighs;
            this.endLows = endLows;
            this.codes = codes;
        }

        static Ipv6LookupSet build(ArrayList<Ipv6LookupCandidate> candidates) {
            if (candidates.isEmpty()) {
                return EMPTY;
            }
            ArrayList<Ipv6LookupEvent> events = new ArrayList<>(candidates.size() << 1);
            for (Ipv6LookupCandidate candidate : candidates) {
                events.add(new Ipv6LookupEvent(candidate.startHigh, candidate.startLow, true, candidate));
                if (!isIpv6Max(candidate.endHigh, candidate.endLow)) {
                    long pointLow = candidate.endLow + 1L;
                    long pointHigh = candidate.endHigh + (pointLow == 0L ? 1L : 0L);
                    events.add(new Ipv6LookupEvent(pointHigh, pointLow, false, candidate));
                }
            }
            Collections.sort(events, new Comparator<Ipv6LookupEvent>() {
                @Override
                public int compare(Ipv6LookupEvent a, Ipv6LookupEvent b) {
                    return compareUnsigned(a.pointHigh, a.pointLow, b.pointHigh, b.pointLow);
                }
            });

            TreeSet<Ipv6LookupCandidate> active = new TreeSet<>(new Comparator<Ipv6LookupCandidate>() {
                @Override
                public int compare(Ipv6LookupCandidate a, Ipv6LookupCandidate b) {
                    int c = Integer.compare(a.order, b.order);
                    return c != 0 ? c : Integer.compare(a.id, b.id);
                }
            });
            ArrayList<Ipv6LookupSegment> segments = new ArrayList<>(events.size());
            long currentHigh = 0L;
            long currentLow = 0L;
            int i = 0;
            while (i < events.size()) {
                long pointHigh = events.get(i).pointHigh;
                long pointLow = events.get(i).pointLow;
                if (compareUnsigned(pointHigh, pointLow, currentHigh, currentLow) > 0 && !active.isEmpty()) {
                    long endLow = pointLow - 1L;
                    long endHigh = pointHigh - (pointLow == 0L ? 1L : 0L);
                    appendIpv6LookupSegment(segments, currentHigh, currentLow, endHigh, endLow, active.first().code);
                }
                do {
                    Ipv6LookupEvent event = events.get(i++);
                    if (event.add) {
                        active.add(event.candidate);
                    } else {
                        active.remove(event.candidate);
                    }
                } while (i < events.size()
                        && compareUnsigned(events.get(i).pointHigh, events.get(i).pointLow, pointHigh, pointLow) == 0);
                currentHigh = pointHigh;
                currentLow = pointLow;
            }
            if (!active.isEmpty()) {
                appendIpv6LookupSegment(segments, currentHigh, currentLow, -1L, -1L, active.first().code);
            }
            if (segments.isEmpty()) {
                return EMPTY;
            }
            long[] startHighs = new long[segments.size()];
            long[] startLows = new long[segments.size()];
            long[] endHighs = new long[segments.size()];
            long[] endLows = new long[segments.size()];
            String[] codes = new String[segments.size()];
            for (int j = 0; j < segments.size(); j++) {
                Ipv6LookupSegment segment = segments.get(j);
                startHighs[j] = segment.startHigh;
                startLows[j] = segment.startLow;
                endHighs[j] = segment.endHigh;
                endLows[j] = segment.endLow;
                codes[j] = segment.code;
            }
            return new Ipv6LookupSet(startHighs, startLows, endHighs, endLows, codes);
        }

        String lookup(byte[] ipBytes) {
            long highValue = toLong(ipBytes, 0);
            long lowValue = toLong(ipBytes, 8);
            int low = 0;
            int high = startHighs.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (compareUnsigned(highValue, lowValue, startHighs[mid], startLows[mid]) < 0) {
                    high = mid - 1;
                } else if (compareUnsigned(highValue, lowValue, endHighs[mid], endLows[mid]) > 0) {
                    low = mid + 1;
                } else {
                    return codes[mid];
                }
            }
            return null;
        }
    }

    static final class Ipv4Range {
        long start;
        long end;

        Ipv4Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class Ipv4RangeSet {
        static final Ipv4RangeSet EMPTY = new Ipv4RangeSet(new long[0], new long[0]);

        final long[] starts;
        final long[] ends;

        Ipv4RangeSet(long[] starts, long[] ends) {
            this.starts = starts;
            this.ends = ends;
        }

        static Ipv4RangeSet build(ArrayList<Ipv4Range> ranges) {
            if (ranges.isEmpty()) {
                return EMPTY;
            }
            Collections.sort(ranges, new Comparator<Ipv4Range>() {
                @Override
                public int compare(Ipv4Range a, Ipv4Range b) {
                    int c = Long.compare(a.start, b.start);
                    return c != 0 ? c : Long.compare(a.end, b.end);
                }
            });

            ArrayList<Ipv4Range> merged = new ArrayList<>(ranges.size());
            for (Ipv4Range range : ranges) {
                if (merged.isEmpty()) {
                    merged.add(range);
                    continue;
                }
                Ipv4Range last = merged.get(merged.size() - 1);
                if (range.start <= last.end + 1L) {
                    if (range.end > last.end) {
                        last.end = range.end;
                    }
                } else {
                    merged.add(range);
                }
            }
            long[] starts = new long[merged.size()];
            long[] ends = new long[merged.size()];
            for (int i = 0; i < merged.size(); i++) {
                Ipv4Range range = merged.get(i);
                starts[i] = range.start;
                ends[i] = range.end;
            }
            return new Ipv4RangeSet(starts, ends);
        }

        boolean matches(byte[] ipBytes) {
            long value = toIpv4Long(ipBytes);
            int low = 0;
            int high = starts.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (value < starts[mid]) {
                    high = mid - 1;
                } else if (value > ends[mid]) {
                    low = mid + 1;
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    static final class Ipv6Range {
        long startHigh;
        long startLow;
        long endHigh;
        long endLow;

        Ipv6Range(long startHigh, long startLow, long endHigh, long endLow) {
            this.startHigh = startHigh;
            this.startLow = startLow;
            this.endHigh = endHigh;
            this.endLow = endLow;
        }
    }

    static final class Ipv6RangeSet {
        static final Ipv6RangeSet EMPTY = new Ipv6RangeSet(new long[0], new long[0], new long[0], new long[0]);

        final long[] startHighs;
        final long[] startLows;
        final long[] endHighs;
        final long[] endLows;

        Ipv6RangeSet(long[] startHighs, long[] startLows, long[] endHighs, long[] endLows) {
            this.startHighs = startHighs;
            this.startLows = startLows;
            this.endHighs = endHighs;
            this.endLows = endLows;
        }

        static Ipv6RangeSet build(ArrayList<Ipv6Range> ranges) {
            if (ranges.isEmpty()) {
                return EMPTY;
            }
            Collections.sort(ranges, new Comparator<Ipv6Range>() {
                @Override
                public int compare(Ipv6Range a, Ipv6Range b) {
                    int c = compareUnsigned(a.startHigh, a.startLow, b.startHigh, b.startLow);
                    return c != 0 ? c : compareUnsigned(a.endHigh, a.endLow, b.endHigh, b.endLow);
                }
            });

            ArrayList<Ipv6Range> merged = new ArrayList<>(ranges.size());
            for (Ipv6Range range : ranges) {
                if (merged.isEmpty()) {
                    merged.add(range);
                    continue;
                }
                Ipv6Range last = merged.get(merged.size() - 1);
                if (overlapsOrAdjacent(last, range)) {
                    if (compareUnsigned(last.endHigh, last.endLow, range.endHigh, range.endLow) < 0) {
                        last.endHigh = range.endHigh;
                        last.endLow = range.endLow;
                    }
                } else {
                    merged.add(range);
                }
            }
            long[] startHighs = new long[merged.size()];
            long[] startLows = new long[merged.size()];
            long[] endHighs = new long[merged.size()];
            long[] endLows = new long[merged.size()];
            for (int i = 0; i < merged.size(); i++) {
                Ipv6Range range = merged.get(i);
                startHighs[i] = range.startHigh;
                startLows[i] = range.startLow;
                endHighs[i] = range.endHigh;
                endLows[i] = range.endLow;
            }
            return new Ipv6RangeSet(startHighs, startLows, endHighs, endLows);
        }

        boolean matches(byte[] ipBytes) {
            long highValue = toLong(ipBytes, 0);
            long lowValue = toLong(ipBytes, 8);
            int low = 0;
            int high = startHighs.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (compareUnsigned(highValue, lowValue, startHighs[mid], startLows[mid]) < 0) {
                    high = mid - 1;
                } else if (compareUnsigned(highValue, lowValue, endHighs[mid], endLows[mid]) > 0) {
                    low = mid + 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        private static boolean overlapsOrAdjacent(Ipv6Range left, Ipv6Range right) {
            if (compareUnsigned(right.startHigh, right.startLow, left.endHigh, left.endLow) <= 0) {
                return true;
            }
            if (left.endHigh == -1L && left.endLow == -1L) {
                return true;
            }
            long nextLow = left.endLow + 1L;
            long nextHigh = left.endHigh + (nextLow == 0L ? 1L : 0L);
            return compareUnsigned(right.startHigh, right.startLow, nextHigh, nextLow) <= 0;
        }
    }
}
