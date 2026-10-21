#!/usr/bin/env python3
"""Real-hardware write test for the WH-1000XM6 (macOS).

Uses the app's probe mode (`--probe`) to send the exact payloads our builders produce
(the same bytes as shared/protocol-fixtures/commands.json), checks that the headphones
confirm each write with a notification, and restores every original value afterwards,
including the active EQ preset and the "Custom" slot curve.

Requirements: XM6 on and connected to this Mac for audio; XM Audio Studio quit (it
would hold the single control channel); Sony Sound Connect closed on the phone.
Run:  python3 tools/hardware/xm6_write_test.py [--app "macos/.build/XM Audio Studio.app"]

Never sends firmware, factory-reset, pairing or source-switch commands.
"""
import argparse
import os
import re
import subprocess
import sys
import time

LOG = os.path.expanduser("~/Library/Application Support/XM Audio Studio/probe.log")


def run_probe(app, payloads, timeout=120):
    if os.path.exists(LOG):
        os.remove(LOG)
    subprocess.run(["open", "-n", app, "--args", "--probe", *payloads], check=True)
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1)
        if os.path.exists(LOG):
            text = open(LOG).read()
            if "done" in text or "ERROR" in text:
                if "ERROR" in text:
                    sys.exit("Probe failed: " + text.strip().splitlines()[-1] +
                             "\nAre the headphones on, connected to this Mac, and Sound Connect closed?")
                return text.splitlines()
    sys.exit("Probe timed out")


def rx(lines, prefix):
    """Last received T1 payload starting with prefix (hex string), as a list of ints."""
    found = None
    for line in lines:
        m = re.match(r"RX\[T1\] (.*)$", line)
        if m and m.group(1).startswith(prefix):
            found = [int(b, 16) for b in m.group(1).split()]
    return found


def hx(values):
    return " ".join("%02x" % v for v in values)


def segments(lines):
    """[(tx_payload, [rx payloads until next TX])] for the scripted part of a probe."""
    out, current = [], None
    for line in lines:
        if line.startswith("TX["):
            current = (line.split("] ", 1)[1], [])
            out.append(current)
        elif line.startswith("RX[T1] ") and current is not None:
            current[1].append(line.split("] ", 1)[1])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(os.path.dirname(__file__), "../../macos/.build/XM Audio Studio.app"))
    app = os.path.abspath(ap.parse_args().app)

    print("1/3 Reading the current state (read-only)…")
    lines = run_probe(app, ["50 04 01", "5a 04"])
    model = next((l for l in lines if l.startswith("RX[T1] 05 01")), None)
    firmware = next((l for l in lines if l.startswith("RX[T1] 05 02")), None)
    amb = rx(lines, "67 19") or rx(lines, "69 19")
    eq = rx(lines, "57 04") or rx(lines, "59 04")
    bgm = rx(lines, "e7 09")
    pause = rx(lines, "f7 01")
    apo = rx(lines, "27 05")
    stc = rx(lines, "f7 0c")
    for name, value in [("ambient 0x19", amb), ("eq", eq), ("bgm", bgm), ("pause", pause), ("auto power off", apo), ("speak-to-chat", stc)]:
        if value is None:
            sys.exit("Missing %s in the initial read; aborting without writing." % name)
    def text(line):
        if not line:
            return "?"
        b = [int(x, 16) for x in line.split("] ", 1)[1].split()]
        return bytes(b[3:3 + b[2]]).decode("ascii", "replace")
    print("   model:", text(model), " firmware:", text(firmware))
    print("   ambient:", hx(amb), " eq:", hx(eq))
    for extra in ("51 04", "5b 04"):
        v = rx(lines, extra)
        print("   %s reply:" % extra, hx(v) if v else "none")

    preset, bands = eq[2], eq[4:4 + eq[3]]
    on, mode, voice, level = amb[3], amb[4], amb[5], max(1, amb[6])

    print("2/3 Reading the Custom slot (select Custom, then restore preset 0x%02x)…" % preset)
    lines = run_probe(app, ["58 04 a0 00", "58 04 %02x 00" % preset])
    custom = None
    for tx, replies in segments(lines):
        if tx == "58 04 a0 00":
            for r in replies:
                v = [int(x, 16) for x in r.split()]
                if v[:3] == [0x59, 0x04, 0xa0]:
                    custom = v[4:4 + v[3]]
    if custom is None:
        sys.exit("The Custom slot did not report its curve; stopping before any band write.")
    print("   custom slot:", custom)

    test_bands = [min(6, b + 1) if i in (0, 9) else b for i, b in enumerate(custom)]
    toggle = lambda b: 0x01 if b == 0x00 else 0x00
    writes = [
        ("NC on, level %d" % level, "68 17 01 01 00 00 %02x" % level, "69 19"),
        ("Ambient, level 10", "68 17 01 01 01 00 0a", "69 19"),
        ("Restore noise control", "68 17 01 %02x %02x %02x %02x" % (on, mode, voice, level), "69 19"),
        ("EQ preset Heavy", "58 04 30 00", "59 04 30"),
        ("EQ custom curve", "58 04 a0 0a " + hx([b + 6 for b in test_bands]), "59 04 a0"),
        ("Restore Custom slot", "58 04 a0 0a " + hx([b + 6 for b in custom]), "59 04 a0"),
        ("Restore preset 0x%02x" % preset, "58 04 %02x 00" % preset, "59 04 %02x" % preset),
        ("BGM toggle", "e8 09 %02x %02x" % (toggle(bgm[2]), bgm[3]), "e9 09"),
        ("Restore BGM", "e8 09 %02x %02x" % (bgm[2], bgm[3]), "e9 09"),
        ("Pause-when-off toggle", "f8 01 %02x" % toggle(pause[2]), "f9 01"),
        ("Restore pause-when-off", "f8 01 %02x" % pause[2], "f9 01"),
        ("Auto power off toggle", "28 05 %02x 00" % (0x11 if apo[2] == 0x10 else 0x10), "29 05"),
        ("Restore auto power off", "28 05 %02x 00" % apo[2], "29 05"),
        ("Speak-to-Chat toggle", "f8 0c %02x 01" % toggle(stc[2]), "f9 0c"),
        ("Restore Speak-to-Chat", "f8 0c %02x 01" % stc[2], "f9 0c"),
    ]
    print("3/3 Writing and restoring (%d steps)…" % len(writes))
    lines = run_probe(app, [w[1] for w in writes], timeout=180)
    results = segments(lines)
    failures = 0
    print("\n%-26s %-40s %s" % ("Step", "Sent", "Headphones confirmed"))
    for (name, payload, expect), (tx, replies) in zip(writes, results):
        ok = any(r.startswith(expect) for r in replies)
        failures += 0 if ok else 1
        echo = next((r for r in replies if r.startswith(expect)), "-")
        print("%-26s %-40s %s %s" % (name, payload, "OK " if ok else "NO ", echo))
    print("\n%d/%d writes confirmed by the headphones." % (len(writes) - failures, len(writes)))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
