import com.drivesoundtrack.app.SoundModel;
import com.drivesoundtrack.app.SoundRenderer;
import java.io.FileOutputStream;
import java.io.IOException;

/** Renders the same synthesis code as the Android app without booting an emulator. */
public final class PreviewWav {
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("output WAV path and optional video mode required");
        boolean video = args.length == 2 && "video".equals(args[1]);
        SoundModel model = new SoundModel();
        SoundRenderer renderer = new SoundRenderer(model);
        renderer.setPreset(0);
        int total = SoundRenderer.RATE * 20;
        byte[] audio = new byte[total * 2];
        short[] block = new short[1024];
        int sample = 0;
        int peak = 0;
        while (sample < total) {
            double second = sample / (double) SoundRenderer.RATE;
            model.setSpeed(video ? (second < 5 ? 0 : second < 9 ? 40 : 90) : (second < 5 ? 0 : second < 10 ? 40 : second < 15 ? 90 : 20));
            if (video) renderer.setPreset(second < 13 ? 0 : second < 16 ? 1 : 2);
            renderer.render(block);
            for (short value : block) {
                if (sample >= total) break;
                if (video) {
                    double t = sample / (double) SoundRenderer.RATE;
                    double gain = Math.max(0, Math.min(1, Math.min((t - 2) / 0.08, (19 - t) / 0.08)));
                    value = (short) (value * gain);
                }
                peak = Math.max(peak, Math.abs((int) value));
                audio[2 * sample] = (byte) value;
                audio[2 * sample + 1] = (byte) (value >>> 8);
                sample++;
            }
        }
        if (peak < 1000 || peak >= 32767) throw new IllegalStateException("unexpected audio peak: " + peak);
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            writeAscii(out, "RIFF"); writeInt(out, 36 + audio.length); writeAscii(out, "WAVE");
            writeAscii(out, "fmt "); writeInt(out, 16); writeShort(out, 1); writeShort(out, 1);
            writeInt(out, SoundRenderer.RATE); writeInt(out, SoundRenderer.RATE * 2);
            writeShort(out, 2); writeShort(out, 16);
            writeAscii(out, "data"); writeInt(out, audio.length); out.write(audio);
        }
        System.out.println("Preview written; peak=" + peak);
    }
    private static void writeAscii(FileOutputStream out, String s) throws IOException { out.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
    private static void writeShort(FileOutputStream out, int v) throws IOException { out.write(v); out.write(v >>> 8); }
    private static void writeInt(FileOutputStream out, int v) throws IOException { out.write(v); out.write(v >>> 8); out.write(v >>> 16); out.write(v >>> 24); }
}
