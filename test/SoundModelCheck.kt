import com.drivesoundtrack.app.SoundModel

fun main() {
    val model = SoundModel()
    val idleBpm = model.bpm()
    model.setSpeed(100.0)
    repeat(250) { model.tick() }
    check(model.bpm() > idleBpm + 20 && model.bpm() <= 135.1) { "speed mapping" }

    val normal = model.bpm()
    model.setDriveMode(2)
    repeat(250) { model.tick() }
    check(model.bpm() > normal + 3) { "sport mode mapping" }

    model.setSpeed(-100.0)
    repeat(350) { model.tick() }
    check(model.speed() in 0.0..0.01) { "speed clamp" }

    model.setSpeed(Double.NaN)
    model.setSpeed(Double.POSITIVE_INFINITY)
    model.tick()
    check(model.bpm().isFinite() && model.speed().isFinite()) { "non-finite sensor input" }
    println("SoundModel checks passed")
}
