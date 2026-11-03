#!/usr/bin/env python3
"""Sends video clips to the TV for Free My TV's screensaver (a fireplace, an aquarium...).

    python3 tools/videos.py 192.168.1.50 fireplace.mp4 aquarium.mp4 Clips/
    python3 tools/videos.py fireplace.mp4          # the TV adb is already connected to
    python3 tools/videos.py 192.168.1.50 --list    # what's on the TV
    python3 tools/videos.py 192.168.1.50 --clear   # removes them all

The clips go to the app's own folder on the TV (Android/data/io.freemytv/files/videos: adb can write there, and the
app needs no permission) and play from there, muted, in name order, each up to 10 minutes. MP4 (H.264 or H.265) plays
on every TV; WebM (VP9) and MKV on most. 1080p is plenty: a 4K clip is larger and some TVs can't decode it smoothly.
"""
import os
import shutil
import subprocess
import sys

FOLDER = '/sdcard/Android/data/io.freemytv/files/videos'
KINDS = ('.mp4', '.m4v', '.webm', '.mkv', '.mov', '.ts')
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way


def adb(*args):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True)


def videos(paths):
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, dirs, files in os.walk(p):
                dirs[:] = sorted(d for d in dirs if not d.startswith('.'))
                out += [os.path.join(root, f) for f in sorted(files)
                        if not f.startswith('.') and f.lower().endswith(KINDS)]
        elif os.path.isfile(p) and p.lower().endswith(KINDS):
            out.append(p)
        else:
            print(f'Skipped {p}: not a video or a folder.')
    return out


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    if not os.path.exists(args[0]) and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]
    listed = adb('shell', 'pm', 'list', 'packages', 'io.freemytv')
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    if 'package:io.freemytv' not in listed.stdout.split():
        print('Free My TV is not installed on this TV.')
        return 1
    if args == ['--list']:
        r = adb('shell', 'ls', '-l', FOLDER)
        print(r.stdout.strip() or 'No clips on the TV.')
        return 0
    if args == ['--clear']:
        adb('shell', 'rm', '-rf', FOLDER)
        print('Removed the clips from the TV.')
        return 0
    found = videos(args)
    if not found:
        print('No videos to send.')
        return 1
    if adb('shell', 'mkdir', '-p', FOLDER).returncode != 0:
        print(f'Could not create {FOLDER} on the TV.')
        return 1
    for path in found:
        name = os.path.basename(path).replace(' ', '_')
        print(f'Sending {path}…')
        r = adb('push', path, f'{FOLDER}/{name}')
        if r.returncode != 0:
            print(f'Could not send {path}: {(r.stdout + r.stderr).strip()}')
            return 1
    print(f'{len(found)} clip(s) on the TV. The next screensaver plays them.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
