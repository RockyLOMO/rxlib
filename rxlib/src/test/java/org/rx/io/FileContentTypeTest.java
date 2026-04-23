package org.rx.io;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileContentTypeTest {
    @Test
    void textPlainExtensionUsesStableFallbackMapping() {
        assertEquals("text/plain", Files.getContentType("sample.txt"));
    }

    @Test
    void markdownExtensionUsesStableFallbackMapping() {
        assertEquals("text/markdown", Files.getContentType("sample.md"));
    }

    @Test
    void fileStreamDelegatesToSharedMimeResolver() throws Exception {
        File file = File.createTempFile("rxlib-", ".md");
        try (FileStream stream = new FileStream(file)) {
            assertEquals("text/markdown", stream.getContentType());
        } finally {
            file.delete();
        }
    }
}
