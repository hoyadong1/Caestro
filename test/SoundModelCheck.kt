import com.drivesoundtrack.app.SoundModel

fun main() {
    val model = SoundModel()
    val idleBpm = model.bpm()
    model.setSpeed(100.0)
    repeat(250) { model.tick() }
    check(model.bpm() > idleBpm && model.bpm() < idleBpm + 8) { "speed must not dominate tempo" }

    model.setAccelerator(80.0)
    model.setBrake(true)
    model.setSteering(-270.0)
    model.setTurn(-1)
    model.setWiper(true)
    repeat(120) { model.tick() }
    check(model.accelerator() > 0.7 && model.pedalAvailable()) { "pedal controls bass" }
    check(model.brake() > 0.9 && model.steering() < -0.4) { "independent brake and steering" }
    check(model.turn() == -1 && model.wiper()) { "event and texture signals" }

    model.setBrake(null)
    model.setSteering(null)
    model.setWiper(null)
    repeat(120) { model.tick() }
    check(!model.brakeAvailable() && !model.steeringAvailable() && !model.wiperAvailable()) { "unknown signals remain unknown" }
    check(model.brake() < 0.01 && model.steering() > -0.01) { "lost signals fade to neutral" }

    model.setSpeed(-100.0)
    model.setAccelerator(Double.NaN)
    repeat(350) { model.tick() }
    check(model.speed() in 0.0..0.01) { "speed clamp" }
    check(model.bpm().isFinite() && model.intensity().isFinite()) { "finite output" }
    val gears = SoundModel()
    gears.setTransmission(true, null)
    gears.setSpeed(130.0)
    repeat(700) { gears.tick() }
    check(gears.gearStage() == 7 && gears.gearSource() == "VIRTUAL") { "virtual seven-stage upshift" }
    check(gears.layerMask() == 15 && gears.layerCount() == 4) { "speed layers did not enter" }
    gears.setTransmission(true, 3)
    check(gears.gearStage() == 3 && gears.gearSource() == "ACTUAL") { "vehicle gear must win" }
    gears.setTransmission(true, null)
    gears.setSpeed(0.0)
    repeat(700) { gears.tick() }
    check(gears.gearStage() == 1 && gears.layerMask() == 1) { "gears/layers did not leave" }
    gears.setTransmission(false, null)
    check(gears.gearStage() == 0 && gears.gearSource() == "NONE") { "P must not claim a drive gear" }
    println("SoundModel checks passed")
}
