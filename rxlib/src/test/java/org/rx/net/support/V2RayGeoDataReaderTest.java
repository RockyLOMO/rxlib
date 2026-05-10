package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class V2RayGeoDataReaderTest {
    @Test
    public void readGeoSiteListParsesKnownFieldsAndSkipsUnknownFields() {
        byte[] domain = V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "Google.COM", "Ads");
        byte[] entry = V2RayGeoDataTestUtil.withUnknownVarint(
                V2RayGeoDataTestUtil.geoSiteEntry("Google", domain), 99, 7);

        V2RayGeoDataReader.GeoSiteListData data = new V2RayGeoDataReader()
                .readGeoSiteList(V2RayGeoDataTestUtil.geoSiteList(entry));

        assertEquals(1, data.entries.size());
        V2RayGeoDataReader.GeoSiteEntry site = data.entries.get(0);
        assertEquals("Google", site.code);
        assertEquals(1, site.domains.size());
        assertEquals(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, site.domains.get(0).type);
        assertEquals("Google.COM", site.domains.get(0).value);
        assertArrayEquals(new String[]{"Ads"}, site.domains.get(0).attributes);
    }

    @Test
    public void readGeoIpListParsesCidrAndInverseMatch() {
        byte[] cidr = V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16);
        byte[] entry = V2RayGeoDataTestUtil.geoIpEntry("CN", true, cidr);

        V2RayGeoDataReader.GeoIpListData data = new V2RayGeoDataReader()
                .readGeoIpList(V2RayGeoDataTestUtil.geoIpList(entry));

        assertEquals(1, data.entries.size());
        V2RayGeoDataReader.GeoIpEntry geoIp = data.entries.get(0);
        assertEquals("CN", geoIp.code);
        assertEquals(true, geoIp.inverseMatch);
        assertEquals(1, geoIp.cidrs.size());
        assertArrayEquals(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), geoIp.cidrs.get(0).ip);
        assertEquals(16, geoIp.cidrs.get(0).prefix);
    }

    @Test
    public void readGeoIpListRejectsInvalidPrefix() {
        byte[] badCidr = V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4), 33);

        assertThrows(InvalidException.class, () -> new V2RayGeoDataReader()
                .readGeoIpList(V2RayGeoDataTestUtil.geoIpList(V2RayGeoDataTestUtil.geoIpEntry("bad", false, badCidr))));
    }

    @Test
    public void readGeoSiteListRejectsOutOfBoundsLength() {
        assertThrows(InvalidException.class, () -> new V2RayGeoDataReader()
                .readGeoSiteList(V2RayGeoDataTestUtil.truncatedLengthDelimited()));
    }
}
