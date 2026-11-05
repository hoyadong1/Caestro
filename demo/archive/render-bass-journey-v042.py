"""Visualize one uncut app PCM take against the Virtual Console speed trace."""

import argparse
import csv
import math
import subprocess
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


W, H, FPS = 1280, 720, 12
BG = (8, 17, 29)
PANEL = (19, 42, 57)
PINK = (243, 148, 185)
TEAL = (149, 232, 221)
WHITE = (242, 248, 250)
MUTED = (140, 169, 185)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def load_trace(path):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) > 100, "Speed trace is incomplete"
    first = float(rows[0]["seconds"])
    trace = [(float(row["seconds"]) - first, float(row["console_kmh"]),
              row["gear_command"], row["phase"]) for row in rows]
    assert any(gear == "Drive" for _, _, gear, _ in trace)
    assert max(speed for _, speed, _, _ in trace) >= 85
    return trace


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    parser.add_argument("trace", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--poster", type=Path)
    args = parser.parse_args()
    with wave.open(str(args.wav), "rb") as source:
        assert source.getnchannels() == 2 and source.getframerate() == 44100
        duration = source.getnframes() / source.getframerate()
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2").reshape(-1, 2)
    assert np.array_equal(samples[:, 0], samples[:, 1]), "Capture contains non-bass stereo content"
    mono = samples[:, 0].astype(np.float32) / 32768.0
    trace = load_trace(args.trace)
    timeline = np.array([row[0] for row in trace], dtype=np.float64)
    speed = np.array([row[1] for row in trace], dtype=np.float64)
    trace_duration = timeline[-1]
    assert abs(duration - trace_duration) < 15, (duration, trace_duration)
    # Both clocks start around PLAY; scale minor emulator drift over the continuous take.
    speed_time = timeline * duration / trace_duration
    envelope = np.array([np.max(np.abs(mono[i:i + 441]))
                         for i in range(0, len(mono), 441)], dtype=np.float32)
    rms = np.array([math.sqrt(float(np.mean(mono[i:i + 22050] ** 2)))
                    for i in range(0, len(mono), 22050)], dtype=np.float32)
    fonts = {size: ImageFont.truetype(FONT, size) for size in (15, 18, 20, 23, 30, 40, 110)}

    encoder = subprocess.Popen([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{W}x{H}",
        "-framerate", str(FPS), "-i", "-", "-i", str(args.wav),
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "fast",
        "-crf", "22", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
        "-movflags", "+faststart", "-shortest", str(args.output)
    ], stdin=subprocess.PIPE)
    assert encoder.stdin is not None
    frames = math.ceil(duration * FPS)
    poster_frame = int(duration * 0.52 * FPS)
    for frame in range(frames):
        t = frame / FPS
        at = min(np.searchsorted(speed_time, t, side="right") - 1, len(trace) - 1)
        at = max(0, at)
        kmh = float(np.interp(t, speed_time, speed))
        phase = trace[at][3]
        gear = "D" if trace[at][2] == "Drive" else "P"
        energy = float(rms[min(int(t * 2), len(rms) - 1)])
        image = Image.new("RGB", (W, H), BG)
        draw = ImageDraw.Draw(image)
        draw.rounded_rectangle((32, 30, 1248, 690), radius=26, fill=PANEL)
        draw.text((64, 56), "DRIVE / SOUNDTRACK", font=fonts[23], fill=WHITE)
        draw.text((64, 90), "ONE CONTINUOUS TAKE  /  CIRCUIT BASS SOLO", font=fonts[15], fill=TEAL)
        draw.text((64, 157), f"{kmh:02.0f}", font=fonts[110], fill=WHITE)
        draw.text((245, 228), "km/h", font=fonts[23], fill=MUTED)
        draw.rounded_rectangle((64, 315, 120, 365), radius=10, fill=(47, 79, 91))
        draw.text((80, 320), gear, font=fonts[30], fill=WHITE)
        draw.text((137, 326), f"SELECTED {gear}", font=fonts[20], fill=TEAL)
        draw.text((64, 374), phase, font=fonts[18], fill=TEAL)
        draw.text((64, 414), "BASS RMS", font=fonts[18], fill=MUTED)
        draw.text((64, 439), f"{energy:.4f}", font=fonts[40], fill=PINK)
        draw.text((405, 163), "APP PCM  /  LAST 4 SECONDS", font=fonts[18], fill=MUTED)
        draw.rounded_rectangle((400, 200, 1198, 471), radius=14, fill=BG)
        center = 335
        for x in range(410, 1188, 3):
            offset = (x - 410) / (1188 - 410) * 4.0
            index = int((t - 4.0 + offset) * 100)
            value = float(envelope[index]) if 0 <= index < len(envelope) else 0.0
            height = min(115, int(math.sqrt(value) * 190))
            draw.line((x, center - height, x, center + height), fill=PINK, width=2)
        draw.text((64, 515), "SPEED PROFILE  /  ACCELERATE · CRUISE · COAST · BRAKE", font=fonts[18], fill=WHITE)
        graph = (64, 552, 1198, 640)
        draw.rounded_rectangle(graph, radius=12, fill=BG)
        points = [(int(72 + (tt / trace_duration) * 1118), int(630 - ss / 100.0 * 70))
                  for tt, ss in zip(timeline, speed)]
        draw.line(points, fill=(72, 96, 111), width=3, joint="curve")
        cutoff = max(1, np.searchsorted(speed_time, t))
        draw.line(points[:cutoff], fill=PINK, width=4, joint="curve")
        cursor_x = int(72 + (min(t, duration) / duration) * 1118)
        draw.line((cursor_x, 553, cursor_x, 638), fill=TEAL, width=2)
        draw.text((64, 655), "PLEOS SIMULATION · GEAR SELECTION P TO D / CURRENT GEAR P IN AVD · ONE APP PCM",
                  font=fonts[15], fill=MUTED)
        if args.poster and frame == poster_frame:
            image.save(args.poster)
        encoder.stdin.write(image.tobytes())
        if frame % (FPS * 15) == 0:
            print(f"Rendered {t:.0f}/{duration:.0f}s", flush=True)
    encoder.stdin.close()
    if encoder.wait() != 0:
        raise RuntimeError("Video encoder failed")
    print(f"Saved {args.output}: {duration:.2f}s continuous app PCM")


if __name__ == "__main__":
    main()
