#!/usr/bin/env python3
"""Adds classes.dex to the aapt2 output and writes an aligned, unsigned APK.

resources.arsc is stored uncompressed on a 4-byte boundary, which Android 11
requires for apps that target API 30. Everything else is deflated.
"""
import hashlib
import struct
import sys
import zipfile
import zlib

base_apk, dex, out = sys.argv[1:4]
STORED = {"resources.arsc"}
FIXED_DATE = (2026, 1, 1, 0, 0, 0)

with zipfile.ZipFile(base_apk) as src, open(out, "wb") as raw:
    entries = [(i.filename, src.read(i.filename)) for i in src.infolist()]
    # An old D8 release hashed too few bytes for the header's SHA-1 field, so redo both
    # header sums. With a correct D8 this writes the same values again.
    code = bytearray(open(dex, "rb").read())
    code[12:32] = hashlib.sha1(code[32:]).digest()
    code[8:12] = struct.pack("<I", zlib.adler32(code[12:]))
    entries.append(("classes.dex", bytes(code)))
    with zipfile.ZipFile(raw, "w") as dst:
        for name, data in entries:
            info = zipfile.ZipInfo(name, FIXED_DATE)
            info.external_attr = 0o644 << 16
            if name in STORED:
                info.compress_type = zipfile.ZIP_STORED
                # Local header is 30 bytes + name + extra; pad extra so the data starts aligned.
                data_start = raw.tell() + 30 + len(name.encode())
                info.extra = b"\0" * (-data_start % 4)
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
            dst.writestr(info, data)
