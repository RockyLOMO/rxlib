package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerResponseTest {
    @Test
    void replacingContentReleasesPreviousOwnership() {
        ServerResponse response = new ServerResponse();
        ByteBuf first = Unpooled.buffer().writeByte(1);
        ByteBuf second = Unpooled.buffer().writeByte(2);
        response.setContent(first);
        response.setContent(second);
        try {
            assertEquals(0, first.refCnt());
            assertSame(second, response.getContent());
            response.setContent(second);
            assertEquals(1, second.refCnt());
            response.setContent(null);
            assertEquals(0, second.refCnt());
        } finally {
            if (first.refCnt() > 0) first.release();
            if (second.refCnt() > 0) second.release();
        }
    }

    @Test
    void bodyHelpersReleasePreviousContent() {
        ServerResponse response = new ServerResponse();
        ByteBuf first = Unpooled.buffer().writeByte(1);
        response.setContent(first);
        response.htmlBody("hello");
        ByteBuf html = response.getContent();
        response.jsonBody("world");
        try {
            assertEquals(0, first.refCnt());
            assertEquals(0, html.refCnt());
            assertEquals("world", response.getContent().toString(CharsetUtil.UTF_8));
        } finally {
            if (first.refCnt() > 0) first.release();
            if (html.refCnt() > 0) html.release();
            response.setContent(null);
        }
    }

    @Test
    void failedBodyWritePreservesExistingContent() {
        ServerResponse response = new ServerResponse();
        response.htmlBody("original");
        ByteBuf original = response.getContent();
        try {
            assertThrows(NullPointerException.class, () -> response.htmlBody(null));
            assertSame(original, response.getContent());
            assertEquals(1, original.refCnt());
        } finally {
            if (original.refCnt() > 0 && original != response.getContent()) original.release();
            response.setContent(null);
        }
    }

    @Test
    void redirectDefaultsToFoundAndDiscardsBody() {
        ServerResponse response = new ServerResponse();
        ByteBuf body = Unpooled.buffer().writeByte(1);
        response.setContent(body);
        response.redirect("/next");
        try {
            assertEquals(HttpResponseStatus.FOUND, response.getStatus());
            assertEquals("/next", response.getHeaders().get(HttpHeaderNames.LOCATION));
            assertNull(response.getContent());
            assertEquals(0, body.refCnt());
        } finally {
            response.setContent(null);
        }
    }

    @Test
    void takingContentTransfersOwnershipAndCleanupIsIdempotent() {
        ServerResponse response = new ServerResponse();
        ByteBuf body = Unpooled.buffer().writeByte(1);
        response.setContent(body);
        assertSame(body, response.takeContent());
        assertNull(response.takeContent());
        response.releaseContent();
        assertEquals(1, body.refCnt());
        body.release();

        ByteBuf second = Unpooled.buffer().writeByte(2);
        response.setContent(second);
        response.releaseContent();
        response.releaseContent();
        assertEquals(0, second.refCnt());
    }
}
