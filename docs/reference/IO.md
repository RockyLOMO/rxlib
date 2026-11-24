## HybridStream

堆内或 direct buffer。内存超过 `maxMemorySize` 后切到文件。第二个参数是 `directMemory`，不是 null。

```java
@Test
public void hybridStream() {
    int[] maxSizes = new int[]{35, 70};
    for (int max : maxSizes) {
        HybridStream stream = new HybridStream(max, false);
        testSeekStream(stream);

        long position = stream.getPosition();
        System.out.println(position);
        stream.write(content);
        assert stream.getPosition() == position + content.length && stream.getLength() == stream.getPosition();
        byte[] data = new byte[(int) stream.getLength()];
        stream.setPosition(0L);
        stream.read(data);
        System.out.println(new String(data));
    }
}
```

