#!/usr/bin/env python3
"""Create deterministic Java/Bedrock packs from the shared 32px phone sprite."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "assets" / "phone"
OUT = ROOT / "target"
SIZE = 32
pixels = [[(0, 0, 0, 0) for _ in range(SIZE)] for _ in range(SIZE)]


def rect(x0, y0, x1, y1, rgba):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            pixels[y][x] = rgba


# Dark frame, cyan edge, glass display and a simple gold auction gavel.
rect(6, 1, 25, 30, (13, 24, 34, 255))
rect(5, 3, 26, 28, (13, 24, 34, 255))
rect(7, 2, 24, 29, (60, 203, 207, 255))
rect(8, 3, 23, 28, (18, 40, 58, 255))
rect(9, 6, 22, 25, (20, 75, 92, 255))
rect(12, 3, 19, 4, (91, 125, 132, 255))
rect(14, 27, 17, 28, (81, 205, 201, 255))
rect(12, 10, 20, 12, (245, 189, 61, 255))
rect(14, 9, 18, 13, (255, 217, 94, 255))
rect(15, 13, 17, 21, (229, 156, 48, 255))
rect(11, 21, 20, 23, (249, 202, 80, 255))
rect(10, 24, 21, 24, (166, 113, 39, 255))


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


raw = b"".join(b"\0" + b"".join(bytes(px) for px in row) for row in pixels)
png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
(BASE / "smartphone.png").write_bytes(png)
OUT.mkdir(exist_ok=True)


def pack(folder, destination, texture_path):
    entries = {p.relative_to(folder).as_posix(): p.read_bytes() for p in folder.rglob("*") if p.is_file()}
    entries[texture_path] = png
    with ZipFile(destination, "w", ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)


pack(BASE / "java", OUT / "ecolife-phone-java.zip", "assets/ecolife/textures/item/smartphone.png")
pack(BASE / "bedrock", OUT / "ecolife-phone-bedrock.mcpack", "textures/items/smartphone.png")
