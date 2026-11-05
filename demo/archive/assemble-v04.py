"""Join Pleos scene captures with PCM recorded from the running app's AudioTrack."""

import argparse
import subprocess
import wave
from pathlib import Path


SCENES = [
    ("01-circuit", "CIRCUIT  /  STATIONARY"),
    ("02-speed", "15 TO 35 KM/H  /  RHYTHM LAYER"),
    ("03-cruise", "55 TO 70 KM/H  /  DENSER DRUMS"),
    ("04-brake", "BRAKE + DECELERATION  /  CHORDS"),
    ("05-steering", "LEFT STEERING  /  CONTINUOUS MELODY"),
    ("06-left", "LEFT INDICATOR  /  DESCENDING CUE"),
    ("07-right", "RIGHT INDICATOR  /  ASCENDING CUE"),
    ("08-combined", "SPEED + TURN + BRAKE  /  COMBINED"),
    ("09-pulse", "PULSE  /  SECOND SOUNDSCAPE"),
]
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
VIDEO_START_SECONDS = 2.5  # screenrecord began two seconds before PLAY


def run(*args):
    subprocess.run(args, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenes", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rendered = []
    for index, (name, caption) in enumerate(SCENES, 1):
        wav = args.scenes / f"{name}.wav"
        webm = args.scenes / f"{name}.webm"
        with wave.open(str(wav), "rb") as source:
            assert source.getnchannels() == 2 and source.getframerate() == 44100
            duration = source.getnframes() / source.getframerate()
        clip = args.scenes / f"scene-{index:02}.mp4"
        caption_filter = (
            "drawbox=x=22:y=232:w=545:h=105:color=0x08111c@0.82:t=fill,"
            f"drawtext=fontfile={FONT}:text='PLEOS V2.1  /  APP AUDIO':"
            "x=43:y=247:fontsize=25:fontcolor=white,"
            f"drawtext=fontfile={FONT}:text='P GEAR  -  VIRTUAL CONSOLE':"
            "x=43:y=282:fontsize=16:fontcolor=0x95e8dd,"
            f"drawtext=fontfile={FONT}:text='{caption}':"
            "x=43:y=307:fontsize=17:fontcolor=white"
        )
        audio_filter = f"afade=t=in:d=0.08,afade=t=out:st={duration - 0.15:.3f}:d=0.15"
        run(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-ss", str(VIDEO_START_SECONDS), "-i", str(webm), "-i", str(wav),
            "-t", f"{duration:.3f}", "-map", "0:v:0", "-map", "1:a:0",
            "-vf", caption_filter, "-af", audio_filter,
            "-r", "12", "-c:v", "libx264", "-preset", "fast", "-crf", "23",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", str(clip)
        )
        rendered.append(clip)
        print(f"{name}: {duration:.2f}s of app PCM", flush=True)

    playlist = args.scenes / "concat.txt"
    playlist.write_text("".join(f"file '{clip}'\n" for clip in rendered))
    run(
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "concat", "-safe", "0", "-i", str(playlist),
        "-c", "copy", "-movflags", "+faststart", str(args.output)
    )
    print(f"Saved {args.output}")


if __name__ == "__main__":
    main()
