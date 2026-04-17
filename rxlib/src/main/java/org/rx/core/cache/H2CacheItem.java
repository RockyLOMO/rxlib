package org.rx.core.cache;

import lombok.Getter;
import lombok.Setter;
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
    @Setter
    long version;
    @Getter
    @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
    long valIdx;
    @Getter
    @Setter
    String region;
    @Getter
    @Setter
    boolean tombstone;

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
    private transient volatile boolean keyDecoded;
    private transient volatile boolean valDecoded;
    private transient K _key;
    private transient V _val;

    @Override
    public K getKey() {
        if (keyDecoded) {
            return _key;
        }
        synchronized (this) {
            if (!keyDecoded) {
                _key = key == null ? null : Serializer.DEFAULT.deserializeFromBytes(key);
                keyDecoded = true;
            }
        }
        return _key;
    }

    public void setKey(K key) {
        synchronized (this) {
            this._key = key;
            this.keyDecoded = true;
            this.id = CodecUtil.hash64(this.key = Serializer.DEFAULT.serializeToBytes(key));
        }
    }

    @Override
    public V getValue() {
        if (valDecoded) {
            return _val;
        }
        synchronized (this) {
            if (!valDecoded) {
                _val = val == null ? null : Serializer.DEFAULT.deserializeFromBytes(val);
                valDecoded = true;
            }
        }
        return _val;
    }

    @Override
    public V setValue(V value) {
        synchronized (this) {
            V oldValue = getValue();
            this._val = value;
            this.valDecoded = true;
            this.val = Serializer.DEFAULT.serializeToBytes(value);
            this.valIdx = CodecUtil.hash64(this.val);
            return oldValue;
        }
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
