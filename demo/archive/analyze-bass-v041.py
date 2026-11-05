"""Measure the settled portion of the app's captured bass-only PCM."""

import argparse
import array
import math
import wave
from pathlib import Path


SPEEDS = (0, 10, 20, 35, 55, 80, 110, 130)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenes", type=Path)
    args = parser.parse_args()
    for speed in SPEEDS:
        with wave.open(str(args.scenes / f"bass-{speed:03}.wav"), "rb") as source:
            assert source.getnchannels() == 2 and source.getframerate() == 44100
            source.setpos(3 * 44100)
            data = array.array("h", source.readframes(source.getnframes() - source.tell()))
        left = data[::2]
        right = data[1::2]
        assert left == right, f"{speed} km/h contains non-bass stereo content"
        count = len(left)
        rms = math.sqrt(sum(value * value for value in left) / count) / 32768
        edge = math.sqrt(sum((left[i] - left[i - 1]) ** 2 for i in range(1, count)) / (count - 1)) / 32768
        print(f"{speed:3d} km/h  {count / 44100:.2f} s  RMS {rms:.5f}  edge {edge:.5f}")


if __name__ == "__main__":
    main()
