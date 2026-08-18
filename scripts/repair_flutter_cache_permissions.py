#!/usr/bin/env python3
"""Restore executable bits lost from Flutter SDK/cache archives."""
from pathlib import Path
import os
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: repair_flutter_cache_permissions.py FLUTTER_ROOT")
    cache = Path(sys.argv[1]).resolve() / "bin" / "cache"
    if not cache.is_dir():
        raise SystemExit(f"Flutter cache not found: {cache}")

    repaired = 0
    for path in cache.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            with path.open("rb") as stream:
                magic = stream.read(4)
        except OSError:
            continue
        if magic == b"\x7fELF" or magic.startswith(b"#!"):
            mode = path.stat().st_mode
            if mode & 0o111 == 0:
                path.chmod(mode | 0o111)
                repaired += 1
    print(f"Flutter cache executable permissions repaired: {repaired}")


if __name__ == "__main__":
    main()
