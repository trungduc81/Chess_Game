package vn.edu.ltm.chess.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Đọc một frame NDJSON và giới hạn kích thước theo byte trước khi giải mã UTF-8. */
public final class LimitedLineReader {
    public static final int MAX_FRAME_BYTES = 65_536;

    private final InputStream input;

    public LimitedLineReader(InputStream input) {
        this.input = input instanceof BufferedInputStream ? input : new BufferedInputStream(input);
    }

    public String readLine() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(512);
        while (true) {
            int value = input.read();
            if (value == -1) {
                return frame.size() == 0 ? null : decode(frame.toByteArray());
            }
            if (value == '\n') {
                byte[] bytes = frame.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return decode(bytes, length);
            }
            if (frame.size() >= MAX_FRAME_BYTES) {
                throw new IOException("Message frame exceeds 64 KiB limit");
            }
            frame.write(value);
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        return decode(bytes, bytes.length);
    }

    private static String decode(byte[] bytes, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Message frame is not valid UTF-8", exception);
        }
    }
}
