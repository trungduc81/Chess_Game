package vn.edu.ltm.chess.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phát các hiệu ứng âm thanh ngắn của client bằng Java Sound. */
public final class SoundManager {
    public enum Effect {
        MOVE,
        CAPTURE,
        CHECK,
        SPECIAL_MOVE,
        CHALLENGE,
        DRAW_OFFER,
        CHAT,
        TIME_WARNING,
        WIN,
        LOSE,
        DRAW,
        DISCONNECT
    }

    private static final float SAMPLE_RATE = 44_100f;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = Thread.ofVirtual().name("chess-sound-player").unstarted(runnable);
        return thread;
    });

    private static volatile boolean muted;

    private SoundManager() {
    }

    public static boolean isMuted() {
        return muted;
    }

    public static boolean toggleMuted() {
        muted = !muted;
        if (!muted) {
            play(Effect.MOVE);
        }
        return muted;
    }

    public static void play(Effect effect) {
        if (muted || effect == null) {
            return;
        }
        PLAYER.submit(() -> playPattern(patternFor(effect)));
    }

    public static void shutdown() {
        PLAYER.shutdownNow();
    }

    private static Tone[] patternFor(Effect effect) {
        return switch (effect) {
            case MOVE -> tones(520, 55, 0.34);
            case CAPTURE -> tones(300, 60, 0.42, 190, 80, 0.38);
            case CHECK -> tones(820, 80, 0.42, 1_040, 105, 0.44);
            case SPECIAL_MOVE -> tones(620, 65, 0.35, 820, 65, 0.38, 1_080, 95, 0.42);
            case CHALLENGE -> tones(660, 85, 0.38, 880, 120, 0.42);
            case DRAW_OFFER -> tones(590, 80, 0.32, 740, 90, 0.34);
            case CHAT -> tones(760, 55, 0.28);
            case TIME_WARNING -> tones(980, 90, 0.42, 980, 90, 0.42);
            case WIN -> tones(523, 90, 0.38, 659, 90, 0.40, 784, 90, 0.42, 1_046, 180, 0.46);
            case LOSE -> tones(392, 120, 0.38, 330, 130, 0.36, 262, 190, 0.38);
            case DRAW -> tones(523, 115, 0.34, 587, 115, 0.34, 523, 160, 0.32);
            case DISCONNECT -> tones(330, 100, 0.38, 220, 130, 0.40, 150, 180, 0.42);
        };
    }

    private static Tone[] tones(double... values) {
        Tone[] tones = new Tone[values.length / 3];
        for (int index = 0; index < values.length; index += 3) {
            tones[index / 3] = new Tone(values[index], (int) values[index + 1], values[index + 2]);
        }
        return tones;
    }

    private static void playPattern(Tone[] tones) {
        if (muted) {
            return;
        }
        try {
            byte[] audio = render(tones);
            SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, audio.length);
            line.start();
            line.write(audio, 0, audio.length);
            line.drain();
            line.close();
        } catch (Exception ignored) {
            // Máy không có thiết bị âm thanh vẫn phải tiếp tục chạy bình thường.
        }
    }

    private static byte[] render(Tone[] tones) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Tone tone : tones) {
            int sampleCount = Math.max(1, Math.round(SAMPLE_RATE * tone.durationMs() / 1_000f));
            int fadeSamples = Math.min(sampleCount / 2, Math.round(SAMPLE_RATE * 0.012f));
            for (int sample = 0; sample < sampleCount; sample++) {
                double envelope = 1.0;
                if (sample < fadeSamples) {
                    envelope = (double) sample / fadeSamples;
                } else if (sample >= sampleCount - fadeSamples) {
                    envelope = (double) (sampleCount - sample - 1) / fadeSamples;
                }
                double angle = 2.0 * Math.PI * tone.frequency() * sample / SAMPLE_RATE;
                short value = (short) (Math.sin(angle) * Short.MAX_VALUE * tone.gain() * envelope);
                output.write(value & 0xff);
                output.write((value >>> 8) & 0xff);
            }
            int gapSamples = Math.round(SAMPLE_RATE * 0.035f);
            for (int sample = 0; sample < gapSamples; sample++) {
                output.write(0);
                output.write(0);
            }
        }
        return output.toByteArray();
    }

    private record Tone(double frequency, int durationMs, double gain) {
    }
}
