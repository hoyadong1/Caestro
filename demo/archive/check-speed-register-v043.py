"""Measure the continuous speed tone in the app's uncut bass PCM."""

import argparse
import math
import wave
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    args = parser.parse_args()
    with wave.open(str(args.wav), "rb") as source:
        assert source.getframerate() == 44100 and source.getnchannels() == 2
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
    mono = samples[::2].astype(np.float64) / 32768.0
    rate = 44100
    measured_tones = []
    for kmh, start, end in [(0, 0.8, 1.8), (40, 6.0, 7.0),
                            (80, 11.0, 12.0), (130, 17.2, 18.8)]:
        piece = mono[int(start * rate):int(end * rate)]
        if len(piece) < rate * 0.8:
            raise AssertionError(f"Missing {kmh} km/h segment")
        spectrum = np.abs(np.fft.rfft(piece * np.hanning(len(piece))))
        bins = np.fft.rfftfreq(len(piece), 1 / rate)
        expected = 55.0 * 2.0 ** (2.0 * kmh / 130.0)
        band = (bins >= expected - 8) & (bins <= expected + 8)
        measured = float(bins[np.flatnonzero(band)[np.argmax(spectrum[band])]])
        baseline = np.median(spectrum[(bins >= 45) & (bins <= 250)])
        prominence = float(np.max(spectrum[band]) / max(baseline, 1e-9))
        print(f"{kmh:3d} km/h  expected {expected:6.1f} Hz  "
              f"measured {measured:6.1f} Hz  prominence {prominence:.1f}x")
        assert math.isclose(measured, expected, abs_tol=12.0)
        assert prominence > 5.0, "Speed tone is buried under the musical bass"
        measured_tones.append(measured)
    assert all(a < b for a, b in zip(measured_tones, measured_tones[1:]))


if __name__ == "__main__":
    main()
