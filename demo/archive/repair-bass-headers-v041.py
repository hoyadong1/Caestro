"""Repair headers copied before PcmCapture.close; leave captured PCM bytes unchanged."""

import argparse
import struct
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenes", type=Path)
    args = parser.parse_args()
    for path in sorted(args.scenes.glob("bass-*.wav")):
        data = bytearray(path.read_bytes())
        assert len(data) > 44 and (len(data) - 44) % 4 == 0
        if data[:4] == b"RIFF":
            print(f"{path.name}: header already valid")
            continue
        assert data[:44] == b"\0" * 44, f"{path.name}: unexpected header"
        pcm_size = len(data) - 44
        data[:44] = struct.pack(
            "<4sI4s4sIHHIIHH4sI",
            b"RIFF", pcm_size + 36, b"WAVE", b"fmt ", 16,
            1, 2, 44100, 44100 * 4, 4, 16, b"data", pcm_size
        )
        path.write_bytes(data)
        print(f"{path.name}: finalized {pcm_size} captured PCM bytes")


if __name__ == "__main__":
    main()
