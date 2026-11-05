"""Copy one continuous app PCM capture from the Pleos debug app."""

import subprocess
import sys
from pathlib import Path


ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"


def main():
    destination = Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=True)
    pcm = subprocess.check_output([
        str(ADB), "exec-out", "run-as", "com.drivesoundtrack.app", "--user", "10",
        "cat", "files/live-demo.wav"
    ])
    (destination / "journey.wav").write_bytes(pcm)
    print(f"Captured {len(pcm)} bytes of uninterrupted app PCM")


if __name__ == "__main__":
    main()
