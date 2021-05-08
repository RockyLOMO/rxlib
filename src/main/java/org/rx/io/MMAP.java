//package org.rx.io;
//
//public class MMAP {
//    public class TestMmap {
//        public static String path = "C:\\Users\\64371\\Desktop\\mmap";
//
//        public static void main(String[] args) throws IOException {
//            File file1 = new File(path, "1");
//
//            RandomAccessFile randomAccessFile = new RandomAccessFile(file1, "rw");
//
//            int len = 2048;
//            // 映射为2kb，那么生成的文件也是2kb
//            MappedByteBuffer mmap = randomAccessFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, len);
//
//            System.out.println(mmap.isReadOnly());
//
//
//
//            System.out.println(mmap.position());
//            System.out.println(mmap.limit());
//
//            // 写数据之后，JVM 退出之后会强制刷新的
//            mmap.put("a".getBytes());
//            mmap.put("b".getBytes());
//            mmap.put("c".getBytes());
//            mmap.put("d".getBytes());
//
////        System.out.println(mmap.position());
////        System.out.println(mmap.limit());
////
////        mmap.force();
//
//            // 参考OffsetIndex强制回收已经分配的mmap，不必等到下次GC，
//            unmap(mmap);
//            // 在Windows上需要执行unmap(mmap); 否则报错
//            // Windows won't let us modify the file length while the file is mmapped
//            // java.io.IOException: 请求的操作无法在使用用户映射区域打开的文件上执行
//            randomAccessFile.setLength(len/2);
//            mmap = randomAccessFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, len/2);
//
//
//            // A mapping, once established, is not dependent upon the file channel
//            // that was used to create it.  Closing the channel, in particular, has no
//            // effect upon the validity of the mapping.
//            randomAccessFile.close();
//
//            mmap.put(128, "z".getBytes()[0]);
//
//        }
//
//        // copy from  FileChannelImpl#unmap(私有方法)
//        private static void unmap(MappedByteBuffer bb) {
//            Cleaner cl = ((DirectBuffer)bb).cleaner();
//            if (cl != null)
//                cl.clean();
//        }
//    }
//}
