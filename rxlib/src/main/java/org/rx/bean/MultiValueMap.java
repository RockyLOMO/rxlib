package org.rx.bean;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import java.util.List;

public class MultiValueMap<K, V> extends ArrayListValuedHashMap<K, V> {
    private static final long serialVersionUID = 2045827206865943227L;

    public V getFirst(K key) {
        List<V> vs = get(key);
        if (CollectionUtils.isEmpty(vs)) {
            return null;
        }
        return vs.get(0);
    }
}
