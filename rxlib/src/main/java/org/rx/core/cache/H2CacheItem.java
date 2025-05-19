package org.rx.core.cache;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.annotation.DbColumn;
import org.rx.codec.CodecUtil;
import org.rx.core.CachePolicy;
import org.rx.io.Serializer;

import java.util.Map;

public class H2CacheItem<K, V> extends CachePolicy implements Map.Entry<K, V> {
    private static final long serialVersionUID = -7742074465897857966L;
    @Getter
    @DbColumn(primaryKey = true)
    long id;
    @Getter
    @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
    long valIdx;
    @Getter
    @Setter
    String region;

    //    @Getter
//    K key;
//    V val;
//
//    public void setKey(K key) {
//        id = CodecUtil.hash64(this.key = key);
//    }
//
//    @Override
//    public V getValue() {
//        return val;
//    }
//
//    @Override
//    public V setValue(V value) {
//        V oldValue = getValue();
//        val = value;
//        valIdx = CodecUtil.hash64(val);
//        return oldValue;
//    }
    private byte[] key;
    private byte[] val;

    @Override
    public K getKey() {
        return key == null ? null : Serializer.DEFAULT.deserializeFromBytes(key);
    }

    public void setKey(K key) {
        id = CodecUtil.hash64(this.key = Serializer.DEFAULT.serializeToBytes(key));
    }

    @Override
    public V getValue() {
        return val == null ? null : Serializer.DEFAULT.deserializeFromBytes(val);
    }

    @Override
    public V setValue(V value) {
        V oldValue = getValue();
        val = Serializer.DEFAULT.serializeToBytes(value);
        valIdx = CodecUtil.hash64(val);
        return oldValue;
    }

    public H2CacheItem() {
        super(null);
    }

    public H2CacheItem(K key, V value, CachePolicy policy) {
        super(policy);
        setKey(key);
        setValue(value);
    }

    @Override
    public String toString() {
        return "H2CacheItem{" +
                "key=" + getKey() +
                ", value=" + getValue() +
                ", region=" + getRegion() +
                '}';
    }
}
