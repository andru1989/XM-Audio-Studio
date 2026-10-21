> Research snapshot from 2026-10-21 (Etapa 0). Paths under `scratchpad/` or `refs/` refer to temporary
> clones that are not part of this repository.

# Sony MDR Bluetooth protocol: research report for XM Audio Studio

Date: 2026-10-21. Scope: the control protocol that Sony "Headphones Connect" / "Sound Connect" uses over Bluetooth RFCOMM (and BLE GATT on newer devices), for WH-1000XM6 first, then XM5, XM4, XM3, XM2 and MDR-1000X.

All paths below are relative to
`/private/tmp/claude-501/-Users-andru1989-Documents-Proyectos-XM-Audio-Studio/8d43e2e4-55d7-46a4-b7cd-015f337907a2/scratchpad/refs/`.
`Lnn` means line numbers at the HEAD listed in section 0. Anything I could not confirm from code plus at least one hardware capture or hardware-verified note is marked **UNVERIFIED**.

Conventions: all payload bytes are hex. "payload" means the bytes *inside* the frame (after the 4-byte length), where `payload[0]` is the command/opcode and `payload[1]` is almost always the "inquired type" (sub-type).

---

## 0. Sources inspected

| Local dir | Upstream | HEAD (date) | SPDX license | Language | What it contributes |
|---|---|---|---|---|---|
| `mos9527-shc` | github.com/mos9527/SonyHeadphonesClient | `8f4808c` (2026-10-20) | **MIT** (`LICENSE`, "Copyright (c) 2026 mos9527, Amr Satrio and other contributors") | C++20 | The authoritative source here. `libmdr` holds full V1/V2 × Table1/Table2 command tables, **generated from Sound Connect iOS J2ObjC metadata** (`libmdr/include/mdr/ProtocolV2.hpp` L4, `tooling/ida/README.md`). Includes real packet captures (XM3 FW 4.5.2, WF-1000XM5 FW 6.1.0, WF-LC900 FW 2.0.3) in `tests/` and per-device notes in `docs/device-support/`. |
| `plutoberth-shc` | github.com/Plutoberth/SonyHeadphonesClient | `5620e8e` (2025-07-19), archived | **MIT** ("Copyright (c) 2020 Nir Harel, Mor Gal, Sem Visscher, jimzrt, guilhermealbm, and other contributors") | C++17 | Historical. V1 framing and the XM3 NC/ASM + VPT setter only. |
| `sony-device-center` | github.com/marconvcm/sony-device-center | `0bf9822` (2026-09-27) | **MIT**. The `LICENSE` file is Plutoberth's text, unchanged, because this is a fork. | C++20 / Qt6 | Hand-written V1/V2 drivers. Has hardware-verified notes: XM3, **XM4 FW 3.0.1**, **XM5 FW 2.3.1**, **XM6 10-band EQ on FW 3.1.5** (PR #44), and XM6 EQ packets taken from a btsnoop capture used as test fixtures. Vendors `third_party/nlohmann` (MIT). |
| `ibatra-shc` | github.com/ibatra/sony-headphones-client | `f870a82` (2026-01-04) | **MIT**, declared in `package.json` L14 and README only. There is **no LICENSE file** and no copyright line. | Rust / Tauri / Svelte | Small. Includes XM6 NC packets taken from an HCI capture (`src-tauri/src/protocol/commands.rs` L129-148). Has several known bugs (see §13). |
| `xm6-macos-controller` | github.com/shellingtonshreyas/xm6-macos-controller | `1d2e60d` (2026-09-09) | **GPL-3.0** (README L4, L260; `LICENSE` is GPL v3. It does not say "or later", so treat it as GPL-3.0-only unless headers say otherwise) | Swift | An XM6 macOS app. Uses NC inquired type **0x19**, EQ type 0x04, DSEE, volume. |
| `xm6-control` (our base) | n/a (Rui Martins) | `af6d89b` (2026-09-12) | **MIT** ("Copyright (c) 2026 Rui Martins") | Swift | Our starting point. Comments say its byte layouts were "read directly out of Gadgetbridge's ... source" (see the licensing note in §14). |
| `gadgetbridge` (extra cross-check) | codeberg Freeyourgadget/Gadgetbridge | `0735529` | **AGPL-3.0-or-later** | Java | Used **as a cross-reference only**, never as a source. |

---

## 1. Key facts

1. **Framing** (identical in all repos): `3E | escape( type, seq, len32BE, payload, checksum ) | 3C`. The checksum is the 8-bit sum of the *unescaped* type, seq, length and payload. Escaping: `3C→3D 2C`, `3D→3D 2D`, `3E→3D 2E` (i.e. `3D, b & 0xEF`).
2. **Two protocol families. Each is chosen by the RFCOMM SDP UUID, not negotiated in-band**:
   - V1 (`96CC203E-5068-46AD-B32D-E316F5E069BA`): MDR-1000X, XM2, XM3, **XM4**.
   - V2 (`956C7B26-D49A-4BA8-B03F-B17D393CB6E2`): **XM5, XM6**.

   Each family has two command tables:
   - **Table 1** (THMSGV*x*T1) uses frame type **0x0C** (`DATA_MDR`).
   - **Table 2** (THMSGV*x*T2) uses frame type **0x0E** (`DATA_MDR_NO2`).

   V1 and V2 reuse opcodes with *different* meanings and polarities. The most dangerous case: V2 `0x22` is battery GET, but V1 `0x22` is **POWER OFF**.
3. **Identification** works the same way on both families (Table 1, 0x0C):
   - `00 00`: protocol info
   - `02 00`: capability info
   - `04 01`: model name
   - `04 02`: firmware version
   - `04 03`: series + colour
   - `06 00`: support-function list
   - On V2 only, also send `06 00` on 0x0E to get the Table-2 support functions.

   **Feature selection must be driven by the support-function list.** Do not use the model name for this.
4. **NC/ASM inquired types 0x17 and 0x19.** Both are V2 Table 1 `NCASM_*` types.
   - `0x17` = `MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS`. Seven bytes. Used by XM5 / WF-1000XM5 (hardware captures and verified code).
   - `0x19` = the same plus **"NA" = Noise Adaptation**. Nine bytes. The two extra bytes are an auto-ambient on/off and an adaptive sensitivity (standard/high/low).
   - The XM6 uses **0x19**, per three independent XM6 sources (mos9527, xm6-macos-controller, ibatra capture). Our `xm6-control` asks for 0x15/0x17 and **rejects 9-byte 0x19 replies and notifications**.
5. **XM6 equalizer**:
   - Uses inquired type **0x04** (`PRESET_EQ_AND_ERRORCODE`). XM6 ignores 0x00.
   - **10 bands, no Clear Bass.**
   - Wire value **0–12**, neutral **6**, UI range **−6…+6**. Encoding: `wire = gain + 6`.
   - Presets: Off 0x00, Heavy 0x30, Clear 0x31, Hard 0x32, Soft 0x33, Custom 0xA0, user settings 0xA1+.
   - Band centres 31/63/125/250/500/1k/2k/4k/8k/16k Hz are what two clients display. They have not been read from an XM6 `EQEBB_RET_EXTENDED_INFO` → **UNVERIFIED**.
   - **XM3/XM4/XM5** use 6 bytes: Clear Bass + 5 bands at 400/1k/2.5k/6.3k/16k Hz. Encoding is `wire = gain + 10` (−10…+10). The 400/1k/2.5k/6.3k/16k frequencies come from a real XM3 capture.
6. **Licenses.** mos9527, Plutoberth, sony-device-center, ibatra and xm6-control are MIT: code may be copied into an MIT project if the copyright and permission notice is kept. ibatra has no LICENSE file or copyright holder, so copying from it is still risky. xm6-macos-controller (GPL-3.0) and Gadgetbridge (AGPL-3.0) are **reference only**.

---

## 2. Transport

| Item | Value | Evidence |
|---|---|---|
| RFCOMM SDP service UUID, V1 (XM4 and older) | `96CC203E-5068-46AD-B32D-E316F5E069BA` | `mos9527-shc/libmdr/include/mdr-c/Base.h` L50-51; `plutoberth-shc/Client/Constants.h` L11 |
| RFCOMM SDP service UUID, V2 (XM5 and newer) | `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` | `Base.h` L48-49; `xm6-macos-controller/Sources/SonyMacApp/Services/SonyProtocol.swift` L29-32 |
| BLE GATT service UUID (XM5 and newer, always V2) | `5B833E20-6BC7-4802-8E9A-723CECA4BD8F` | `Base.h` L52-53; `Headphones.h` L520-523 |
| iOS External Accessory protocol strings | V1 `jp.co.sony.songpal.mdr.link`, V2 `jp.co.sony.songpal.mdr.link2` | `mos9527-shc/libmdr-bt/src/iOS/ConnectionIOS.mm` L14-15 |
| Fallback RFCOMM channel | 9. Used only if the SDP lookup fails. **UNVERIFIED** in general. | `xm6-macos-controller/.../SonyProtocol.swift` L39 |
| Max write / read size | 2048 bytes per write ("matches Sound Connect's IapSession"). Larger packets may be chunked by Sony. **UNVERIFIED**. | `ConnectionIOS.mm` L16-18; `mos9527-shc/libmdr/include/mdr/Command.hpp` L13-15; `plutoberth-shc/Client/Constants.h` L5 and `CommandSerializer.cpp` L127-131 |

In "auto" mode the mos9527 client tries V2 first and then V1 (`client/Client.cpp` L1116-1126, L1062-1073). For Android, open an RFCOMM socket with `createRfcommSocketToServiceRecord(UUID)` using the same UUIDs. For macOS, use `IOBluetoothDevice getServiceRecordForUUID` (`libmdr-bt/src/MacOS/ConnectionMacOS.mm` L71-83).

---

## 3. Frame format

### 3.1 Layout

```
3E  ESCAPE( TYPE(1) SEQ(1) LEN(4, big-endian, length of UNescaped payload) PAYLOAD(LEN) CHECKSUM(1) )  3C
```

- **Start marker** `0x3E` ('>') and **end marker** `0x3C` ('<'). Source: `mos9527-shc/libmdr/include/mdr/Command.hpp` L10-11, L18-26.
- **Checksum**: the low 8 bits of the sum of TYPE, SEQ, the four LEN bytes and the PAYLOAD, all **unescaped**, before escaping. Sources: `libmdr/src/Command.cpp` L6-12, L74-96, L123-128; `plutoberth-shc/Client/CommandSerializer.cpp` L87-118.
- **Escaping** applies to every byte between the markers, including the checksum:

| Raw byte | Escaped as |
|---|---|
| `3C` | `3D 2C` |
| `3D` | `3D 2D` |
| `3E` | `3D 2E` |

  Equivalently: escaped = `3D, b & 0xEF`, and unescape with `b | 0x10`. Sources: `Command.hpp` L6-9; `Command.cpp` L14-72; `xm6-control/.../SonyMessage.swift` L26-30, L90-119.

  An escape byte followed by anything else is malformed: mos9527 returns empty, Plutoberth throws (`Command.cpp` L62-63).
- Because the end marker is always escaped inside a frame, the first raw `3C` after a `3E` ends that frame. Scanning for it is safe (`xm6-control/.../FrameParser.swift` L6-10; mos9527 `Headphones.cpp` L237-241).
- Minimum unescaped inner length is 7: type, seq, 4 length bytes, checksum. mos9527 rejects the frame if LEN does not equal the actual payload length (`Command.cpp` L109-134).

Verified example from a capture, `tests/WH-1000XM3-4.5.2/*CONNECT_RET_PROTOCOL_INFO*`:

```
3E 0C 00 00 00 00 04 01 00 40 10 61 3C
```

Here the type is `0C`, seq `00`, length `4`, payload `01 00 40 10`, and the checksum is `0x61` (0x0C+0x04+0x01+0x40+0x10).

A real escape inside a payload (XM3 `EQEBB_RET_EXTENDED_INFO`) is `... 01 3D 2E 80 ...`, which decodes to `01 3E 80`, i.e. 16000 Hz.

### 3.2 Data types (byte 1 of the frame)

From `mos9527-shc/libmdr/include/mdr/Protocol.hpp` L37-56; the same list is in `plutoberth-shc/Client/Constants.h` L22-41.

| Value | Name | Used for |
|---|---|---|
| 0x00 | DATA | (unused by headphones) |
| **0x01** | **ACK** | Acknowledgement. Payload is empty, LEN = 0. |
| 0x02 | DATA_MC_NO1 | |
| 0x09 | DATA_ICD | |
| 0x0A | DATA_EV | |
| **0x0C** | **DATA_MDR** | **Table 1** commands (V1T1 / V2T1) |
| 0x0D | DATA_COMMON | |
| **0x0E** | **DATA_MDR_NO2** | **Table 2** commands (V1T2 / V2T2) |
| 0x10 / 0x12 / 0x19 / 0x1A / 0x1C / 0x1D / 0x1E | SHOT, SHOT_MC_NO1, SHOT_ICD, SHOT_EV, SHOT_MDR, SHOT_COMMON, SHOT_MDR_NO2 | not used by these clients |
| 0x2D | LARGE_DATA_COMMON | not used (probably the FW-update transport, **UNVERIFIED**) |

The table → data type mapping is confirmed by the generated traits: all 317 V1T1 and 624 V2T1 payloads are `DATA_MDR`, and all 48 V1T2 and 307 V2T2 payloads are `DATA_MDR_NO2` (`libmdr/include/mdr/Generated/Protocol*Traits.hpp`).

### 3.3 Sequence numbers and ACK (stop-and-wait, alternating bit)

- The sequence number is one byte that alternates between 0 and 1.
- **The host sends** data with its current `seq`. The device replies with `ACK` whose seq is `1 - seq`. The host adopts that value as its next seq. A stale ACK, whose seq is not `1 - current`, is ignored. Sources: mos9527 `libmdr/src/Headphones.cpp` L316-326; `xm6-control/.../HeadphonesController.swift` L339-345; Plutoberth `BluetoothWrapper.cpp` L28, L96.
- **The device sends** DATA with seq `s`. The host must reply with `ACK` seq `1 - s`, *before* processing the data. Sources: mos9527 `Headphones.cpp` L186-203 and L311-314; xm6-control L353-358 (`ackSeq = seq ^ 1`).
- ACK frames, verified from the XM3 capture:
  - `3E 01 01 00 00 00 00 02 3C` (seq 1)
  - `3E 01 00 00 00 00 00 01 3C` (seq 0)
- **Retries**: mos9527 resends the same frame up to **10 times with a 1000 ms ACK timeout**. The default reply timeout is 5000 ms. See `libmdr/src/Details.hpp` L410-412 and L514-531.
- **Ordering quirks** seen on hardware (mos9527 `docs/device-support/WH-1000XM3.md`, "Notes"; `WF-LC900.md`):
  - The device often sends the `*_RET_*` reply **before** the ACK for the `*_GET_*`. Do not discard data that arrives while you wait for an ACK.
  - Notifications that follow a SET arrive after the ACK.
  - The device can push an unsolicited `PLAY_NTFY_PARAM` even **before** `CONNECT_RET_PROTOCOL_INFO`. Accept and ACK it.
  - Some GETs are ACKed but never answered. Examples on XM3: `INSTRUCTION_GUIDE`, `COMMON_GET_UPSCALING_EFFECT`. Never block forever on a RET.

### 3.4 Example frames

Computed with `scratchpad/tools/frame.py`. The ACK frames match the capture.

| Purpose | Type | Seq | Payload | Full frame |
|---|---|---|---|---|
| Protocol info | 0C | 0 | `00 00` | `3E 0C 00 00 00 00 02 00 00 0E 3C` |
| Capability info | 0C | 1 | `02 00` | `3E 0C 01 00 00 00 02 02 00 11 3C` |
| Model name | 0C | 0 | `04 01` | `3E 0C 00 00 00 00 02 04 01 13 3C` |
| Firmware | 0C | 1 | `04 02` | `3E 0C 01 00 00 00 02 04 02 15 3C` |
| Series/colour | 0C | 0 | `04 03` | `3E 0C 00 00 00 00 02 04 03 15 3C` |
| Support functions T1 | 0C | 1 | `06 00` | `3E 0C 01 00 00 00 02 06 00 15 3C` |
| Support functions T2 (V2) | **0E** | 0 | `06 00` | `3E 0E 00 00 00 00 02 06 00 16 3C` |
| V2 battery | 0C | 0 | `22 00` | `3E 0C 00 00 00 00 02 22 00 30 3C` |
| V2 NC/ASM (XM6) | 0C | 1 | `66 19` | `3E 0C 01 00 00 00 02 66 19 8E 3C` |
| V2 EQ param (XM6) | 0C | 0 | `56 04` | `3E 0C 00 00 00 00 02 56 04 68 3C` |
| V2 EQ capability, English | 0C | 1 | `50 04 01` | `3E 0C 01 00 00 00 03 50 04 01 65 3C` |
| V1 battery | 0C | 0 | `10 00` | `3E 0C 00 00 00 00 02 10 00 1E 3C` |

### 3.5 Common payload encodings

- Strings and arrays are prefixed with a **1-byte count**. Exceptions: `MDRPrefixedString16BE` uses a 2-byte prefix, and 17-byte MAC fields are fixed-size ASCII "AA:BB:CC:DD:EE:FF". Source: `Protocol.hpp` L384-411, L424-455, L471-486.
- V2 multi-byte integers are big-endian (`Int16BE`, `Int24BE` for Bluetooth Class-of-Device, `Int32BE`).
- **Boolean polarity differs between families and between enums. This is a big source of bugs.**

| Enum | Values | Evidence |
|---|---|---|
| V2 `OnOffSettingValue` (speak-to-chat, pause-on-remove, BGM, cinema, voice guidance, head gesture …) | **ON = 0x00, OFF = 0x01** | `ProtocolV2.hpp` L54-59 |
| V2 `EnableDisable` / `CommonStatus` | ENABLE = 0x00, DISABLE = 0x01 | `ProtocolV2.hpp` L7-19 |
| V2 `NcAsmOnOffValue` (NC total effect, auto-ASM) | **OFF = 0x00, ON = 0x01** | `ProtocolV2T1.hpp` L1157-1162 |
| V2 `UpscalingTypeAutoOff` (DSEE) | OFF = 0x00, AUTO = 0x01 | `ProtocolV2T1.hpp` (enum `UpscalingTypeAutoOff`) |
| V2 `GsSettingValue` (general settings) | ON = 0x00, OFF = 0x01 | `ProtocolV2T1.hpp` L957-962 |
| **All V1** on/off enums | OFF = 0x00, ON = 0x01 | `ProtocolV1T1.hpp` (`SmartTalkingModeSettingValue`, `ControlByWearingSettingValue`, `CommonOnOffSettingValue`, `UpscalingSettingValue`) |

---

## 4. Protocol families, tables and handshake

### 4.1 Naming

The generated headers carry Sony's J2ObjC class names: `THMSGV1T1*`, `THMSGV1T2*`, `THMSGV2T1*`, `THMSGV2T2*` (for example `// THMSGV2T1NcAsmGetParam` in `ProtocolV2T1.hpp`; 623 occurrences). "V1/V2" is the protocol family and "T1/T2" is the command table. Expanding "THMSG" as Tandem Message is a guess (**UNVERIFIED**).

| Table | Frame type | Families | Contents |
|---|---|---|---|
| T1 | 0x0C | V1, V2 | connect/identity, common, power/battery, update, EQ, NC/ASM, optimizer, alert, playback, general settings, audio (DSEE/BGM/connection mode), system (STC, wearing, assignable keys, APO on V1) |
| T2 | 0x0E | V1, V2 | peripheral (paired devices, multipoint, source switch), voice guidance; V2 adds safe listening, LE Audio, party, T2-system (wearing status, auto volume, …), T2 power |

### 4.2 How the family and tables are chosen

There is no in-band version negotiation.

1. **The family is chosen by which SDP UUID you connect to** (§2). mos9527 hard-binds the family at construction time (`libmdr/src/Headphones.cpp` L1403-1409).
2. The host sends `CONNECT_GET_PROTOCOL_INFO` (`00 00`) on 0x0C. The device replies `CONNECT_RET_PROTOCOL_INFO` (`01 ...`):

   | Family | RET layout | Example | Evidence |
   |---|---|---|---|
   | V1 | `01 00 <ver Int16BE>`. 4 bytes, no table flags. | XM3 FW 4.5.2: `01 00 40 10` (version 0x4010) | `ProtocolV1T1.hpp` L3108-3115; capture `tests/WH-1000XM3-4.5.2/...000001...` |
   | V2 | `01 00 <ver Int32BE> <table1: 00=supported> <table2: 00=supported>`. 8 bytes. | WF-1000XM5 FW 6.1.0: `01 00 03 00 30 18 00 00` | `ProtocolV2T1.hpp` L3747-3760; `HeadphonesV2.cpp` L1014-1030; capture `tests/WF-1000XM5-6.1.0/...000002...` |

   Payload length 4 vs 8 is a reliable way to tell V1 from V2. `xm6-control/.../Commands.swift` L22-31 already does this. In mos9527, a reply whose first payload byte is `0x01` before any other traffic is always treated as protocol info (`Headphones.cpp` L188-190).
3. **V2**: if `table1` = 0x00, Table 1 is usable. If `table2` = 0x00, query the T2 support list too (`HeadphonesV2.cpp` L17-45). **V1**: there are no table flags. mos9527 enables T2 if the T1 support list contains `PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT` (0x38) or `VOICE_GUIDANCE` (0x39) (`HeadphonesV1.cpp` L6-11).
4. Sound Connect caches the support functions per MAC address, together with `CONNECT_RET_CAPABILITY_INFO`'s `capabilityCounter` and `uniqueId` (comment in `HeadphonesV2.cpp` L33). Caching is optional.

### 4.3 Opcode structure

Within a feature group the low nibble is standard:

| Low nibble | Meaning |
|---|---|
| +0 | GET_CAPABILITY |
| +1 | RET_CAPABILITY |
| +2 | GET_STATUS |
| +3 | RET_STATUS |
| +4 | SET_STATUS |
| +5 | NTFY_STATUS |
| +6 | GET_PARAM |
| +7 | RET_PARAM |
| +8 | SET_PARAM |
| +9 | NTFY_PARAM |
| +A | GET_EXT |
| +B | RET_EXT |
| +C | SET_EXT |
| +D | NTFY_EXT |

**V2 Table 1 commands** (`ProtocolV2T1.hpp` L376-530):

| Range | Group | Notes |
|---|---|---|
| 0x00-0x07 | CONNECT | GET/RET PROTOCOL_INFO 00/01, CAPABILITY_INFO 02/03, DEVICE_INFO 04/05, SUPPORT_FUNCTION 06/07 |
| 0x10-0x19 | COMMON | GET_CAPABILITY 10, GET_STATUS 12 (codec, upscaling effect, connection status…), SET_PARAM 18, NTFY_PARAM 19 |
| 0x20-0x29 | POWER | battery 22/23/25, power off 24, APO 26/27/28/29 |
| 0x30-0x3F | UPDT | firmware update. **Do not implement.** |
| 0x40-0x4D | LEA | LE Audio |
| **0x50-0x5B** | **EQEBB** | 50/51 capability, 52/53/55 status, 56/57/58/59 param, 5A/5B extended info (band frequencies) |
| **0x60-0x69** | **NCASM** | 60/61 capability, 62/63/64/65 status, 66/67/68/69 param |
| 0x70-0x7B | SENSE | adaptive sound control |
| 0x80-0x89 | OPT | NC optimizer, sound-field optimization |
| 0x90-0x99 | ALERT | 94 SET_STATUS (subscribe), 98 SET_PARAM (answer), 99 NTFY |
| 0xA0-0xA9 | PLAY | volume, play/pause, metadata |
| 0xB0-0xB9 | SAR_AUTO_PLAY | |
| 0xC4 / 0xC9 | LOG | |
| 0xD0-0xD9 | GENERAL_SETTING | touch panel, multipoint, sidetone… self-describing (§10) |
| 0xE0-0xE9 | AUDIO | connection mode, DSEE, BGM, cinema, voice contents, sound-leakage |
| 0xF0-0xFD | SYSTEM | pause-on-remove, STC, assignable keys, auto volume, quick access, head gesture |

**V2 Table 2 commands** (`ProtocolV2T2.hpp` L23 onward):

| Range | Group |
|---|---|
| 0x06/0x07 | CONNECT_GET/RET_SUPPORT_FUNCTION |
| 0x20-0x29 | POWER (T2) |
| **0x30-0x3D** | **PERI** (paired devices, multipoint, source switch) |
| **0x40-0x48** | **VOICE_GUIDANCE** |
| 0x53-0x5B | SAFE_LISTENING |
| 0x60-0x69 | LEA |
| 0x70-0x7C | PARTY |
| 0xF0-0xFD | SYSTEM (T2) |

**V1 Table 1 commands** (`ProtocolV1T1.hpp` L212-348). These differ from V2:

| Opcode | Command | Note |
|---|---|---|
| 0x00-0x07 | CONNECT | same as V2 |
| **0x10 / 0x11 / 0x13** | COMMON_GET / RET / NTFY_BATTERY_LEVEL | |
| 0x14 / 0x15 / 0x17 | UPSCALING_EFFECT | |
| 0x18 / 0x19 / 0x1B | AUDIO_CODEC | |
| 0x1C / 0x1D | BLUETOOTH_DEVICE_INFO | |
| **0x22** | **COMMON_SET_POWER_OFF** | Never send a V2 battery GET to a V1 device. See `sony-device-center/libs/sony-protocol/src/ProtocolV1.cpp` L12-13 and `docs/device-matrix.md` (Protocol V1 section). |
| 0x24-0x27 | CONNECTION_STATUS | |
| 0x28 / 0x29 | CONCIERGE_DATA | |
| 0x2E / 0x2F | LINK_CONTROL | |
| 0x34-0x37 | UPDT | |
| **0x40-0x49** | **VPT** (surround / sound position) | |
| 0x50-0x5B | EQEBB | |
| 0x60-0x69 | NCASM | |
| 0x70-0x74 | SENSE | |
| 0x80-0x89 | OPT | |
| 0x90-0x99 | ALERT | |
| 0xA0-0xA9 | PLAY | |
| 0xB0-0xBD | SPORTS | |
| 0xC4 / 0xC9 | LOG | |
| 0xD0-0xD9 | GS | |
| 0xE0-0xE9 | AUDIO | |
| 0xF0-0xFD | SYSTEM (APO, wearing, STC, assignable keys) | |
| 0xFF | TEST_COMMAND | |

**V1 Table 2 commands** (`ProtocolV1T2.hpp` L17-41):

| Range | Group |
|---|---|
| 0x30-0x3D | PERIPHERAL |
| 0x40-0x49 | VOICE_GUIDANCE |

---

## 5. Device identification

The request and reply bytes are the same on V1 and V2, all in Table 1 (0x0C).

| Request payload | Meaning | Reply layout | Real example |
|---|---|---|---|
| `00 00` | CONNECT_GET_PROTOCOL_INFO | see §4.2 | XM3 `01 00 40 10`; WF-XM5 `01 00 03 00 30 18 00 00` |
| `02 00` | CONNECT_GET_CAPABILITY_INFO | `03 00 <capabilityCounter u8> <len u8> <uniqueId ASCII>`. uniqueId was the BT MAC string in both captures. | XM3 `03 00 52 11 "AA:BB:CC:00:00:01"` (scrubbed) |
| `04 01` | GET_DEVICE_INFO MODEL_NAME | `05 01 <len> <ASCII model>` | XM3 `05 01 0A 57 48 2D 31 30 30 30 58 4D 33` ("WH-1000XM3"). For XM6 expect `05 01 0A 57 48 2D 31 30 30 30 58 4D 36`. |
| `04 02` | GET_DEVICE_INFO FW_VERSION | `05 02 <len> <ASCII version>` | XM3 `05 02 05 "4.5.2"`; WF-XM5 `05 02 05 "6.1.0"` |
| `04 03` | GET_DEVICE_INFO SERIES_AND_COLOR_INFO | `05 03 <ModelSeries> <ModelColor>` | XM3 `05 03 30 01` (PREMIUM, BLACK); WF-XM5 `05 03 30 03` (PREMIUM, SILVER) |
| `04 04` | GET_DEVICE_INFO INSTRUCTION_GUIDE | (XM3: ACKed, never answered) | |
| `06 00` on **0x0C** | CONNECT_GET_SUPPORT_FUNCTION (T1) | **V2**: `07 00 <count> (<FunctionType> <priority>)×count`. **V1**: `07 00 <count> <FunctionType>×count`, with no priority byte. | XM3 V1: `07 00 13 11 C1 12 13 14 30 71 62 81 42 41 51 A1 E1 E2 D1 D2 F4 39`; WF-XM5 V2: `07 00 2E 10 FF 12 FF … 6B 08 …` |
| `06 00` on **0x0E** (V2) | T2 support functions | same pair layout | WF-XM5 `07 00 0B 42 3D 2D …` (escaped) |

Struct evidence:
- `ProtocolV2T1.hpp` L3617-3773 (Connect structs), L613-620 (`DeviceInfoType`), L1108-1122 (`ModelSeries`).
- `ProtocolV2.hpp` L21-52 (`ModelColor`), L246-250 and L312-316 (`SupportFunction {FunctionType, priority}`).
- V1: `ProtocolV1T1.hpp` L409-417, L3151-3158 (`RetSupportFunction` = `MDRPodArray<FunctionType>`), L4843-4851.

mos9527 sends these at startup (`HeadphonesV2.cpp` L15-46; `HeadphonesV1.cpp` L34-59).

**ModelSeries** (V2): 0x00 NO_SERIES, 0x10 EXTRA_BASS, 0x11 ULT_POWER_SOUND, 0x20 HEAR, **0x30 PREMIUM** (the 1000X line), 0x40 SPORTS, 0x50 CASUAL, 0x60 LINK_BUDS, 0x70 NECKBAND, 0x80 LINKPOD, 0x90 GAMING. V1 has only 0x00-0x50.

**ModelColor**: 0x00 DEFAULT, 0x01 BLACK, 0x02 WHITE, 0x03 SILVER, 0x04 RED, 0x05 BLUE, 0x06 PINK, 0x07 YELLOW, 0x08 GREEN, 0x09 GRAY, 0x0A GOLD, 0x0B CREAM, 0x0C ORANGE, 0x0D BROWN, 0x0E VIOLET. Values 0x11-0x1E are "_I" variants of the same colours (meaning **UNVERIFIED**).

Other identity-adjacent GETs:
- V2 `12 02`: COMMON_GET_STATUS AUDIO_CODEC → `13 02 <codec>`. Codec values: 01 SBC, 02 AAC, 10 LDAC, 20 aptX, 21 aptX HD, 30 LC3. WF-XM5 replied `13 02 02` (AAC).
- V1 `18 00` → `19 00 <codec>`.
- V2 `12 09`: DISPLAY_FW_VERSION (exists in the enum, unused).
- V1 `1C <type>`: BLUETOOTH_DEVICE_INFO.

Sources: `ProtocolV2T1.hpp` L229-240, L539-552, L3507-3515; `sony-device-center/.../ProtocolV2.cpp` L263-275.

### 5.1 V2 Table-1 FunctionType values (subset that matters)

Full list in `mos9527-shc/libmdr/include/mdr/ProtocolV2.hpp` L104-245.

| Code | FunctionType | Drives |
|---|---|---|
| 0x12 | CODEC_INDICATOR | codec GET `12 02` |
| 0x13 | UPSCALING_INDICATOR | |
| 0x20 | BATTERY_LEVEL_INDICATOR | battery type 0x00 |
| 0x21 / 0x22 | LEFT_RIGHT / CRADLE_BATTERY_LEVEL_INDICATOR | battery types 0x01 / 0x02 |
| 0x23 | POWER_OFF | `24 03 01` |
| 0x24 / 0x25 | AUTO_POWER_OFF / …_WITH_WEARING_DETECTION | APO types 0x04 / 0x05 |
| 0x28 / 0x29 / 0x2A | BATTERY / LR / CRADLE …_WITH_THRESHOLD | battery types 0x08 / 0x09 / 0x0A |
| **0x50** | PRESET_EQ | EQ type 0x00 |
| 0x52 | PRESET_EQ_NON_CUSTOMIZABLE | EQ type 0x02 |
| 0x53 | PRESET_EQ_AND_ULT_MODE | EQ type 0x03 |
| **0x57** | **PRESET_EQ_AND_ERRORCODE** | **EQ type 0x04 (XM6)** |
| 0x55 | CUSTOM_EQ | EQ type 0x31 |
| 0x61-0x6D | NC/ASM variants | see §7.2 mapping |
| 0x69 | AMBIENT_SOUND_CONTROL_MODE_SELECT | NC/AMB button assignment, type 0x30 |
| 0x70 / 0x71 | AUTO_NCASM / ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION | |
| 0x80-0x83 | NC_OPTIMIZER_* / SOUND_FIELD_OPTIMIZATION | |
| 0x90 | FIXED_MESSAGE | alerts |
| 0xA1-0xA4 | PLAYBACK_CONTROLLER_* | volume / play |
| 0xC1 / 0xC2 | ACTION_LOG / TIME_SERIES_OPERATIONLOG notifier | |
| 0xD1-0xD4 | GENERAL_SETTING_1..4 | |
| 0xE1 / 0xE3 / 0xE7 | CONNECTION_MODE_* | |
| 0xE2 | UPSCALING_AUTO_OFF | DSEE |
| 0xE4 / 0xEB | BGM_MODE_SMALL_MIDDLE_LARGE(_AND_ERRORCODE) | |
| 0xE5 | UPMIX_CINEMA | |
| 0xE6 | LISTENING_OPTION | |
| 0xE8 | VOICE_CONTENTS | |
| 0xE9 | SOUND_LEAKAGE_REDUCTION | |
| 0xF1 | PLAYBACK_CONTROL_BY_WEARING_REMOVING_HEADPHONE_ON_OFF | pause when removed |
| 0xF2 / **0xFC** | SMART_TALKING_MODE_TYPE1 / **TYPE2** | speak-to-chat |
| 0xF3 / 0xFE | ASSIGNABLE_SETTING(_WITH_LIMITATION) | |
| 0xF6 | WEARING_STATUS_DETECTOR | |
| 0xFA | AUTO_VOLUME | |
| 0xFD | QUICK_ACCESS | |
| 0xFF | HEAD_GESTURE_ON_OFF_TRAINING | |

### 5.2 V2 Table-2 FunctionType values

From `ProtocolV2.hpp` L255-310:

| Code | FunctionType |
|---|---|
| 0x30 | PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT |
| 0x31 | SOURCE_SWITCH_CONTROL |
| 0x32 / 0x33 | PAIRING_DEVICE_MANAGEMENT_WITH_BLUETOOTH_CLASS_OF_DEVICE_CLASSIC_BT / _LE |
| 0x40-0x48 | VOICE_GUIDANCE_* (0x42 adds volume; 0x43 is a fixed 5-step volume) |
| 0x50-0x55 | SAFE_LISTENING_* |
| 0x60-0x64 | LE Audio |
| 0xF0 | WEARING_STATUS_CHECKER |
| 0xF3 / 0xF4 | AUTO_VOLUME_* |
| 0xF6 | WEARING_POSITION |
| 0xF8 | LINK_AUTO_SWITCH_FOR_HEADSETS |

### 5.3 V1 FunctionType values

From `ProtocolV1T1.hpp` L500-541:

| Code | FunctionType |
|---|---|
| 0x11 | BATTERY_LEVEL |
| 0x12 | UPSCALING_INDICATOR |
| 0x13 | CODEC_INDICATOR |
| 0x15 | LEFT_RIGHT_BATTERY_LEVEL |
| 0x18 | CRADLE_BATTERY_LEVEL |
| 0x21 | POWER_OFF |
| 0x30 | FW_UPDATE |
| 0x38 | PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT |
| 0x39 | VOICE_GUIDANCE |
| 0x41 | VPT |
| 0x42 | SOUND_POSITION |
| 0x51 | PRESET_EQ |
| 0x52 | EBB |
| 0x53 | PRESET_EQ_NONCUSTOMIZABLE |
| 0x61 | NOISE_CANCELLING |
| 0x62 | NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE |
| 0x63 | AMBIENT_SOUND_MODE |
| 0x71 | AUTO_NC_ASM |
| 0x81 | NC_OPTIMIZER |
| 0xA1 | PLAYBACK_CONTROLLER |
| 0xC1 | ACTION_LOG_NOTIFIER |
| 0xD1-0xD3 | GENERAL_SETTING1..3 |
| 0xE1 | CONNECTION_MODE |
| 0xE2 | UPSCALING |
| 0xF1 | VIBRATOR |
| 0xF2 | POWER_SAVING_MODE |
| 0xF3 | CONTROL_BY_WEARING |
| 0xF4 | AUTO_POWER_OFF |
| 0xF5 | SMART_TALKING_MODE |
| 0xF6 | ASSIGNABLE_SETTINGS |

The XM3 capture decodes to BATTERY_LEVEL, ACTION_LOG, UPSCALING_IND, CODEC_IND, BLE_SETUP, FW_UPDATE, AUTO_NC_ASM, NC+ASM, NC_OPTIMIZER, SOUND_POSITION, VPT, PRESET_EQ, PLAYBACK, CONNECTION_MODE, UPSCALING, GS1, GS2, AUTO_POWER_OFF, VOICE_GUIDANCE. This matches `docs/device-support/WH-1000XM3.md` ("Advertised functions").

---

## 6. Battery and charging

### V2 (POWER group, Table 1)

- GET: `22 <PowerInquiredType>`. RET: `23 …`. NTFY: `25 …`.
- Choose the type from the support functions (`HeadphonesV2.cpp` L310-338). `PowerInquiredType` is at `ProtocolV2T1.hpp` L1324-1343.

| Type | Function | RET/NTFY layout after `23 <type>` | Example |
|---|---|---|---|
| 0x00 BATTERY | 0x20 | `<level 0-100> <charging>` | over-ear |
| 0x01 LEFT_RIGHT_BATTERY | 0x21 | `<L> <Lchg> <R> <Rchg>` | TWS |
| 0x02 CRADLE_BATTERY | 0x22 | `<level> <chg>` | case |
| 0x08 BATTERY_WITH_THRESHOLD | 0x28 | `<level> <chg> <threshold>` | |
| 0x09 LR_BATTERY_WITH_THRESHOLD | 0x29 | `<L> <Lchg> <R> <Rchg> <Lthr> <Rthr>` | WF-XM5: `23 09 00 00 64 00 64 64` |
| 0x0A CRADLE_BATTERY_WITH_THRESHOLD | 0x2A | `<level> <chg> <threshold>` | WF-XM5: `23 0A 35 00 1E` (53 %, threshold 30) |

`BatteryChargingStatus` (V2): 0x00 NOT_CHARGING, 0x01 CHARGING, 0x02 UNKNOWN, 0x03 CHARGED (`ProtocolV2T1.hpp` L313-320). Struct layouts are at `ProtocolV2T1.hpp` L7560-7745.

**XM6**: `xm6-control` uses `22 00` and reports live updates working. `sony-device-center` reports XM6 battery as **"intermittent"** (`README.md` L57; `docs/technical-debt.md` L17-19). A possible cause is that XM6 advertises 0x28 (threshold variant). Then the device would reply or notify with type 0x08, and both clients reject that because they only accept type 0x00. This explanation is **UNVERIFIED**. Recommendation: select the type from the support list and accept both 0x00 and 0x08.

### V1 (COMMON group)

- GET `10 <type>`, RET `11 <type> …`, NTFY `13 …`. Types: 0x00 single, 0x01 L/R, 0x02 cradle.
- Layouts: single `<level> <chg>`. **L/R is `<L> <R> <Lchg> <Rchg>`**, a different order from V2 (`ProtocolV1T1.hpp` L1816-1822).
- V1 charging values: 0 NOT_CHARGING, 1 CHARGING, 0xF0 UNKNOWN.
- Over-ear V1 models answer every battery type and copy the single value into the left slot (`sony-device-center/.../ProtocolV1.cpp` L63-100).

---

## 7. Noise cancelling / ambient sound (NCASM)

### 7.1 V2 inquired types

From `ProtocolV2T1.hpp` L1131-1148. Layouts are for SET (`68`), RET (`67`) and NTFY (`69`). They are identical after the opcode byte.

| Type | Name | Layout after `<op> <type>` (byte index in the payload) |
|---|---|---|
| 0x01 | NC_ON_OFF | [2] valueChangeStatus, [3] totalEffect, [4] ncOnOff |
| 0x11 | NC_ON_OFF_AND_ASM_ON_OFF | [2] vcs, [3] eff, [4] asmOnOff, [5] asmMode, [6] ncOnOff |
| 0x12 | NC_MODE_SWITCH_AND_ASM_ON_OFF | [2] vcs, [3] eff, [4] NcValue, [5] asmMode, [6] onOff |
| 0x13 | NC_ON_OFF_AND_ASM_SEAMLESS | [2] vcs, [3] eff, [4] ncOnOff, [5] asmMode, [6] level |
| 0x14 | NC_MODE_SWITCH_AND_ASM_SEAMLESS | [2] vcs, [3] eff, [4] NcValue, [5] asmMode, [6] level |
| 0x15 | MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS | [2] vcs, [3] eff, [4] ncAsmMode, [5] NcValue, [6] asmMode, [7] level. 8 bytes. **UNVERIFIED on hardware.** Gadgetbridge sends 7 bytes for "0x15". |
| 0x16 | MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS | [2] vcs, [3] eff, [4] ncAsmMode, [5] NcValue, [6] asmMode, [7] level |
| **0x17** | **MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS** | **[2] vcs, [3] totalEffect, [4] ncAsmMode (0 = NC, 1 = ASM), [5] ambientSoundMode (0 = NORMAL, 1 = VOICE, i.e. Focus on Voice), [6] ambient level.** 7 bytes. |
| 0x18 | MODE_NC_NCSS_ASM_DUAL_… | same as 0x17. [4] is NcNcssAsmMode. |
| **0x19** | **MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA** ("NA" = Noise Adaptation) | **0x17 layout + [7] ncAsmOnOffValue (auto ambient sound: 0 = off, 1 = on) + [8] NoiseAdaptiveSensitivity (0 STANDARD, 1 HIGH, 2 LOW).** 9 bytes. |
| 0x21 | ASM_ON_OFF | [2] vcs, [3] eff, [4] asmMode, [5] asmOnOff |
| 0x22 | ASM_SEAMLESS | [2] vcs, [3] eff, [4] asmMode, [5] level (devices without NC) |
| 0x30 | NC_AMB_TOGGLE | [2] `Function`: the NC/AMB button cycle, e.g. 0x01 NC_ASM_OFF, 0x02 NC_ASM, 0x03 NC_OFF, 0x04 ASM_OFF |
| 0x40 | NC_TEST_MODE | factory test; skip |

Field values:
- `valueChangeStatus`: 0x00 UNDER_CHANGING (slider being dragged), 0x01 CHANGED (commit) (`ProtocolV2T1.hpp` L1728-1733).
- `totalEffect`: 0x00 OFF, 0x01 ON.
- `NcValue`: 0 OFF, 1 ON_SINGLE, 2 ON_DUAL, 3 AUTO, 4 AUTO_SINGLE, 5 AUTO_DUAL.

Evidence for 0x17 and 0x19:
- Set structs: `ProtocolV2T1.hpp` L6022-6068.
- RET structs: L5681-5727.
- NTFY structs: L5261-5307.
- Parser: `libmdr/src/HeadphonesV2T1.cpp` L126-150. It maps [5] to `mNcAsmFocusOnVoice`, [7] to `mNcAsmAutoAsmEnabled` and [8] to the sensitivity.
- Writer: `HeadphonesV2.cpp` L410-466.
- The client UI calls [7] "Auto Ambient Sound" (`client/I18N/Default.cpp` L161; `client/Client.cpp` L1634, L1719-1729).

**Capability**: GET `60 <type>`, RET `61 <type> <count> (<ambientSoundMode> <min> <max> <step>)×count` (`ProtocolV2T1.hpp` L10765-10860, L2354-2361, `Range` at `ProtocolV2.hpp` L93-100). Use it to discover the ambient level range instead of hard-coding 0-20.

**Status**: GET `62 <type>`, RET `63 <type> <ENABLE 00 / DISABLE 01>`. It tells you whether NC is currently usable.

### 7.2 Which inquired type to use

mos9527 picks the type from the support function list. The confirmed mappings are in `HeadphonesV2.cpp` L121-140 (init) and L410-466 (commit):

| Support function | NCASM type |
|---|---|
| 0x6B `MODE_NC_ASM_NOISE_CANCELLING_DUAL_AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT` | **0x17** |
| 0x6D `…_NOISE_ADAPTATION` | **0x19** |
| 0x67 `AMBIENT_SOUND_MODE_LEVEL_ADJUSTMENT` | 0x22 |
| 0x69 `AMBIENT_SOUND_CONTROL_MODE_SELECT` | 0x30 |

The other mappings follow from the names and are **UNVERIFIED**:

| Support function | NCASM type |
|---|---|
| 0x61 | 0x01 |
| 0x62 | 0x11 |
| 0x63 | 0x12 |
| 0x64 | 0x13 |
| 0x65 | 0x14 |
| 0x66 | 0x21 |
| 0x68 | 0x15 |
| 0x6A | 0x16 |
| 0x6C | 0x18 |

### 7.3 Which models use which type

| Model | Type | Evidence |
|---|---|---|
| WH-1000XM5 | **0x17** | `sony-device-center` (maintainer's XM5 FW 2.3.1, "Verified") `ProtocolV2.cpp` L95-143. Gadgetbridge's XM5 path sends 0x15 but accepts 0x15/0x17/0x22 replies (`SonyProtocolImplV2.java` L77-116, L785-791). Whether XM5 answers a 0x15 GET is **UNVERIFIED**. |
| WF-1000XM5 (same generation) | **0x17** | Capture: support list contains `6B 08`; `NCASM_RET_PARAM` = `67 17 01 00 01 00 14` (CHANGED, effect OFF, mode ASM, NORMAL, level 20) (`mos9527-shc/tests/WF-1000XM5-6.1.0/...000069...`) |
| **WH-1000XM6** | **0x19** | Three independent sources: (a) `xm6-macos-controller/.../SonyProtocol.swift` L72-74 (`case xm6 = 0x19`), L155-182; (b) `ibatra-shc/src-tauri/src/protocol/constants.rs` ("XM6 uses 0x19 … discovered from traffic capture") and `commands.rs` L129-148 with the captured frames below; (c) mos9527 `docs/device-support/WH-1000XM6.md` ("supports new auto ambient level"), which is the 0x6D/0x19 feature. `sony-device-center` uses 0x17 on XM6 and lists XM6 "noise-control readback unresolved" (`README.md` L57). That supports the view that XM6 does **not** answer 0x17 reliably. |

XM6 SET frames captured from the official app (ibatra `commands.rs` L130-134):

```
NC ON : 68 19 01 01 00 01 14 00 00
ASM ON: 68 19 01 01 01 00 0A 00 00   (level 10)
OFF   : 68 19 01 00 00 00 14 00 00
```

Decoded with the mos9527 layout:
- [2] = 01 CHANGED.
- [3] = total effect.
- [4] = 0 NC / 1 ASM.
- [5] = voice flag. It is 01 in the NC-ON frame, presumably because Focus-on-Voice was left on (**UNVERIFIED**).
- [6] = level.
- [7] = auto-ambient off.
- [8] = sensitivity STANDARD.

**Conflict (UNVERIFIED)** in the meaning of [5] and [7] for 0x19:
- `xm6-macos-controller` writes [5] = (mode == NC ? 1 : 0) and [7] = focusOnVoice. Its decoder reads Focus-on-Voice from [7] (`SonyHeadphoneDriver.swift` L167-182).
- ibatra treats [5] as an "NC flag".
- mos9527's layout comes from Sony's own class metadata: [5] = Focus on Voice, [7] = Auto Ambient Sound. **Prefer mos9527.** Verify on a real XM6 by toggling Focus-on-Voice and Auto Ambient in Sound Connect and watching the `69 19 …` notifications.

### 7.4 V1 NC/ASM (XM2/XM3/XM4)

Inquired types: 0x01 NOISE_CANCELLING, **0x02 NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE**, 0x03 AMBIENT_SOUND_MODE (`ProtocolV1T1.hpp` L627-634).

Layout for type 0x02, after `<op> 02` (`ProtocolV1T1.hpp` L1836-1848, L3747-3761):

| Byte | Field | Values |
|---|---|---|
| [2] | NcAsmEffect | 00 OFF, 01 ON, 10 ADJUSTMENT_IN_PROGRESS, 11 ADJUSTMENT_COMPLETION |
| [3] | NcSettingType | 00 ON_OFF, 01 LEVEL_ADJUSTMENT, 02 DUAL_SINGLE_OFF |
| [4] | NcDualSingleValue | 00 OFF (ambient), 01 SINGLE (wind-noise reduction), 02 DUAL (NC) |
| [5] | AsmSettingType | 00 ON_OFF, 01 LEVEL_ADJUSTMENT |
| [6] | AsmId | 00 NORMAL, 01 VOICE |
| [7] | asm level | 0-20 |

- XM3 RET capture: `67 02 01 02 02 01 00 00` (ON, DUAL_SINGLE_OFF, DUAL = NC, LEVEL, NORMAL, 0).
- XM3 NTFY when NC is toggled on the headset: `69 02 00 02 02 01 00 00` / `69 02 01 …`.
- Setters:
  - mos9527 writes ncType = LEVEL_ADJUSTMENT. Level −1 → DUAL (NC), 0 → SINGLE (wind), 1-20 → OFF + level (`HeadphonesV1.cpp` L13-24, L564-584).
  - Plutoberth sends effect 0x11 (ADJUSTMENT_COMPLETION) (`plutoberth-shc/Client/Headphones.cpp` L82-95; `CommandSerializer.cpp` L156-186).
  - sony-device-center sends `68 02 <eff> 01 <dualSingle> 01 <asmId> <level>` (`ProtocolV1.cpp` L146-160).

---

## 8. Equalizer (EQEBB)

### 8.1 Commands and inquired types

V2 inquired types (`ProtocolV2T1.hpp` L736-752):

| Value | Name |
|---|---|
| 0x00 | PRESET_EQ |
| 0x01 | EBB |
| 0x02 | PRESET_EQ_NONCUSTOMIZABLE |
| 0x03 | PRESET_EQ_AND_ULT_MODE |
| **0x04** | **PRESET_EQ_AND_ERRORCODE** |
| 0x30 | SOUND_EFFECT |
| 0x31 | CUSTOM_EQ |
| 0x32 | TURN_KEY_EQ |
| 0x33-0x36 | CUSTOMIZABLE_SOUND_EFFECT_* |
| 0x40 | ULT_BTN_SOUND_EFFECT_ASSIGN |

**V1 values are shifted**: 0x01 PRESET_EQ, 0x02 EBB, 0x03 PRESET_EQ_NONCUSTOMIZABLE (`ProtocolV1T1.hpp` L448-455).

How to pick the V2 type from the support functions: 0x50 → 0x00, 0x52 → 0x02, 0x53 → 0x03, **0x57 → 0x04** (`libmdr/src/DetailsV2.hpp` L294-307).

| Op | Purpose | Payload |
|---|---|---|
| `50 <type> <lang>` | GET_CAPABILITY (lang 0x01 = English) | RET `51 <type> <bandCount> <stepCount> <presetCount> (<presetId> <nameLen> <name>)×n`. Type 0x03 has an extra `ult2AdditionalStep` byte before the preset list (`ProtocolV2T1.hpp` L10571-10602). |
| `52 <type>` | GET_STATUS | RET/NTFY `53/55 <type> <00 enabled / 01 disabled>`. For type 0x04: `… <numErr> <errList>` with error codes 00 CALLING, 01 DEMO_MODE, **02 LISTENING_MODE**, FE OTHER (`ProtocolV2T1.hpp` L3964-3974, L4170-4180). |
| `56 <type>` | GET_PARAM | RET/NTFY `57/59 <type> <presetId> <count> <band bytes…>` (`EqParam`, L4284-4290, L10643-10653) |
| `58 <type> <presetId> <count> <bytes…>` | SET_PARAM | |
| `5A <type>` | GET_EXTENDED_INFO | RET `5B <type> <count> (<infoType> <freq>)×count`. infoType: 00 none, 01 Hz, 02 kHz, 10 specific (e.g. Clear Bass). V1 uses a **u16 BE** frequency (`ProtocolV1T1.hpp` L1168-1174). The V2 struct says u8 (`ProtocolV2T1.hpp` L3786-3792), which cannot hold 500 Hz unless kHz units are used. **UNVERIFIED for V2.** |

Rules learned on hardware (mos9527 `docs/device-support/WF-LC900.md`, the EQ notes):
- **Selecting a preset**: send `58 <type> <presetId> 00`, with zero band bytes. If you include band steps, the device switches to CUSTOM and drops the preset.
- **Custom curve**: send `58 <type> A0 <n> <steps…>`. sony-device-center and xm6-control use preset 0xA0. mos9527 V1 uses 0xFF UNSPECIFIED (`HeadphonesV1.cpp` L613-624). mos9527 V2 sends the current preset id.
- The device notifies the new curve with `59`. After a SET, mos9527 re-reads with `56`.
- While a listening mode (BGM / Cinema) is active, EQ and DSEE are disabled. The device signals this via `EQEBB_NTFY_STATUS … DISABLE` and `AUDIO_NTFY_STATUS UPSCALING DISABLE`. Re-query both after changing the listening mode (`HeadphonesV2.cpp` L697-702).
- On XM3, changing the EQ preset raised `ALERT_NTFY_PARAM DISCONNECT_CAUSED_BY_CONNECTION_MODE_CHANGE`. The client answered NEGATIVE and the preset still applied (`WH-1000XM3.md`, "Notes").

### 8.2 Band layouts per model

| Model | Family / type | Bytes | Order | Encoding / range | Band centres | Evidence |
|---|---|---|---|---|---|---|
| WH-1000XM3 | V1 / 0x01 | 6 | [Clear Bass, 400, 1k, 2.5k, 6.3k, 16k] | `wire = gain + 10`, −10…+10 (stepCount 0x15 = 21) | **400, 1000, 2500, 6300, 16000 Hz, read from the device's EXTENDED_INFO** | XM3 capture `51 01 06 15 0C …`, `57 01 00 06 0A 0A 0A 0A 0A 0A`, `5B 01 06 10 00 01 01 01 90 01 03 E8 01 09 C4 01 18 9C 01 3E 80` (after unescaping) |
| WH-1000XM4 | V1 / 0x01 | 6 | same | +10, ±10 | same (assumed) | sony-device-center "EQ + Clear Bass verified" FW 3.0.1 (`README.md` "WH-1000XM4" row; `ProtocolV1.cpp` L169-215) |
| WH-1000XM5 | V2 / 0x00 | 6 | same | +10, ±10 | 400/1k/2.5k/6.3k/16k (**UNVERIFIED** on XM5; no XM5 EXTENDED_INFO capture) | `sony-device-center/.../ProtocolV2.cpp` L145-223 (XM5 verified); WF-XM5 capture `57 00 A0 06 14 0B 0A 0D 09 0C` (Clear Bass +10, bands +1, 0, +3, −1, +2) |
| **WH-1000XM6** | **V2 / 0x04** | **10** | 10 bands, low → high, **no Clear Bass** | **`wire = gain + 6`, wire 0…12, neutral 6, −6…+6** | **31, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz: client labels only, UNVERIFIED** | See below |
| WH-1000XM2 / MDR-1000X | V1 | ? | | | | **UNVERIFIED**. Gadgetbridge's XM2 coordinator lists EqualizerWithCustomBands. No MDR-1000X data at all. |

XM6 EQ evidence:
- `xm6-control/.../Commands.swift` L120-180. A live XM6 echoed the write `58 04 A0 0A 09 08 07 06 05 04 03 06 06 06` byte for byte, and type 0x00 is "silently ignored".
- `sony-device-center` README L38, L57 ("10 bands (raw levels 0–12)", verified on FW 3.1.5, PR #44).
- `tests/protocol/ProtocolV2Tests.cpp` L120-170 (btsnoop capture `57 04 A0 0A 07 07 07 07 06 06 07 08 08 08`).
- `tests/protocol/DeviceEventDispatcherTests.cpp` L176-197 (capture `59 04 20 0A 00 04 08 09 07 05 07 09 06 04`).
- mos9527 `HeadphonesV2.cpp` L741-753 and `HeadphonesV2T1.cpp` L968-981 (10-band with offset 6).
- `client/Client.cpp` L930-940 (labels 31…16k, range −6…+6).
- `WF-LC900.md` EQ notes: a V2 10-band device reported `band 10, step 13`. That confirms the ±6 convention for 10-band V2 devices.
- Also: sony-device-center QML `apps/device-center/qml/Main.qml` L1459.
- Gadgetbridge's XM6 coordinator says "TODO 9 bands". That is contradicted by the captures and is wrong.

Notes:
- The dB value per step is **UNVERIFIED**; 1 dB per step is the natural reading.
- **Recommendation**: at connect, query `50 04 01` (band and step count, preset names) and `5A 04` (band frequencies). Do not hard-code these.

### 8.3 Preset IDs

V2 enum, `ProtocolV2T1.hpp` L754-815. V1 has the same list up to 0x1F plus 0xA0-0xA5.

| Range | Presets |
|---|---|
| 0x00 | OFF |
| 0x01-0x07 | ROCK, POP, JAZZ, DANCE, EDM, R&B/HIP-HOP, ACOUSTIC |
| 0x10-0x17 | BRIGHT, EXCITED, MELLOW, RELAXED, VOCAL, TREBLE (boost), BASS (boost), SPEECH |
| 0x20-0x23 | GAMING_EQ, FPS_1..3 |
| **0x30-0x33** | **HEAVY, CLEAR, HARD, SOFT** |
| **0xA0** | **CUSTOM** |
| 0xA1-0xA5 | USER_SETTING1..5 |
| 0xB0-0xBB | ARTIST_COLLAB1..12 |
| 0xFF | UNSPECIFIED |

Per-model preset lists:
- **XM3** (from its own capability reply): OFF, BRIGHT, EXCITED, MELLOW, RELAXED, VOCAL, TREBLE, BASS, SPEECH, CUSTOM, USER1, USER2. Every name string is empty, so the UI must supply labels. The same was seen on WF-LC900.
- **XM6**: "Only Heavy/Clear/Hard/Soft (0x30–0x33), Custom (0xA0) and the user settings are accepted … as of FW 3.0.0" (mos9527 `docs/device-support/WH-1000XM6.md`). Consistent with `xm6-control` `Models/EqualizerPreset.swift` and `xm6-macos-controller` (off/heavy/clear/hard).
- One XM6 btsnoop notification carried preset **0x20** (GAMING_EQ in the enum). Its meaning on XM6 is **UNVERIFIED**.
- XM5 preset list: **UNVERIFIED**. sony-device-center assumes the Bright…Speech family.

---

## 9. Other features: opcode inventory (V2 unless noted)

| Feature | GET | SET | RET / NTFY layout | Notes / evidence |
|---|---|---|---|---|
| Volume | `A6 20` | `A8 20 <0-30>` | `A7/A9 20 <vol>` | MUSIC_VOLUME 0x20, CALL_VOLUME 0x21. WF-XM5 RET `A7 20 0C`. V1: `A6 01 20` / `A8 01 20 <vol>` (`ProtocolV1T1.hpp` L3828-3837). |
| Play / pause / skip | `A2 01` | `A4 01 00 <ctl>` | `A3 01 <00> <PlaybackStatus> <music/call>` | ctl: 01 PAUSE, 02 next, 03 prev, 07 PLAY. **The `00` (ENABLE) byte is required** (`ProtocolV2T1.hpp` L8732-8744, L8091-8104). WF-XM5 `A3 01 00 02 00` = paused. |
| Track metadata | `A6 01` | | `A7 01 …` strings | mos9527 `HeadphonesV2.cpp` L113-120 |
| **DSEE** (upscaling) | `E6 01`; status `E2 01` | `E8 01 <00 OFF / 01 AUTO>` | `E7/E9 01 <v>` | Requires function 0xE2. **Not inverted.** WF-XM5 `E7 01 01`. xm6-macos-controller says it is verified on XM6. V1: `E8 02 00 <00/01>` (type 0x02, settingType 0x00) (`ProtocolV1T1.hpp` L3612-3620). |
| **Listening mode: BGM** | `E6 09` | `E8 09 <00 ON / 01 OFF> <room 00 small, 01 mid, 02 large>` | `E7/E9 09 <onoff> <room>` | Type 0x09 BGM_MODE_AND_ERRORCODE (0x03 on some firmware). Confirmed on XM6 by xm6-control (`Commands.swift` L186-220). mos9527 `HeadphonesV2.cpp` L639-672. |
| Listening mode: Cinema | `E6 04` | `E8 04 <00 ON / 01 OFF>` | | Exclusive with BGM. Send the "off" writes before the "on" writes (`HeadphonesV2.cpp` L649-692). |
| Voice contents / sound-leakage reduction | `E6 06` / `E6 07` | `E8 06/07 <onoff>` | | Other listening options (WF-LC900) |
| Connection quality (sound vs connection priority) | `E6 00` | `E8 00 <00 sound / 01 connection / 02 low-latency β>` | | Function 0xE1 |
| **Speak-to-Chat** (TYPE2) | `F6 0C`; ext `FA 0C` | `F8 0C <00 ON / 01 OFF> 01`; ext `FC 0C <sens> <timeout>` | `F7 0C <onoff> <preview>`; `FB 0C <sens> <timeout>` | sens: 00 AUTO, 01 HIGH, 02 LOW. timeout (ModeOutTime): 00 FAST, 01 MID, 02 SLOW, 03 NONE. Function 0xFC. WF-XM5 `F7 0C 01 01`, `FB 0C 00 00`. Agrees with xm6-control (XM6 live) and sony-device-center. xm6-macos-controller and ibatra use type **0x02** (TYPE1, ON = 01) instead, **UNVERIFIED** on XM6. **V1 (XM4)**: `F6 05` → `F7 05 <kind> <onoff>`; SET `F8 05 01 <00/01>` (not inverted) plus config `FC 05 00 <sens> <voiceFocus> <timeout>` (`sony-device-center/.../ProtocolV1.cpp` L24-31, L256-300; `HeadphonesV1.cpp` L700-723). |
| **Pause when removed** (wear detection) | `F6 01` | `F8 01 <00 ON / 01 OFF>` | `F7/F9 01 <v>` | Function 0xF1. WF-XM5 `F7 01 00` = ON. On XM6 the `26 01` GET is ACKed but never answered, so use the SYSTEM family (`xm6-control/.../Commands.swift` L353-376). V1: SYSTEM_SET_EXTENDED_PARAM `FC 03 00 <00/01>` (`ProtocolV1T1.hpp` L3920-3928). |
| Wearing sensor state | T2 `F2 00` (on 0x0E) | | `F3/F5 00 <00 normal, 01 illegal, 02 L off, 03 R off, 04 both off>` | `ProtocolV2T2.hpp` L3313-3323. **UNVERIFIED** on XM6. |
| **Auto power off** | `26 05` (with wearing detection) or `26 04` | `28 05 <current> <lastSelected>` | `27/29 05 <cur> <last>` | Elements: 00 5 min, 01 30 min, 02 60 min, 03 180 min, 04 15 min, **10 when removed**, **11 disabled** (`ProtocolV2T1.hpp` L280-301, L7776-7808). WF-XM5 `27 05 10 00`. XM6 verified with `28 05 <elem> 00` (xm6-control L328-347). **V1**: SYSTEM_SET_EXTENDED_PARAM `FC 04 01 <active> <selected>`. There is no 15-minute element on V1 (`ProtocolV1T1.hpp` L3903-3913; `WH-1000XM3.md` notes). |
| Power off | | V2 `24 03 01` (USER_POWER_OFF) / V1 `22 00 01` | | |
| **Paired / connected device list (multipoint)** | T2 `36 02` (with Class-of-Device, functions 0x32/0x33) or `36 00` (classic, function 0x30) | | `37 <type> <n> { MAC[17 ASCII] <connStatus> [CoD 3B BE if type 02] <nameLen> <name> }×n <playbackDevice>` | `ProtocolV2T2.hpp` L1639-1657, L4811-4835. A device is the active playback source when `connStatus == playbackDevice` (xm6-control, XM6 live, `Commands.swift` L226-280). The WF-XM5 capture `37 02 08 …` has 8 entries. |
| Pairing mode | T2 `32 <type>` | `34 <type> <00 normal / 01 inquiry-scan> 00` | `33/35 <type> <mode> <00>` | WF-XM5 `33 02 00 00` |
| Connect / disconnect / unpair | | T2 `3C <type> <00 disc / 01 conn / 02 unpair> <MAC17>` | NTFY `3D <type> <action> <result> <MAC17>` | `ProtocolV2T2.hpp` L1712-1724, L1931-1942 |
| **Playback-source switch** | T2 `36 01` (switch enabled) | `3C 01 <MAC17>`; enable `38 01 <0/1>` | `3D 01 <result> <MAC17>`; `37/39 01 <value> [result]` | `ProtocolV2T2.hpp` L1728-1781, L1945-1991. WF-XM5 `37 01 01`, `39 01 01 02`. |
| Multipoint on/off, touch panel, sidetone ("capture voice during call") | `D0 <D1..D4> <lang>` → names; `D6 <Dx>` | `D8 <Dx> 00 <00 ON / 01 OFF>` (boolean) | `D1 <Dx> <settingType 00 bool / 01 list> <strFmt> <name> <summary>`; `D7 <Dx> 00 <v>` | **Self-describing**: the capability reply gives the setting name. WF-XM5: D1 = "SIDETONE_SETTING", D2 = "MULTIPOINT_SETTING". XM3: GS2 = TOUCH_PANEL_SETTING, GS1 = ASSIGNABLE_KEY_SETTING list. Changing multipoint raises alert 0x06/0x07, which needs `98 00 <msg> <01 positive>`. XM6 mapping is **UNVERIFIED**; xm6-control's comment claims D1 = connect-two-devices, D2 = touch panel, D3 = capture-voice. |
| Alerts subscription | | `94 00 00` (FIXED_MESSAGE ENABLE); reply `98 00 <msgType> <00 NEG / 01 POS>` | `99 00 <msgType> <actionType>` | `ProtocolV2T1.hpp` L2086-2096, L2232-2242, L2325-2333 |
| NC/AMB button behaviour | `66 30` | `68 30 <Function>` | | Function 0x69 |
| Assignable keys | `F6 03` / `F6 0E` | `F8 03 <n> <presets…>` | WF-XM5 `F7 03 02 35 20` | Preset ids: `ProtocolV2T1.hpp` L1359-1389 |
| Head gesture | `F6 0F` | `F8 0F <00 ON / 01 OFF>` | WF-XM5 `F7 0F 01` | |
| **Voice guidance** | T2 `46 01` (on/off and language); `46 20` (volume) | `48 01 <00 ON / 01 OFF>`; `48 20 <Int8 vol> <feedback 00/01>` | `47 01 <onoff> <lang>`; `47 20 <vol>` | Functions T2 0x41/0x42. WF-XM5 `47 01 00 01`, `47 20 00`. Volume range **UNVERIFIED** (likely −2…+2). V1: T2 `48 01 00 <00/01>` (not inverted) (`ProtocolV1T2.hpp` L716-726). |
| Codec | `12 02` | | `13 02 <codec>` | |
| Safe listening / sound pressure | T2 `5A <00-03>` | | `5B …` | |
| NC optimizer | `82/86 <type>` | `84 …` | | OPT group |
| V1 VPT / sound position (XM2/XM3) | | `48 01 <preset>` / `48 02 <pos>` | | Plutoberth `Constants.h` L86-131. Positions: 00 off, 01 FL, 02 FR, 03 F, 11 RL, 12 RR. |

---

## 10. Per-model protocol summary

| Model | Family / UUID | Tables | NC type | EQ | Battery | STC | Status in sources |
|---|---|---|---|---|---|---|---|
| MDR-1000X (2016) | V1 (`96CC203E…`), **UNVERIFIED** | T1 (+T2 if 0x38 or 0x39 advertised) | V1 0x02 (assumed) | unknown | V1 `10 00` | none | sony-device-center: "Known broken – shows as disconnected" (#12). No capture anywhere. **UNVERIFIED.** |
| WH-1000XM2 (2017) | V1 | T1 + T2 (voice guidance) | V1 0x02 (+ wind/single) | V1 0x01, 6 bytes (assumed) | V1 | none | sony-device-center lists it as V1, untested. Gadgetbridge XM2 coordinator: battery, ASC, wind NR, optimizer, custom EQ, sound position, surround, DSEE. |
| WH-1000XM3 (2018) | V1, protocol **0x4010** (FW 4.5.2) | T1 + T2 | V1 0x02 | V1 0x01, 6 bytes, ±10 | V1 `10 00` | none | Full capture in mos9527 `tests/WH-1000XM3-4.5.2/` and `docs/device-support/WH-1000XM3.md` |
| WH-1000XM4 (2020) | V1 | T1 + T2 | V1 0x02 | V1 0x01, 6 bytes, ±10 | V1 `10 00` | V1 `F6/F8/FC 05` | sony-device-center verified FW 3.0.1 (battery, NC readback, EQ + Clear Bass, firmware, codec, STC) |
| WH-1000XM5 (2022) | **V2** (`956C7B26…`) | T1 + T2 | **0x17** | V2 **0x00**, 6 bytes, ±10 | `22 00` | TYPE2 `F6/F8 0C`, `FA/FC 0C` | sony-device-center verified FW 2.3.1. mos9527 doc: EQ OK, voice-guidance volume not available. |
| **WH-1000XM6** (2025) | **V2** | T1 + T2 | **0x19** | V2 **0x04**, **10 bands, 0…12 (±6)**, no Clear Bass | `22 00` (maybe `22 08`, **UNVERIFIED**) | TYPE2 0x0C (xm6-control) | mos9527 (all features ✅ incl. voice-guidance volume, listening mode, multipoint, EQ); sony-device-center (EQ verified FW 3.1.5; NC readback unresolved because it uses 0x17); xm6-control, xm6-macos-controller and ibatra are all XM6-focused |

Firmware references seen: XM3 4.5.2; XM4 3.0.1; XM5 2.3.1; XM6 3.0.0 (mos9527 EQ note) and 3.1.5 (sony-device-center EQ).

---

## 11. XM6-specific notes

1. **NC/ASM**: use type 0x19 (9 bytes). Read the auto-ambient flag ([7]) and the sensitivity ([8]) and write them back unchanged, so you never clobber the user's Sound Connect settings. Send `valueChangeStatus` = 00 while a slider is moving and 01 on release. That is what the official app does (`xm6-control/.../Commands.swift` L45; mos9527 `HeadphonesV2.cpp` L421-422).
2. **EQ**: type 0x04, ten bands, +6 bias. Presets 0x00 and 0x30-0x33, Custom 0xA0, user slots 0xA1+. EQ is disabled while BGM or Cinema is on (status error code 0x02 LISTENING_MODE).
3. **Listening mode**: BGM `E8 09 <onoff> <room>` and Cinema `E8 04 <onoff>`, both inverted (00 = ON).
4. **Pause-when-removed**: SYSTEM `F6/F8 01`, not POWER `26 01`. The latter is ACKed but never answered on XM6.
5. **Auto power off**: `28 05 <elem> 00`, where 0x10 = when removed and 0x11 = off. Do not use SYSTEM `F8 05`: on XM6 that is VOICE_ASSISTANT_WAKE_WORD (`xm6-control/.../Commands.swift` L334-341; `ProtocolV2T1.hpp` L1586-1606).
6. **Multipoint list**: T2 `36 02`. Source switch: T2 `3C 01 <MAC>`.
7. **Unknown or UNVERIFIED on XM6**: the exact battery inquired type; the band frequencies; the general-settings slot mapping; the voice-guidance volume range; whether 0x15/0x17 GETs are answered at all.

---

## 12. Comparison with our Swift base (`refs/xm6-control/Sources/SonyHeadphonesKit/Protocol/*.swift`)

| Area | xm6-control | Reference (mos9527 + captures) | Verdict |
|---|---|---|---|
| Framing, escaping, checksum | `SonyMessage.swift` L20-125 | identical | ✅ |
| Frame reassembly | `FrameParser.swift` (8 KB cap) | identical approach | ✅ |
| Data types | `0x01`, `0x0C`, `0x0E` only | others exist but are unused | ✅ (the decoder drops other types, which is fine) |
| Seq / ACK | `HeadphonesController.swift` L339-358: adopt the ACK's seq; ACK with `seq ^ 1` | same | ✅. Also check that RET-before-ACK data is not dropped while `awaitingAck`. |
| Protocol detection | RET length 4 → V1, 8 → V2 | same | ✅. The family is still decided by the UUID. |
| **NC/ASM** | GETs `66 15` **and** `66 17`. Decoder accepts only 0x15/0x17/0x22 with **6-8 byte** payloads (`Commands.swift` L35-99). Setter writes `68 <subtype> 01 <eff> <asm> [02] <voice> <level>`. | XM6 uses **0x19, 9 bytes** | ⚠️ **Gap**: 9-byte `67/69 19 …` replies and notifications are rejected, so headset-side NC button presses will not be reflected. Add a 0x19 path chosen from support function 0x6D. The 0x17 layout itself is correct. |
| Battery | `22 00` only; decoder requires type 0x00 | type depends on the support function (0x00 or 0x08) | ⚠️ Accept 0x08 (`23 08 lvl chg thr`) as well |
| **EQ** | GETs `56 04` and `56 00`. Presets `58 04 <id> 00`. Custom `58 04 A0 0A <g+6>×10`, clamped ±6 (`Commands.swift` L120-184). | matches the XM6 captures | ✅. Add `50 04 01` and `5A 04` discovery. The 6-byte path for XM5 should use type 0x00 and +10. |
| Listening mode | `E6/E8 09`, `E6/E8 04`, inverted | same | ✅ |
| Multipoint | T2 `36 02`, `3C 01 MAC` | same | ✅ |
| Speak-to-chat | `F6/F8 0C`, inverted; config `FA/FC 0C` | same (TYPE2) | ✅ |
| Auto power off | `26/28 05`, elements 0x10 / 0x11 | same | ✅ (only 2 of 7 elements are modelled) |
| Pause when removed | `F6/F8 01`, inverted | same | ✅ |
| Identity | not implemented | `04 01`, `04 02`, `04 03`, `06 00` (both tables) | ➕ To add, needed for multi-model support |
| V1 (XM4 and older) | not supported | separate opcode and polarity tables | ➕ Implement as a separate codec. Never reuse V2 opcodes; V1 0x22 is power-off. |

The comment in `Opcodes.swift` L80-89 ("0x15 ... XM6's coordinator does not declare those capabilities") reflects Gadgetbridge's naming, not Sony's. Sony's own 0x15 is the "AUTO NC" variant with an 8-byte layout.

---

## 13. Quality notes on the other repositories

- **mos9527**: the most complete and the most authoritative, because its tables are machine-extracted from Sound Connect. Caveats:
  - Generated *default* values in some structs are wrong. For example, `NcAsmSetParamModeNcModeSwitchAsmSeamless` defaults its type to `NC_ON_OFF` (`ProtocolV2T1.hpp` L6094-6110). Always set the type explicitly.
  - The V2 EQ path only handles inquired type 0x00 in `HandleEqEbbParamT1` and always sends GET_STATUS / GET_PARAM / SET with 0x00 (`HeadphonesV2.cpp` L210-219, L704-762; `HeadphonesV2T1.cpp` L939-1030). That conflicts with XM6 needing 0x04, despite the "✅ Equalizer" in its XM6 doc. **Possible regression; UNVERIFIED.**
  - Only a shallow history (50 commits) was inspected.
- **Plutoberth**: V1 only, and only NC/ASM and VPT setters. `unpackBtMessage` checksums the *escaped* buffer, which is a bug (`CommandSerializer.cpp` L136-153).
- **sony-device-center**:
  - Its V2 NC uses 0x17 only, which does not work for XM6.
  - The EQ path takes a hard-coded 10-band flag per model profile.
  - It vendors nlohmann/json (MIT).
  - `AGENTS.md` and `PROMPT.md` show heavy LLM-assisted development. Trust its *hardware-verified* notes more than its code.
- **ibatra**:
  - Battery uses `26 xx` (POWER_GET_PARAM), which is wrong; it should be `22 xx`.
  - Playback control omits the ENABLE byte.
  - Speak-to-chat uses TYPE1 0x02.
  - It is useful only for its XM6 0x19 capture bytes.
- **xm6-macos-controller (GPL-3.0)**: an XM6-only Swift app. Its 0x19 field interpretation ([5] / [7]) disagrees with mos9527; see §7.3.

---

## 14. Licenses and reuse

| Repo | SPDX | Copy into an MIT project? | Conditions |
|---|---|---|---|
| mos9527/SonyHeadphonesClient | MIT | **Yes** | Keep "Copyright (c) 2026 mos9527, Amr Satrio and other contributors" and the MIT text, e.g. in a THIRD_PARTY_NOTICES file. Generated headers carry no extra license. Third-party deps (SDL, imgui, fmt …) are declared in `contrib/CMakeLists.txt`; only `libmdr` is relevant to us. |
| Plutoberth/SonyHeadphonesClient | MIT | **Yes** | Keep the 2020 copyright notice (Nir Harel, Mor Gal, Sem Visscher, jimzrt, guilhermealbm). The imgui submodule is separate (MIT). |
| marconvcm/sony-device-center | MIT (Plutoberth's notice) | **Yes** | Keep the same notice. `third_party/nlohmann` is MIT. Provenance caveat: `ProtocolV1.cpp` L7-10 says its layouts "match … Gadgetbridge's SonyProtocolImplV1". |
| ibatra/sony-headphones-client | MIT, declared only (package.json, README); no LICENSE file, no copyright holder | **Technically yes, but avoid** | There is no license text to reproduce. Treat it as reference, or ask the author to add a LICENSE. |
| shellingtonshreyas/xm6-macos-controller | GPL-3.0 | **No** | Copying code would make our app GPL. Read for facts only. Do not translate its code line by line. |
| Gadgetbridge (cross-check only) | AGPL-3.0-or-later | **No** | Reference only |
| xm6-control (our base) | MIT (Rui Martins 2026) | **Yes** | Keep the notice. **Provenance flag**: its comments state layouts were "read directly out of Gadgetbridge's … source" (`Commands.swift` L3-9; `SonyMessage.swift` L5-6, L23-24). Byte values and wire formats are generally treated as non-copyrightable interface facts, but code *structure* translated from AGPL source could be argued to be derivative. Low risk given the small, fact-driven code. Consider re-deriving from the MIT mos9527 tables and re-wording the comments. (Not legal advice.) |

General point: mos9527's tables were extracted from Sony's proprietary iOS app with IDA (`tooling/ida/README.md`). The repo is MIT, but the facts originate from reverse engineering for interoperability. This is common practice; flag it to whoever owns legal sign-off. Do not ship anything containing Sony binaries, assets or trademarks beyond nominative model names.

---

## 15. Open questions and suggested on-device checks (XM6)

1. Capture `07 00 …` (T1 and T2 support lists) from a real XM6. This settles the NC type (expect 0x6D), the battery type (0x20 or 0x28), the EQ type (expect 0x57), STC (0xFC) and the voice-guidance variant.
2. Send `60 19`: the NC capability gives the ambient range and step.
3. Send `50 04 01` and `5A 04`: EQ band and step count, preset names, band frequencies. This settles the 31…16k question and the ±6 / ±10 question.
4. Toggle Focus-on-Voice and Auto Ambient in Sound Connect while sniffing (Android HCI snoop) or while connected via our app. This confirms the meaning of 0x19 [5] and [7].
5. Send `D0 D1 01` … `D0 D4 01`: general-settings names (multipoint / touch / sidetone mapping).
6. Check whether XM6 answers `66 17` at all (compatibility).
7. MDR-1000X and XM2: no captures exist in any repo. Their tables are inferred (**UNVERIFIED**).
