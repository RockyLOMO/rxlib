package org.rx.io;

import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;

import java.io.StreamCorruptedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FurySerializerTest {
    @Test
    void roundTripObjectAndDateTime() {
        FurySerializer serializer = new FurySerializer();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "fury");
        payload.put("seq", 7);
        Map<String, Object> decoded = serializer.deserialize(serializer.serialize(payload));
        assertEquals(payload, decoded);

        DateTime dateTime = new DateTime(1711960245123L, TimeZone.getTimeZone("UTC"));
        DateTime decodedDateTime = serializer.deserialize(serializer.serialize(dateTime));
        assertEquals(dateTime.getTime(), decodedDateTime.getTime());
        assertEquals(dateTime.getTimeZone().getID(), decodedDateTime.getTimeZone().getID());
    }

    @Test
    void readsOneFrameAtATime() {
        FurySerializer serializer = new FurySerializer();
        HybridStream stream = new HybridStream();
        try {
            serializer.serialize("first", stream);
            serializer.serialize("second", stream);

            stream.rewind();
            assertEquals("first", serializer.deserialize(stream, true));
            assertEquals("second", serializer.deserialize(stream, true));
        } finally {
            stream.close();
        }
    }

    @Test
    void rejectsTypeOutsideAllowlist() {
        FurySerializer writer = new FurySerializer().allowPrefix("com.acme");
        byte[] encoded = writer.serializeToBytes(new com.acme.transport.ForbiddenPojo("deny"));

        assertThrows(Exception.class, () -> new FurySerializer().deserializeFromBytes(encoded));
    }

    @Test
    void invalidFrameLooksLikeCorruptedStream() {
        assertThrows(StreamCorruptedException.class,
                () -> new FurySerializer().deserialize(DuplexStream.wrap("", new byte[8])));
    }
}
