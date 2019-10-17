# rxlib-java

A set of utilities for Java.

### Maven
```xml
<dependency>
    <groupId>com.github.rockylomo</groupId>
    <artifactId>rxlib</artifactId>
    <version>2.12</version>
</dependency>
```

### shadowsocks (Only tested AES encryption)
    * A pure client for [shadowsocks](https://github.com/shadowsocks/shadowsocks).
    * Requirements
        Bouncy Castle v1.5.9 [Release](https://www.bouncycastle.org/)
    * Using Non-blocking server
        Config config = new Config("SS_SERVER_IP", "SS_SERVER_PORT", "LOCAL_IP", "LOCAL_PORT", "CIPHER_NAME", "PASSWORD");
        NioLocalServer server = new NioLocalServer(config);
        new Thread(server).start();
