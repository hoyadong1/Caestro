import com.drivesoundtrack.app.SoundModel;

public final class SoundModelCheck {
    public static void main(String[] args) {
        SoundModel model = new SoundModel();
        double idleBpm = model.bpm();
        model.setSpeed(100);
        for (int i = 0; i < 250; i++) model.tick();
        if (!(model.bpm() > idleBpm + 20 && model.bpm() <= 135.1)) throw new AssertionError("speed mapping");
        double normal = model.bpm();
        model.setDriveMode(2);
        for (int i = 0; i < 250; i++) model.tick();
        if (!(model.bpm() > normal + 3)) throw new AssertionError("sport mode mapping");
        model.setSpeed(-100);
        for (int i = 0; i < 350; i++) model.tick();
        if (model.speed() < 0 || model.speed() > 0.01) throw new AssertionError("speed clamp");
        System.out.println("SoundModel checks passed");
    }
}
