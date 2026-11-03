"""Copy debug app PCM captures from Android user 10 without text-mode conversion."""

import subprocess
import sys
from pathlib import Path


ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
SCENES = (
    "01-circuit", "02-speed", "03-brake", "04-steering", "05-turn", "06-pulse"
)


def main():
    destination = Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=True)
    for scene in SCENES:
        pcm = subprocess.check_output([
            str(ADB), "exec-out", "run-as", "com.drivesoundtrack.app", "--user", "10",
            "cat", f"files/{scene}.wav"
        ])
        (destination / f"{scene}.wav").write_bytes(pcm)
        print(f"{scene}: {len(pcm)} bytes")


if __name__ == "__main__":
    main()
