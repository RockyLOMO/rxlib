package org.rx.net.support;

import java.io.File;
import java.io.IOException;

public final class V2RayGeoSiteReader {
    final V2RayGeoDataReader dataReader;

    public V2RayGeoSiteReader() {
        this(new V2RayGeoDataReader());
    }

    V2RayGeoSiteReader(V2RayGeoDataReader dataReader) {
        this.dataReader = dataReader;
    }

    public V2RayGeoSiteIndex read(File file) throws IOException {
        return new V2RayGeoSiteIndex(dataReader.readGeoSiteList(file));
    }

    public V2RayGeoSiteIndex read(byte[] data) {
        return new V2RayGeoSiteIndex(dataReader.readGeoSiteList(data));
    }
}
