package org.rx.net.support;

import java.io.File;
import java.io.IOException;

public final class V2RayGeoIpReader {
    final V2RayGeoDataReader dataReader;

    public V2RayGeoIpReader() {
        this(new V2RayGeoDataReader());
    }

    V2RayGeoIpReader(V2RayGeoDataReader dataReader) {
        this.dataReader = dataReader;
    }

    public V2RayGeoIpMatcher read(File file) throws IOException {
        return new V2RayGeoIpMatcher(new V2RayGeoIpIndex(dataReader.readGeoIpList(file)));
    }

    public V2RayGeoIpMatcher read(byte[] data) {
        return new V2RayGeoIpMatcher(new V2RayGeoIpIndex(dataReader.readGeoIpList(data)));
    }
}
