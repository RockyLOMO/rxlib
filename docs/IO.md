## HybridStream - heep/direct buffer & memory/file stream混用BIO

内部支持 heep / direct buffer，当memory stream超过阈值后自动切换为file stream。

```java
@Test
public void hybridStream() {
    int[] maxSizes = new int[]{35, 70};
    for (int max : maxSizes) {
        HybridStream stream = new HybridStream(max, null);
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

