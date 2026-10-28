package vn.edu.ltm.chess.client.ui;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/** Nạp sẵn WAV PCM và giữ đường âm thanh mở để phát ngay khi có sự kiện. */
public final class SoundManager {
    public enum Effect {
        MOVE("move.wav"),
        CAPTURE("capture.wav"),
        CHECK("check.wav"),
        CASTLE("castle.wav"),
        PROMOTE("promote.wav"),
        ILLEGAL("illegal.wav"),
        GAME_START("game-start.wav"),
        LOW_TIME("low-time.wav"),
        NOTIFY("notify.wav"),
        WIN("victory.wav"),
        LOSE("defeat.wav"),
        DRAW("draw.wav"),
        DISCONNECT("disconnect.wav");

        private final String fileName;

        Effect(String fileName) {
            this.fileName = fileName;
        }

        String resourcePath() {
            return "/sounds/" + fileName;
        }
    }

    private static final Map<Effect, Clip> CLIPS = new EnumMap<>(Effect.class);
    private static volatile boolean muted;

    private SoundManager() {
    }

    public static void initialize() {
        for (Effect effect : Effect.values()) {
            if (CLIPS.containsKey(effect)) continue;
            try {
                CLIPS.put(effect, load(effect));
            } catch (Exception exception) {
                System.err.println("Không thể nạp âm thanh " + effect + ": " + exception.getMessage());
            }
        }
    }

    public static boolean isMuted() {
        return muted;
    }

    public static boolean toggleMuted() {
        muted = !muted;
        if (muted) {
            CLIPS.values().forEach(Clip::stop);
        } else {
            play(Effect.MOVE);
        }
        return muted;
    }

    public static void play(Effect effect) {
        if (muted || effect == null) {
            return;
        }
        Clip clip = CLIPS.get(effect);
        if (clip == null) return;
        if (effect == Effect.WIN || effect == Effect.LOSE || effect == Effect.DRAW) {
            // Kết quả được ưu tiên, không lẫn tiếng chiếu hoặc cảnh báo còn đang phát.
            CLIPS.values().forEach(Clip::stop);
        }
        clip.stop();
        clip.setFramePosition(0);
        clip.start();
    }

    public static void stop(Effect effect) {
        Clip clip = CLIPS.get(effect);
        if (clip != null) clip.stop();
    }

    public static Effect moveEffect(String notation) {
        if (notation == null) {
            return Effect.MOVE;
        }
        if (notation.endsWith("+") || notation.endsWith("#")) {
            return Effect.CHECK;
        }
        if (notation.startsWith("O-O")) {
            return Effect.CASTLE;
        }
        if (notation.contains("=")) {
            return Effect.PROMOTE;
        }
        if (notation.contains("x")) {
            return Effect.CAPTURE;
        }
        return Effect.MOVE;
    }

    public static void shutdown() {
        CLIPS.values().forEach(Clip::close);
        CLIPS.clear();
    }

    private static Clip load(Effect effect) throws Exception {
        String resource = effect.resourcePath();
        InputStream resourceStream = SoundManager.class.getResourceAsStream(resource);
        if (resourceStream == null) {
            throw new IllegalStateException("Không tìm thấy tài nguyên âm thanh: " + resource);
        }
        try (InputStream input = new BufferedInputStream(resourceStream);
             AudioInputStream audio = AudioSystem.getAudioInputStream(input)) {
            Clip clip = AudioSystem.getClip();
            try {
                clip.open(audio);
                return clip;
            } catch (Exception exception) {
                clip.close();
                throw exception;
            }
        }
    }
}
