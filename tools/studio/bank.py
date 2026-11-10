"""Compresses the instrument bank rendered by StudioBankExport (app/build/studio-bank) into docs/studio/bank:
one mono MP3 per instrument (24 kHz, 48 kbit/s) and bank.json. Needs ffmpeg (on Windows, run through WSL).

    STUDIO_BANK=1 gradlew :app:testDebugUnitTest --tests "*StudioBankExport*"
    python tools/studio/bank.py
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
SRC = os.path.join(ROOT, 'app', 'build', 'studio-bank')
DST = os.path.join(ROOT, 'docs', 'studio', 'bank')


def wsl(path):
    p = path.replace(os.sep, '/')
    return '/mnt/' + p[0].lower() + p[2:] if len(p) > 1 and p[1] == ':' else p


def main():
    os.makedirs(DST, exist_ok=True)
    for name in sorted(os.listdir(SRC)):
        if not name.endswith('.wav'):
            continue
        src, dst = os.path.join(SRC, name), os.path.join(DST, name[:-4] + '.mp3')
        args = ['-v', 'error', '-y', '-i', src, '-ac', '1', '-ar', '24000', '-c:a', 'libmp3lame', '-b:a', '48k', dst]
        if os.name == 'nt':
            args = [wsl(a) if a in (src, dst) else a for a in args]
            cmd = ['wsl', '-e', 'ffmpeg'] + args
        else:
            cmd = ['ffmpeg'] + args
        subprocess.run(cmd, check=True)
        print(name, os.path.getsize(dst))
    shutil.copy(os.path.join(SRC, 'bank.json'), os.path.join(DST, 'bank.json'))


if __name__ == '__main__':
    sys.exit(main())
