//package org.rx.io;
//
//import lombok.NonNull;
//import lombok.RequiredArgsConstructor;
//
//import java.io.File;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Comparator;
//import java.util.List;
//
//import static org.rx.core.Extends.eq;
//
///**
// * <p>index
// * crc64(8) + pos(8)
// *
// * @param <TK>
// */
//class ExternalSortingIndexer<TK> {
//    @RequiredArgsConstructor
//    class SubFile {
//        final FileStream file;
//
//        List<KeyIndexer<TK>> read() {
//            file.mmap()
//            file.setPosition(0);
//            return serializer.deserialize(file, true);
//        }
//
//        void write(List<KeyIndexer<TK>> keys) {
//            keys.sort();
//            Object[] objects = keys.toArray();
//            Arrays.sort(objects,comparator);
//            keys.sort(comparator);
//            file.setPosition(0);
//            serializer.serialize(keys, file);l
//        }
//    }
//
//    final FileStream fs;
//    final int keySize;
//    final Comparator<KeyIndexer<TK>> comparator;
//    final Serializer serializer;
//
//    public ExternalSortingIndexer(File file, int keyCount, @NonNull Serializer serializer) {
//        fs = new FileStream(file, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.NON_BUF);
//        this.keySize = keyCount;
//        this.serializer = serializer;
//    }
//
//    public void save(@NonNull KeyIndexer<TK> key) {
//        List<KeyIndexer<TK>> list = new ArrayList<>(keySize);
//        fs.setPosition(0);
//        boolean find = false;
//        for (int i = 0; i < keySize; i++) {
//            KeyIndexer<TK> k = serializer.deserialize(fs, true);
//            if (eq(key.key, k.key)) {
//                find = true;
//                list.add(key);
//                continue;
//            }
//            list.add(k);
//        }
//    }
//
//    public KeyIndexer<TK> find(@NonNull TK k) {
//
//    }
//}
