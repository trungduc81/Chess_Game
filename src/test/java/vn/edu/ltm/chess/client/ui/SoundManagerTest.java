package vn.edu.ltm.chess.client.ui;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundManagerTest {
    @Test
    void classifiesMoveNotation() {
        assertEquals(SoundManager.Effect.MOVE, SoundManager.moveEffect("e4"));
        assertEquals(SoundManager.Effect.CAPTURE, SoundManager.moveEffect("Nxf3"));
        assertEquals(SoundManager.Effect.CHECK, SoundManager.moveEffect("Qh7+"));
        assertEquals(SoundManager.Effect.CHECK, SoundManager.moveEffect("Qxh7#"));
        assertEquals(SoundManager.Effect.CASTLE, SoundManager.moveEffect("O-O"));
        assertEquals(SoundManager.Effect.CASTLE, SoundManager.moveEffect("O-O-O"));
        assertEquals(SoundManager.Effect.PROMOTE, SoundManager.moveEffect("e8=Q"));
    }

    @Test
    void allSoundResourcesArePcmAndStartWithoutLeadingSilence() throws Exception {
        for (SoundManager.Effect effect : SoundManager.Effect.values()) {
            var resource = SoundManager.class.getResourceAsStream(effect.resourcePath());
            assertNotNull(resource, effect.name());
            try (var input = new BufferedInputStream(resource);
                 var audio = AudioSystem.getAudioInputStream(input)) {
                var format = audio.getFormat();
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding(), effect.name());
                assertEquals(16, format.getSampleSizeInBits(), effect.name());
                assertEquals(1, format.getChannels(), effect.name());
                assertTrue(audio.getFrameLength() > 0, effect.name());
                // Có tín hiệu nghe được trong 20 ms đầu, tránh tái xuất hiện độ trễ do file.
                byte[] start = audio.readNBytes((int) (format.getSampleRate() * 0.02) * 2);
                int peak = 0;
                for (int i = 0; i + 1 < start.length; i += 2) {
                    short sample = (short) ((start[i] & 0xff) | (start[i + 1] << 8));
                    peak = Math.max(peak, Math.abs((int) sample));
                }
                assertTrue(peak > 100, effect + " starts with silence");
            }
        }
    }
}
