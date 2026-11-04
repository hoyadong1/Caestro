"""Audit the uncut PCM and speed trace for the continuous bass scenario."""

import argparse
import array
import csv
import math
import wave
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    parser.add_argument("trace", type=Path)
    args = parser.parse_args()
    with args.trace.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) > 200
    first = float(rows[0]["seconds"])
    with wave.open(str(args.wav), "rb") as source:
        assert source.getnchannels() == 2 and source.getframerate() == 44100
        frames = source.getnframes()
        pcm = array.array("h", source.readframes(frames))
    left = pcm[::2]
    assert left == pcm[1::2], "Non-bass stereo content in capture"
    duration = frames / 44100
    assert 78 < duration < 90, f"Unexpected PCM duration: {duration}"
    assert max(float(row["console_kmh"]) for row in rows) >= 85
    print(f"PCM duration {duration:.2f}s, {len(rows)} console updates, stereo bass only")
    times = [float(row["seconds"]) for row in rows]
    speeds = [float(row["console_kmh"]) for row in rows]
    assert all(b > a for a, b in zip(times, times[1:]))
    print(f"Largest command interval {max(b - a for a, b in zip(times, times[1:])):.2f}s; "
          f"largest speed step {max(abs(b - a) for a, b in zip(speeds, speeds[1:])):.0f} km/h")
    for start in range(0, 80, 5):
        segment = left[start * 44100:min((start + 5) * 44100, len(left))]
        rms = math.sqrt(sum(value * value for value in segment) / len(segment)) / 32768
        near = [row for row in rows if start <= float(row["seconds"]) - first < start + 5]
        speed = sum(float(row["console_kmh"]) for row in near) / len(near)
        phase = near[len(near) // 2]["phase"]
        print(f"{start:02d}-{start + 5:02d}s  {speed:5.1f} km/h  RMS {rms:.5f}  {phase}")
    # The one-second blocks must contain sound throughout the take.
    one_second = [left[i:i + 44100] for i in range(0, len(left) - 44100, 44100)]
    check = [sum(abs(value) for value in segment) / len(segment) for segment in one_second]
    assert min(check) > 100, "Silent one-second gap in the continuous performance"
    print(f"Minimum one-second mean absolute PCM: {min(check):.1f}")
    short = [left[i:i + 4410] for i in range(0, len(left) - 4410, 4410)]
    short_means = [sum(abs(value) for value in segment) / len(segment) for segment in short]
    minimum = min(short_means)
    at = short_means.index(minimum) / 10.0
    print(f"Minimum 100 ms mean absolute PCM: {minimum:.1f} at {at:.1f}s")
    sustained_minimum = min(short_means[10:])
    print(f"Minimum after startup second: {sustained_minimum:.1f}")
    assert sustained_minimum > 100, "Silent 100 ms gap after the startup second"


if __name__ == "__main__":
    main()
