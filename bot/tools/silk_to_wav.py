#!/usr/bin/env python3
"""微信 SILK → WAV（供声音复刻）。依赖: pip install pilk"""
from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: silk_to_wav.py <in.silk> <out.wav> [sample_rate]", file=sys.stderr)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    rate = int(sys.argv[3]) if len(sys.argv) > 3 else 24000
    if not src.is_file():
        print(f"missing input: {src}", file=sys.stderr)
        return 1
    try:
        import pilk
    except ImportError:
        print("pilk not installed; run: pip install pilk", file=sys.stderr)
        return 3

    data = src.read_bytes()
    original = data
    # 去掉微信常见 0x02 前缀（偶发叠多层）
    while data and data[0] == 0x02 and len(data) > 1 and data[1:3] == b"#!":
        data = data[1:]
    if not data.startswith(b"#!SILK"):
        idx = data.find(b"#!SILK")
        if idx > 0:
            data = data[idx:]
    if data != original:
        src = dst.with_suffix(".stripped.silk")
        src.write_bytes(data)

    pilk.silk_to_wav(str(src), str(dst), rate)
    if not dst.is_file() or dst.stat().st_size < 44:
        print("wav output empty", file=sys.stderr)
        return 4
    print(f"ok bytes={dst.stat().st_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
