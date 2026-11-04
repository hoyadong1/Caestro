"""Copy the running app's debug-only bass PCM captures without text conversion."""

import subprocess
import sys
from pathlib import Path


ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
SPEEDS = (0, 10, 20, 35, 55, 80, 110, 130)


def main():
    destination = Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=True)
    for speed in SPEEDS:
        name = f"bass-{speed:03}"
        pcm = subprocess.check_output([
            str(ADB), "exec-out", "run-as", "com.drivesoundtrack.app", "--user", "10",
            "cat", f"files/{name}.wav"
        ])
        (destination / f"{name}.wav").write_bytes(pcm)
        print(f"{speed:3d} km/h: {len(pcm)} bytes")


if __name__ == "__main__":
    main()
