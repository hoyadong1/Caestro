"""Match a Pleos screenrecord to PCM captured from the same AudioTrack session.

The emulator renders far slower than real time, so multiplying video PTS by one
constant would make speed labels disagree with the music. sync.csv records the
actual PCM duration beside a host stopwatch throughout the take.
"""

import argparse
import bisect
import csv
import json
import os
from pathlib import Path
import subprocess
import wave


def run(*args, capture=False):
    result = subprocess.run(args, check=True, text=True, capture_output=capture)
    return result.stdout if capture else None


def interpolate(points, value):
    xs = [point[0] for point in points]
    at = bisect.bisect_left(xs, value)
    if at <= 0:
        left, right = points[:2]
    elif at >= len(points):
        left, right = points[-2:]
    else:
        left, right = points[at - 1 : at + 1]
    return left[1] + (value - left[0]) * (right[1] - left[1]) / (right[0] - left[0])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("take", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--video-clock-offset", type=float, default=1.0,
                        help="Approximate wall seconds minus screenrecord PTS")
    parser.add_argument("--anchors", type=Path,
                        help="Optional CSV with video_seconds,audio_seconds read from the app speed display")
    args = parser.parse_args()
    take = args.take.resolve()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    meta = json.loads((take / "sync-meta.json").read_text(encoding="utf-8-sig"))
    with (take / "sync.csv").open(encoding="utf-8-sig", newline="") as file:
        rows = list(csv.DictReader(file))
    audio_to_wall = [(0.0, meta["play_command_wall"])] + [
        (float(row["audio_seconds"]), float(row["wall_seconds"])) for row in rows
    ]
    with wave.open(str(take / "journey.wav"), "rb") as file:
        audio_duration = file.getnframes() / file.getframerate()
        assert file.getnchannels() == 2 and file.getframerate() == 44100
    audio_to_video = None
    if args.anchors:
        with args.anchors.open(encoding="utf-8-sig", newline="") as file:
            anchors = list(csv.DictReader(file))
        audio_to_video = sorted((float(row["audio_seconds"]), float(row["video_seconds"]))
                            for row in anchors)
        assert len(audio_to_video) >= 2

    source = take / "screen.mp4"
    pts = [float(row) for row in run(
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
        "frame=best_effort_timestamp_time", "-of", "csv=p=0", str(source),
        capture=True).splitlines() if row.strip()]
    assert len(pts) > 100
    scratch = output.parent.parent / "build" / "v062-video-sync"
    source_frames = scratch / "source-frames"
    aligned_frames = scratch / "aligned-frames"
    source_frames.mkdir(parents=True, exist_ok=True)
    aligned_frames.mkdir(parents=True, exist_ok=True)
    if len(list(source_frames.glob("*.png"))) != len(pts):
        run("ffmpeg", "-y", "-v", "error", "-i", str(source), "-vsync", "0",
            "-vf", "crop=840:604:427:45", str(source_frames / "%05d.png"))
    assert len(list(source_frames.glob("*.png"))) == len(pts)

    fps = 12
    frame_count = round(audio_duration * fps)
    frame_times = []
    for frame in range(frame_count):
        audio_time = (frame + 0.5) / fps
        wall_time = interpolate(audio_to_wall, audio_time)
        video_time = (interpolate(audio_to_video, audio_time) if audio_to_video else
                      wall_time - meta["screen_start_wall"] - args.video_clock_offset)
        video_time = max(0.0, video_time)
        index = min(len(pts) - 1, max(0, bisect.bisect_right(pts, video_time) - 1))
        destination = aligned_frames / f"{frame + 1:05d}.png"
        if destination.exists():
            destination.unlink()
        os.link(source_frames / f"{index + 1:05d}.png", destination)
        frame_times.append({"audio": audio_time, "wall": wall_time,
                            "video_pts": pts[index], "source_frame": index + 1})
    run("ffmpeg", "-y", "-v", "error", "-framerate", str(fps), "-i",
        str(aligned_frames / "%05d.png"), "-i", str(take / "journey.wav"),
        "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt",
        "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags",
        "+faststart", str(output))
    (scratch / "long-demo-sync.json").write_text(json.dumps({
        "source": str(source), "audio": str(take / "journey.wav"),
        "video_clock_offset": args.video_clock_offset,
        "anchors": str(args.anchors) if args.anchors else None,
        "audio_duration": audio_duration, "frame_times": frame_times,
    }, indent=2), encoding="utf-8")
    print(f"{output}: {audio_duration:.2f}s app PCM, {len(pts)} source frames, "
          f"{frame_count} aligned frames")


if __name__ == "__main__":
    main()
