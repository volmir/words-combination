#!/usr/bin/env python3
"""Restore the approved DB and prepare its pinned writer; downloads are opt-in."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.owned_hints_toolchain import load_lock, prepare_library, restore_database


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true', help='allow SHA-256-pinned artifact downloads')
    args = parser.parse_args()
    lock = load_lock()
    prepare_library(lock, allow_download=args.download)
    restore_database(ROOT / 'data', lock, allow_download=args.download)
    # Run the original rights, manifest and byte-exact rebuild gate as well.
    subprocess.run([sys.executable, str(ROOT / 'tools/build_owned_hints.py'), '--check'], check=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from error
