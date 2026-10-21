#!/usr/bin/env python3
"""Independent reference encoder for Sony MDR frames.

Generates shared/protocol-fixtures/frames.json. The Swift (macos/) and Kotlin
(android/core) codecs are both tested against this output, so a third, minimal
implementation keeps either port from drifting. Pure standard library.

Usage: python3 tools/fixtures/generate_frames.py
"""
import json
import os

HEADER, TRAILER, ESCAPE = 0x3E, 0x3C, 0x3D
TYPES = {"ack": 0x01, "command1": 0x0C, "command2": 0x0E}


def escape(data):
    out = []
    for b in data:
        if b in (HEADER, TRAILER, ESCAPE):
            out += [ESCAPE, b & 0xEF]
        else:
            out.append(b)
    return out


def encode(type_name, seq, payload):
    body = [TYPES[type_name], seq] + list(len(payload).to_bytes(4, "big")) + payload
    checksum = sum(body) & 0xFF
    return [HEADER] + escape(body) + escape([checksum]) + [TRAILER]


def hx(data):
    return " ".join("%02x" % b for b in data)


def unhex(s):
    return list(bytes.fromhex(s.replace(" ", "")))


VALID = [
    ("init request", "command1", 0, "00 00"),
    ("ack seq 1", "ack", 1, ""),
    ("ack seq 0", "ack", 0, ""),
    ("battery get", "command1", 1, "22 00"),
    ("battery reply 80% charging", "command1", 0, "23 00 50 01"),
    ("init reply v2 (8 bytes)", "command1", 1, "01 00 02 00 00 00 00 00"),
    ("ncasm set noise cancelling", "command1", 0, "68 15 01 01 00 00 14"),
    ("eq custom write verified on XM6", "command1", 1, "58 04 a0 0a 09 08 07 06 05 04 03 06 06 06"),
    # Payload bytes equal to the delimiters must be escaped inside the frame.
    ("payload containing header, trailer and escape bytes", "command1", 0, "3e 3c 3d 00"),
    # 0x3e as the checksum itself: body 0c 00 00000001 + x; choose x so sum == 0x3e.
    ("checksum byte that needs escaping", "command1", 0, "%02x" % ((0x3E - (0x0C + 1)) & 0xFF)),
    ("device list get (table 2)", "command2", 0, "36 02"),
]

INVALID = [
    ("bad checksum", lambda: corrupt_checksum(encode("command1", 0, unhex("22 00")))),
    ("unknown message type 0x07", lambda: encode_raw_type(0x07, 0, unhex("22 00"))),
    ("length field larger than payload", lambda: wrong_length(unhex("22 00"))),
    ("missing trailer", lambda: encode("command1", 0, unhex("22 00"))[:-1]),
    ("too short", lambda: [HEADER, TRAILER]),
]


def corrupt_checksum(frame):
    frame = list(frame)
    frame[-2] = (frame[-2] + 1) & 0xFF
    if frame[-2] in (HEADER, TRAILER, ESCAPE):
        frame[-2] = (frame[-2] + 1) & 0xFF
    return frame


def encode_raw_type(code, seq, payload):
    body = [code, seq] + list(len(payload).to_bytes(4, "big")) + payload
    return [HEADER] + escape(body) + escape([sum(body) & 0xFF]) + [TRAILER]


def wrong_length(payload):
    body = [0x0C, 0] + list((len(payload) + 3).to_bytes(4, "big")) + payload
    return [HEADER] + escape(body) + escape([sum(body) & 0xFF]) + [TRAILER]


def main():
    valid = []
    for name, t, seq, payload in VALID:
        p = unhex(payload)
        valid.append({"name": name, "type": t, "seq": seq, "payload": hx(p), "frame": hx(encode(t, seq, p))})

    invalid = [{"name": name, "frame": hx(build())} for name, build in INVALID]

    a = encode("command1", 0, unhex("23 00 50 01"))
    b = encode("command1", 1, unhex("67 15 01 01 00 00 14"))
    streams = [
        {"name": "one frame split across three reads",
         "chunks": [hx(a[:3]), hx(a[3:7]), hx(a[7:])], "expectPayloads": ["23 00 50 01"]},
        {"name": "two frames in one read",
         "chunks": [hx(a + b)], "expectPayloads": ["23 00 50 01", "67 15 01 01 00 00 14"]},
        {"name": "garbage before header is skipped",
         "chunks": [hx([0x00, 0xFF, 0x12] + a)], "expectPayloads": ["23 00 50 01"]},
        {"name": "corrupt frame dropped, next one kept",
         "chunks": [hx(corrupt_checksum(a) + b)], "expectPayloads": ["67 15 01 01 00 00 14"]},
    ]

    out = {
        "schema": "xm-audio-studio.protocol-frames/1",
        "generator": "tools/fixtures/generate_frames.py",
        "valid": valid,
        "invalid": invalid,
        "streams": streams,
    }
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(root, "shared", "protocol-fixtures", "frames.json")
    with open(path, "w") as f:
        json.dump(out, f, indent=2)
        f.write("\n")
    print("wrote", path)


if __name__ == "__main__":
    main()
