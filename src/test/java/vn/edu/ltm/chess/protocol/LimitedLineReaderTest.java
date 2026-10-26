package vn.edu.ltm.chess.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LimitedLineReaderTest {
    @Test void readsUtf8FramesAndHandlesCrLf() throws Exception {
        LimitedLineReader reader = new LimitedLineReader(new ByteArrayInputStream(
                "xin chào\r\nframe 2\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("xin chào", reader.readLine());
        assertEquals("frame 2", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test void rejectsFrameLargerThanSixtyFourKiB() {
        byte[] oversized = ("a".repeat(LimitedLineReader.MAX_FRAME_BYTES + 1) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        LimitedLineReader reader = new LimitedLineReader(new ByteArrayInputStream(oversized));

        IOException exception = assertThrows(IOException.class, reader::readLine);
        assertEquals("Message frame exceeds 64 KiB limit", exception.getMessage());
    }
}
