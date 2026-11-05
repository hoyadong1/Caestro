"""Present eight speed cases using screen capture and the app's isolated bass PCM."""

import argparse
import array
import math
import subprocess
import wave
from pathlib import Path


SPEEDS = (0, 10, 20, 35, 55, 80, 110, 130)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
VIDEO_LEAD = 2.5
SETTLE = 3.0


def run(*args):
    subprocess.run(args, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenes", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rendered = []
    for index, speed in enumerate(SPEEDS, 1):
        name = f"bass-{speed:03}"
        wav = args.scenes / f"{name}.wav"
        webm = args.scenes / f"{name}.webm"
        with wave.open(str(wav), "rb") as source:
            assert source.getnchannels() == 2 and source.getframerate() == 44100
            duration = source.getnframes() / source.getframerate() - SETTLE
            source.setpos(3 * 44100)
            samples = array.array("h", source.readframes(source.getnframes() - source.tell()))[::2]
            rms = math.sqrt(sum(value * value for value in samples) / len(samples)) / 32768
        assert duration > 6, f"{name} capture is too short"
        clip = args.scenes / f"{name}.mp4"
        panel = (
            "drawbox=x=22:y=232:w=545:h=105:color=0x08111c@0.82:t=fill,"
            f"drawtext=fontfile={FONT}:text='BASS ONLY  /  CIRCUIT':"
            "x=43:y=247:fontsize=25:fontcolor=white,"
            f"drawtext=fontfile={FONT}:text='{speed} KM/H  /  SETTLED SPEED':"
            "x=43:y=285:fontsize=20:fontcolor=0x95e8dd,"
            "drawbox=x=640:y=68:w=1260:h=907:color=0x09131f:t=fill,"
            f"drawtext=fontfile={FONT}:text='CIRCUIT  /  BASS SOLO':"
            "x=700:y=135:fontsize=39:fontcolor=white,"
            f"drawtext=fontfile={FONT}:text='APP PCM WAVEFORM  /  {speed} KM/H':"
            "x=700:y=205:fontsize=23:fontcolor=0x95e8dd,"
            f"drawtext=fontfile={FONT}:text='RMS  {rms:.5f}':"
            "x=700:y=770:fontsize=30:fontcolor=white,"
            f"drawtext=fontfile={FONT}:text='SPEED CHANGES LEVEL + HARMONICS + DECAY':"
            "x=700:y=825:fontsize=19:fontcolor=0x95e8dd"
        )
        audio_filter = f"afade=t=in:d=0.08,afade=t=out:st={duration - 0.15:.3f}:d=0.15"
        filters = (
            f"[0:v]{panel}[base];"
            "[1:a]asplit=2[listen][visual];"
            "[visual]showwaves=s=1120x420:mode=cline:rate=12:colors=0xF394B9:scale=sqrt:draw=full[wave];"
            "[base][wave]overlay=x=700:y=275:shortest=1[vout];"
            f"[listen]{audio_filter}[aout]"
        )
        run(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-ss", str(VIDEO_LEAD + SETTLE), "-i", str(webm),
            "-ss", str(SETTLE), "-i", str(wav),
            "-t", f"{duration:.3f}",
            "-filter_complex", filters, "-map", "[vout]", "-map", "[aout]",
            "-r", "12", "-c:v", "libx264", "-preset", "fast", "-crf", "23",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", str(clip)
        )
        rendered.append(clip)
        print(f"{speed:3d} km/h: {duration:.2f}s settled bass", flush=True)
    playlist = args.scenes / "bass-concat.txt"
    playlist.write_text("".join(f"file '{clip}'\n" for clip in rendered))
    run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "concat", "-safe", "0", "-i", str(playlist),
        "-c", "copy", "-movflags", "+faststart", str(args.output))
    print(f"Saved {args.output}")


if __name__ == "__main__":
    main()
