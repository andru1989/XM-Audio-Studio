> Research snapshot from 2026-10-21 (Etapa 0). Paths under `scratchpad/` or `refs/` refer to temporary
> clones that are not part of this repository.

# Gadgetbridge Sony headphones support: technical reference

**Purpose:** a reference for a new MIT-licensed native Android app (Kotlin and Jetpack Compose). The app will control the Sony WH-1000XM6 over Bluetooth Classic RFCOMM using the Sony MDR protocol, and later the MDR-1000X and WH-1000XM2 to XM5.

**Source studied:** Gadgetbridge at codeberg.org/Freeyourgadget/Gadgetbridge. This was a shallow clone at commit `0735529275699325638f5e963b68d36cdcddd8db` (2026-10-21, "iTAG: add device settings and features"), sparse-checked-out to the Sony headphones, BTBR transport and serial paths.

**Local clone:** `/private/tmp/claude-501/-Users-andru1989-Documents-Proyectos-XM-Audio-Studio/8d43e2e4-55d7-46a4-b7cd-015f337907a2/scratchpad/refs/gadgetbridge`

**Website consulted:** https://gadgetbridge.org/gadgets/headphones/sony/, fetched 2026-10-21 and treated as data.

**Path abbreviations used below:**
- `SVC` = `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones`
- `DEV` = `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones`
- `BTBR` = `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr`
- `TEST` = `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl`

**Notation:**
- Payload bytes are shown in hex, without the frame wrapper.
- `MT` = message type: `0x0c` COMMAND_1 or `0x0e` COMMAND_2.
- Statements marked **UNVERIFIED** are inferences or are not confirmed by Gadgetbridge code, tests or captures.

---

## 1. License and what it means for an MIT project

| Item | Finding | Evidence |
|---|---|---|
| Project license | **GNU Affero General Public License v3.0**. The headers say "either version 3 of the License, or (at your option) any later version", so it is effectively **AGPL-3.0-or-later**. | `LICENSE` (line 1: "GNU AFFERO GENERAL PUBLIC LICENSE Version 3, 19 November 2007"); `README.md` line 114: "Gadgetbridge is licensed under the AGPLv3" |
| Sony files | Every Sony file carries the same AGPL header. For example: `SVC/SonyHeadphonesProtocol.java` is "Copyright (C) 2021-2026 José Rebelo"; `SVC/SonyHeadphonesSupport.java` is "Arjan Schrijver, José Rebelo"; `BTBR/BtBRQueue.java` is "Damien Gaignon, José Rebelo"; `DEV/coordinators/SonyWH1000XM6Coordinator.java` is "Copyright (C) 2026 José Rebelo". | File headers |
| Exceptions | README lists some third-party files under MIT, Apache or BSD, such as Bouncy Castle and nQuant. **None of them are in the Sony or BTBR paths.** | `README.md` lines 115-121 |

### Verdict

**Do not copy, translate or closely paraphrase Gadgetbridge code into the MIT project.**
- AGPL-3.0 is a strong copyleft license. Copying Java source or porting it line by line to Kotlin makes a derivative work.
- A derivative work would force the whole app to be distributed under AGPL-3.0, which is incompatible with releasing it as MIT. This covers:
  - class structure taken verbatim, such as the `Message` encode/escape code, `SonyProtocolImplV1`/`V2`, or the `onSocketRead` reassembly loop;
  - comments;
  - test files.

**Use Gadgetbridge only as a reference and write a clean implementation.**
- Protocol facts are interoperability information, not creative expression. This includes byte values, command IDs, frame format, UUIDs, enum codes, payload layouts and sequencing behavior.
- In practice such facts can be reimplemented from a written spec like this document.
- Recommended process:
  1. Work from this spec, not from the Gadgetbridge source.
  2. Write your own architecture, naming and tests. Write new test vectors from your own captures where possible; byte strings that encode protocol facts are low-risk.
  3. Do not import Gadgetbridge's comments or test files.
- This is not legal advice. If the project wants certainty, have the person who writes the Kotlin code be someone who has not studied the Gadgetbridge source in depth (clean room).

---

## 2. Android transport

### 2.1 Class chain

`SonyHeadphonesSupport` → `AbstractHeadphoneSerialDeviceSupportV2` (deprecated) → `AbstractSerialDeviceSupportV2` (deprecated) → **`AbstractBTBRDeviceSupport`** → `BtBRQueue`.

- Evidence: `SVC/SonyHeadphonesSupport.java`; `app/src/main/java/.../service/serial/AbstractSerialDeviceSupportV2.java` (`extends AbstractBTBRDeviceSupport`); `BTBR/AbstractBTBRDeviceSupport.java`; `BTBR/BtBRQueue.java`.
- The older `service/btclassic/BtClassicIoThread.java` is **not** used by the Sony driver. It is the legacy path used by `AbstractSerialDeviceSupport` (V1). For comparison, it also uses `createRfcommSocketToServiceRecord(uuids[0])` and a blocking read loop.

### 2.2 Service UUIDs and selection

These come from `SVC/SonyHeadphonesSupport.java`, `getSupportedService()`.

| Name | UUID |
|---|---|
| `BTRFCOMM_UUID_V1` | `96CC203E-5068-46ad-B32D-E316F5E069BA` |
| `BTRFCOMM_UUID_V2` | `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` |

Selection algorithm:
1. Read the cached SDP UUIDs with `BluetoothDevice.getUuids()` (via `AbstractBTBRDeviceSupport.getBluetoothDeviceUuids()`).
2. If V2 is present, use V2. Otherwise, if V1 is present, use V1.
3. If neither is present, fall back to `coordinator.preferServiceV2()`. Only LinkBuds, LinkBuds S and WH-ULT900N return `true`. **All WH-1000X coordinators, XM6 included, fall back to V1.**
- Gadgetbridge never calls `fetchUuidsWithSdp()`.
- Possible bug: the `for (ParcelUuid uuid : uuids)` loop is not null-guarded, so a null `getUuids()` would cause a NullPointerException. Do not copy this; handle null and trigger `fetchUuidsWithSdp()`.
- **UNVERIFIED:** which UUID the XM6 advertises. Newer Sony devices (XM5-era and later) are commonly reported to expose the V2 UUID. Log `getUuids()` on a real XM6.

The RFCOMM UUID (V1 or V2) is independent of the MDR protocol version (v1 or v2). The protocol version is detected later from the init reply (§3.4).

### 2.3 Socket creation and connect (`BTBR/BtBRQueue.java`)

- **Secure RFCOMM only:**
  - Gadgetbridge calls `btDevice.createRfcommSocketToServiceRecord(mService)` (`connect()`).
  - There is **no** `createInsecureRfcommSocketToServiceRecord` fallback and no automatic fallback chain.
  - A reflection path, `BluetoothDevice.class.getMethod("createRfcommSocket", int.class)`, exists. It is used only when `getRfcommChannel() >= 0`. Sony leaves the default `RFCOMM_CHANNEL_UNSPECIFIED = -1`, so it always uses SDP by UUID.
- `mBtAdapter.cancelDiscovery()` is called before creating the socket.
- `mBtSocket.connect()` is a blocking call. It runs on a dedicated `HandlerThread` named `"BtBRQueue_write_N"` (THREAD_PRIORITY_BACKGROUND), via message `HANDLER_SUBJECT_CONNECT`, which is posted at the front of the queue.
- **Connect delay:**
  - Before `connect()`, the thread sleeps for `getConnectDelayMillis()`.
  - Sony overrides this to **500 ms**, with the comment "Connecting too fast fails with an IOException" (`SVC/SonyHeadphonesSupport.java`, `getConnectDelayMillis`).
- **On IOException or SecurityException during connect:**
  - The socket is closed.
  - The state becomes `WAITING_FOR_RECONNECT` if the user's auto-reconnect pref is on, else `NOT_CONNECTED`.
  - There is **no** immediate retry loop inside BtBRQueue. Reconnect is driven by Gadgetbridge's generic device service and receivers, which are outside the checked-out paths; their behavior is **UNVERIFIED** here.
- **On success:**
  1. The state becomes `CONNECTED`.
  2. A read thread starts.
  3. `onConnectionEstablished()` runs, which calls `initializeDevice(TransactionBuilder)`.

### 2.4 Threads

| Thread | Role | Evidence |
|---|---|---|
| `BtBRQueue_write_N` (HandlerThread) | Runs the connect, then executes `Transaction`s serially. Each `WriteAction` does `socket.getOutputStream().write(bytes); flush()`. A write failure aborts the remaining actions in that transaction. | `BTBR/BtBRQueue.java` (`HANDLER_SUBJECT_PERFORM_TRANSACTION`); `BTBR/actions/WriteAction.java` |
| `BtBRQueue_read_N` (Thread) | Loops `mBtSocket.getInputStream().read(buffer)` with a buffer of `mBufferSize` = **2048** (the default from `AbstractSerialDeviceSupportV2()`). Hands each `Arrays.copyOf(buffer, nRead)` to `onSocketRead`. Exits on IOException or `-1`, cleans up, then sets `WAITING_FOR_RECONNECT` or `NOT_CONNECTED`. | `BTBR/BtBRQueue.java` `createReadThread()` |
| Main looper `Handler` | Schedules init retries. | `SVC/SonyHeadphonesSupport.java` (`new Handler(Looper.getMainLooper())`) |

- **Timeouts:**
  - The socket read is fully blocking, with no read timeout.
  - The only timer is the init retry: **1250 ms**, at most **2 retries**, so 3 INIT sends in total.
  - After that, the device goes to `WAITING_FOR_RECONNECT` (auto-reconnect on) or `NOT_CONNECTED`. Evidence: `initSendRunnable` and `scheduleInitRetry()` in `SVC/SonyHeadphonesSupport.java`.
- **Disconnect:** `BtBRQueue.disconnect()` quits the write HandlerThread and closes the socket. `dispose()` also interrupts the read thread.

### 2.5 Stream reassembly

`SonyHeadphonesSupport.onSocketRead` works as follows:
- It keeps a 2048-byte `ByteBuffer packetBuffer`, which is cleared on each `initializeDevice`.
- On each chunk it calls `put(data)`, then `flip()`, then loops:
  1. Read one byte. If it is not `0x3E`, log a warning and continue, which skips garbage.
  2. Search forward for the first `0x3C` (trailer).
  3. If none is found, `reset()` and break to wait for more data.
  4. Otherwise copy `[header..trailer]` inclusive and pass it to `SonyHeadphonesProtocol.decodeResponse`.
- Finally it calls `compact()`.
- This works because `0x3C`, `0x3D` and `0x3E` are always escaped inside a frame (§3.1).
- A frame larger than 2048 bytes would overflow the buffer. Size your buffer generously, since some devices send large JSON or log payloads (`JSON_RET 0xc9` in V1).

### 2.6 ACKs, sequence numbers and flow control

All of this is in `SVC/SonyHeadphonesProtocol.java`.

- **State:** one `byte sequenceNumber`, initially 0, plus `Queue<Request> requestQueue` and `int pendingAcks`.
- **Outgoing command:** encoded with the **current** `sequenceNumber`.
- **Incoming ACK** (`MT 0x01`, empty payload):
  - If `ack.seq == sequenceNumber`, Gadgetbridge logs "Unexpected ACK" and ignores it.
  - Otherwise it sets `sequenceNumber = ack.seq`. The device ACKs with `1 - sentSeq`, so the sequence toggles 0/1.
  - It then calls `handleAck()`: `pendingAcks--`, sends the next queued request (if any) with the new sequence number, and, if the queue is empty while the state is `INITIALIZING`, sets the state to `INITIALIZED`.
- **Incoming COMMAND_1 or COMMAND_2:**
  - Gadgetbridge *first* emits an ACK: `Message(ACK, seq = 1 - receivedSeq, payload = [])`.
  - It then dispatches the payload to the protocol implementation.
  - Receiving a command does **not** change the outbound `sequenceNumber`.
- **Queueing:**
  - Init requests are enqueued (via `SonyHeadphonesEnqueueRequestEvent`) and sent one at a time, one per ACK.
  - User configuration changes (`encodeSendConfiguration`) are sent **immediately**, not queued, with `pendingAcks++`.
  - Some settings enqueue follow-ups, for example a reboot after changing the NC/AMB button function or voice assistant, or the "connect two devices" commit.
- **Retries and resend:** **none** for normal commands; only the init is retried. The source has a TODO: "Ideally, we should retry every command for which we didn't get an ACK."
- **Weak bookkeeping, not to copy:**
  - `getFromQueue()` does not increment `pendingAcks`.
  - The ACK for INIT can flip the state to `INITIALIZED` before the INIT reply arrives.
  - Immediate config writes can be in flight at the same time as queued init reads.
  - A cleaner design: a single-writer coroutine actor. It sends one command, awaits the ACK with a timeout (for example 1-2 s), resends with the **same** sequence number up to N times, then advances. This is a recommendation, not Gadgetbridge behavior.

### 2.7 Permissions

These are in `app/src/main/AndroidManifest.xml`, lines 10-26.
- `BLUETOOTH` is kept on all SDKs (`tools:remove="android:maxSdkVersion"`, issue #5053). `BLUETOOTH_ADMIN` is declared with `maxSdkVersion=30`.
- `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are declared. Location permissions are declared for scanning.
- The BTBR code uses `@SuppressLint("MissingPermission")` and catches `SecurityException` on connect.
- Runtime-permission prompting lives elsewhere in Gadgetbridge and is not in the checked-out paths.
- For our app: request `BLUETOOTH_CONNECT` at runtime on API 31+. Only request `BLUETOOTH_SCAN` if we do discovery. Bonded devices can be listed with `BLUETOOTH_CONNECT` alone.
- **Pairing:** coordinators use the default `getBondingStyle() = BONDING_STYLE_ASK` (`devices/AbstractDeviceCoordinator.java`, line 712). The website lists every Sony model as "pair free".

### 2.8 Transport recipe for our app

This is distilled behavior, not code.
1. Find the bonded device whose name matches the model regex (§7). Require `BLUETOOTH_CONNECT`.
2. Choose the UUID:
   - Read `getUuids()`. If it is null or empty, call `fetchUuidsWithSdp()` and wait for `ACTION_UUID`.
   - Prefer `956C7B26-…` (V2), else `96CC203E-…` (V1). Fall back by model: V2 for XM5 and XM6 (**UNVERIFIED**), V1 for XM2 to XM4.
3. Call `cancelDiscovery()`, wait about 500 ms, then call `createRfcommSocketToServiceRecord(uuid).connect()` on an IO dispatcher.
   - Optional hardening, which Gadgetbridge does not do: on failure, retry with backoff, then try `createInsecureRfcommSocketToServiceRecord`.
4. Start one reader coroutine with a blocking `read()`, a reassembly buffer of 4 KB or more, and the 0x3E/0x3C framing. Use a single writer for outgoing frames.
5. ACK every incoming 0x0c or 0x0e immediately, with `seq = 1 - rxSeq`.
6. Send INIT (§3.4). Retry after 1.25 s, up to 2 times.
7. After the INIT reply, pick the protocol (v1 or v2) and send the capability-gated GETs one at a time, each gated on its ACK.
8. On IOException, close the socket and go to a reconnect-pending state. Reconnect on `ACTION_ACL_CONNECTED` or on user action.

---

## 3. Protocol

### 3.1 Frame format

Source: `SVC/protocol/Message.java`. The encoding below is verified against vectors in `TEST/v2/SonyProtocolImplV2Test.java`.

```
0x3E | ESC( type:1 | seq:1 | len:4 (big-endian) | payload:len | checksum:1 ) | 0x3C
```

| Field | Size | Notes |
|---|---|---|
| Header | 1 | `0x3E` (`MESSAGE_HEADER`) |
| Message type | 1 | `0x01` ACK, `0x0c` COMMAND_1, `0x0e` COMMAND_2. Anything else maps to UNKNOWN (`0xff`) (`SVC/protocol/MessageType.java`). |
| Sequence | 1 | 0 or 1 (toggling, §2.6) |
| Length | 4 | Big-endian payload length |
| Payload | n | `payload[0]` is the "payload type" or command code |
| Checksum | 1 | `sum(type, seq, len[4], payload) & 0xFF`. This is the unescaped sum, with the header excluded. |
| Trailer | 1 | `0x3C` (`MESSAGE_TRAILER`) |

**Escaping:**
- Applies to every byte between the header and trailer, **including the checksum**.
- Bytes `0x3C`, `0x3D` and `0x3E` are written as `0x3D, (b & 0xEF)`. So `3E` becomes `3D 2E`, `3C` becomes `3D 2C`, and `3D` becomes `3D 2D`.
- To unescape, on `0x3D` take the next byte and OR it with `0x10`.

**Decode validation** (`Message.fromBytes`):
- Header and trailer must be present.
- Unescape, then verify the checksum. On a mismatch, log a warning and drop the frame.
- Verify that `len == unescapedLength - 9`.
- An unknown type is mapped to UNKNOWN.

**Worked frames.** These were computed with a scratch script and match Gadgetbridge test vectors where one exists.

| Meaning | Frame |
|---|---|
| INIT, seq 0, payload `00 00` | `3e 0c 00 00 00 00 02 00 00 0e 3c` |
| ACK replying to a device command with seq 0 | `3e 01 01 00 00 00 00 02 3c` |
| V2 get codec, seq 1 (test vector) | `3e 0c 01 00 00 00 02 12 02 23 3c` |
| V2 EQ preset MANUAL, seq 1 (test vector) | `3e 0c 01 00 00 00 04 58 00 a0 00 09 3c` |
| V2 ASC set NC (type 0x15, level 20), seq 0 | `3e 0c 00 00 00 00 07 68 15 01 01 00 00 14 a6 3c` |
| Escape example: payload `3e` | `3e 0c 00 00 00 00 01 3d 2e 4b 3c` |

### 3.2 Protocol implementations

The only implementations are `SonyProtocolImplV1` and `SonyProtocolImplV2`. **There is no V3.**
- `V2 extends V1` and overrides the methods that differ (`SVC/protocol/impl/v2/SonyProtocolImplV2.java`).
- The abstract API is in `SVC/protocol/impl/AbstractSonyProtocolImpl.java`.
- Any unsupported getter or setter returns `null`, and null requests are filtered out of the init list.
- The developer setting `pref_protocol_version` has the values `auto`, `v1` and `v2` (`res/values/arrays.xml` lines 4060-4070; `res/xml/devicesettings_sony_headphones_protocol_version.xml`).

### 3.3 Command codes

`payload[0]` is the command code. The "sub" byte is `payload[1]`. Most families follow the pattern GET = x6, RET = x7, SET = x8, NOTIFY = x9; some use xA to xD.

**V1 codes** (`SVC/protocol/impl/v1/PayloadTypeV1.java`):

| Code | Name | MT |
|---|---|---|
| 00 / 01 | INIT_REQUEST / INIT_REPLY | 0c |
| 04 / 05 | FW_VERSION req/reply | 0c |
| 06 / 07 | INIT_2 req/reply (defined, **never sent**) | 0c |
| 10 / 11 / 13 | BATTERY req/reply/notify | 0c |
| 18 / 19 / 1b | AUDIO_CODEC req/reply/notify | 0c |
| 22 | POWER_OFF | 0c |
| 46 / 47 / 48 / 49 | SOUND_POSITION_OR_MODE get/ret/set/notify (virtual sound) | 0c |
| 56 / 57 / 58 / 59 | EQUALIZER get/ret/set/notify | 0c |
| 66 / 67 / 68 / 69 | AMBIENT_SOUND_CONTROL get/ret/set/notify | 0c |
| 84 / 85 | NC_OPTIMIZER start/status | 0c |
| 86 / 87 / 89 | NC_OPTIMIZER_STATE get/ret/notify | 0c |
| 90 / 91 | "SOMETHING" get/ret (unknown; sent by the Sony app) | 0c |
| a6 / a7 / a8 / a9 | VOLUME get/ret/set/notify | 0c |
| c4 / c9 | JSON get/ret | 0c |
| d6 / d7 / d8 / d9 | TOUCH_SENSOR family (sub d1/d2/d3) | 0c |
| e6 / e7 / e8 / e9 | AUDIO_UPSAMPLING family (sub 01/02) | 0c |
| f6 / f7 / f8 / f9 | AUTOMATIC_POWER_OFF_BUTTON_MODE family ("general settings", many subs) | 0c |
| fa / fb / fc / fd | SPEAK_TO_CHAT_CONFIG family in V1; ASC button mode / S2C config in V2 | 0c |
| 46 / 47 / 48 / 49 | VOICE_NOTIFICATIONS get/ret/set/notify | **0e** |

**V2-specific codes** (`SVC/protocol/impl/v2/PayloadTypeV2.java`). V2 still uses the V1 codes for ASC (0x66), EQ (0x56), the touch family (0xd6), upsampling (0xe6), general settings (0xf6) and 0xfa-0xfd.

| Code | Name |
|---|---|
| 12 / 13 / 15 | AUDIO_CODEC req/reply/notify |
| 22 / 23 / 25 | BATTERY req/reply/notify |
| 24 | POWER_SET (power off) |
| 26 / 27 / 28 / 29 | AUTOMATIC_POWER_OFF get/ret/set/notify |
| 96 / 97 / 98 / 99 | SYSTEM_CONTROL get/ret/set/notify (reboot 0x16, connect-two-devices 0x06, service-link apply) |
| b6 / b7 / b8 / b9 | SERVICE_LINK get/ret/set/notify |
| f8 | FACTORY_RESET_SET (`f8 09 01`) |
| fa / fb / fc / fd | AMBIENT_SOUND_CONTROL_BUTTON_MODE get/ret/set/notify (sub 03); sub 0c = speak-to-chat config |

### 3.4 Init sequence

Sources: `SVC/SonyHeadphonesSupport.initializeDevice`, `SVC/SonyHeadphonesProtocol.encodeInit` and `decodeResponse`, `SonyProtocolImplV1.handleInitResponse`, `SonyProtocolImplV2.handleInitResponse`.

1. After the socket connects: clear the reassembly buffer, send **INIT** = `MT 0x0c`, seq 0, payload **`00 00`**, set the state to INITIALIZING, and schedule a retry in 1250 ms.
2. The device sends an ACK and an **INIT_REPLY** (`MT 0x0c`, `payload[0] == 0x01`). Gadgetbridge ACKs it.
3. **Protocol version detection** (with `pref_protocol_version == auto`):

   | Init reply payload length | Version | Examples in source comments |
   |---|---|---|
   | 4 | **v1** | WH-1000XM3 `01:00:40:10`; WF-SP800N 1.0.1 `01:00:70:00`; WI-SP600N `01:00:40:10` |
   | 8 | **v2** | WF-1000XM4 1.1.5 `01:00:01:00:00:00:00:00`; LinkBuds S 2.0.2 `01:00:03:00:00:07:00:00`; **WH-1000XM5 1.1.3 `01:00:03:00:00:00:00:00`**; WF-1000XM5 2.0.1 `01:00:03:00:10:04:00:00`; LinkBuds 1.0.3 `01:00:02:00:10:00:00:00` |
   | other | error and stop | — |

   - The meaning of the individual bytes in the init reply is **UNVERIFIED**; Gadgetbridge does not parse them.
   - The result is stored in pref `PREF_SONY_ACTUAL_PROTOCOL_VERSION`.
4. `handleInitResponse` builds the read list: `getFirmwareVersion()`, then `getAudioCodec()`, then, for each capability in this **fixed order** that the model supports, the matching getter:
   BatterySingle, BatteryDual, BatteryDual2, BatteryCase, AmbientSoundControl, AmbientSoundControl2, AncOptimizer, AudioUpsampling, AudioLDAC, ButtonFunctionNcAmbient, ButtonModesLeftRight, VoiceNotifications, VoiceAssistantFunction, AutomaticPowerOffWhenTakenOff, AutomaticPowerOffByTime, TouchSensorSingle, EqualizerSimple, EqualizerWithCustomBands, SoundPosition, SurroundMode, SpeakToChatEnabled, SpeakToChatConfig, PauseWhenTakenOff, AmbientSoundControlButtonMode, QuickAccess, Volume, AdaptiveVolumeControl, WideAreaTap, ConnectTwoDevices, CaptureVoiceDuringCall, ServiceLink.
   - V2 then appends the voice-notification volume read (`0e: 46 20`) if VoiceNotifications is supported.
5. The requests are sent one per ACK. When the queue is empty, the state becomes INITIALIZED.

- **No capability negotiation.** Gadgetbridge does **not** query the device's support-function list. INIT_2 (`06`/`07`) is defined but unused. Capabilities are static per model (§4), with a user override (`PREF_OVERRIDE_FEATURES_ENABLED` / `_LIST`, `DEV/SonyHeadphonesCoordinator.kt` `getCapabilities`).

**Effective XM6 init traffic** (v2 assumed), derived from the XM6 capability set:

`04 02` (fw) → `12 02` (codec) → `22 00` (battery single) → `66 15` (ASC) → `26 05` (auto power off) → `f6 0c` (S2C enabled) → `fa 0c` (S2C config) → `26 01` (pause-when-taken-off, see the caveat in §5.2).

### 3.5 Request and response reference

`S` = SET payload, `G` = GET payload, `R` = RET/NOTIFY layout. Indices refer to payload bytes.

#### Device info, battery, codec, power

| Feature | V1 | V2 |
|---|---|---|
| FW version | G `04 02`; R `05 02 <len> <ASCII…>` | Same as V1 |
| Battery | G `10 <type>`; R `11`/`13 <type> <level> <charging 1/0> [<R level> <R charging>]` | G `22 <type>`; R `23`/`25` with the same layout |
| Battery type byte | SINGLE `00`, DUAL `01`, CASE `02` (DUAL2 sent as `01`) | SINGLE `00`, DUAL2 `01`, DUAL `09`, CASE `0a` |
| Audio codec | G `18 00`; R `19`/`1b 00 <codec>` | G `12 02`; R `13`/`15 02 <codec>` |
| Codec IDs | UNKNOWN 00, SBC 01, AAC 02, LDAC 10, APTX 20, APTX_HD 21 (`.../v1/params/AudioCodec.java`) | Same |
| Power off | `22 00 01` | `24 03 01` |
| Reboot | `98 01 02 01` | `98 00 16 01` |
| Factory reset | n/a | `f8 09 01` |
| Volume (0..30) | G `a6 01 20`; S `a8 01 20 <vol>`; R `a7`/`a9 01 20 <vol>` | Not implemented |

#### Sound settings

| Feature | V1 | V2 |
|---|---|---|
| DSEE / upsampling | G `e6 02`; S `e8 02 00 <01 on/00 off>`; R `e7`/`e9 02 00 <v>` | G `e6 01`; S `e8 01 <01/00>`; R `e7`/`e9 01 <v>` |
| LDAC / connection quality | G `e6 01`; S `e8 01 00 <00 = LDAC on / 01 = off>` | G `e6 02`; S `e8 02 <00 = quality(LDAC) / 01 = stable>` |
| Sound position | G `46 02`; S `48 02 <code>`; R `47`/`49 02 <code>` | Not implemented |
| Surround (VPT) | G `46 01`; S `48 01 <code>` | Not implemented |
| NC optimizer | Start/cancel `84 01 00 <01/00>`; status R `85 xx xx <status>`; state G `86 01`, R `87`/`89 01 01 01 01 <pressure×10>` | Not implemented |

#### Wear detection and general settings

| Feature | V1 | V2 |
|---|---|---|
| Auto power off | G `f6 04`; S `f8 04 01 <b1> <b2>`; R `f7`/`f9 04 01 <b1> <b2>` | G `26 05`; S `28 05 <b1> <b2>`; R `27`/`29 05 <b1> <b2>` |
| Pause when taken off | G `f6 03`; S `f8 03 00 <01 on/00 off>` | G `26 01` (see caveat); S `f8 01 <00 on/01 off>` (**inverted**); R `f7`/`f9 01 <00 on/01 off>` |
| Touch sensor | G `d6 d2`; S `d8 d2 01 <01 on/00 off>` | G `d6 d2`; S `d8 d2 00 <00 on/01 off>` (**inverted**) |
| NC/AMB button function (XM3) | G `d6 d1`; S `d8 d1 02 <00 ASC / 01 Google / 02 Alexa>`, then reboot | n/a |
| Wide area tap | n/a | G `d6 d1`; S `d8 d1 00 <00 on/01 off>` |
| Capture voice during call | n/a | G `d6 d3`; S `d8 d3 00 <00 on/01 off>` |
| Button modes L/R | G `f6 06`; S `f8 06 02 <L> <R>` | G `f6 03`; S `f8 03 02 <L> <R>` |
| Button mode codes | OFF ff, ASC 00, PLAYBACK 20, VOLUME 10 | Same; ASC is encoded `35` if WindNoiseReduction or NoNoiseCancelling, else `00`. Decode accepts `00` or `35`. |
| Quick access | n/a | G `f6 0d`; S `f8 0d 02 <double> <triple>` (00 off, 01 Spotify) |
| Voice assistant | n/a | G `f6 04`; S `f8 04 <ff off / 30 device / 31 Google / 32 Alexa>`, then reboot |
| Adaptive volume | n/a | G `f6 0a`; S `f8 0a <00 on/01 off>` |
| ASC button mode | n/a | G `fa 03`; S `fc 03 01 <00 if ASC2 else 35> 01 00 <mode>`. Modes: NC_AS_OFF 01, NC_AS 02, NC_OFF 03, AS_OFF 04. |
| Service link | n/a | G `b6 10`; S `b8 10 <00 on/01 off>`, plus apply `98 00 <29 on / 1e off> 01` |
| Connect two devices | n/a | Implemented as WAT set (`d8 d1 00 …`) plus commit `98 00 06 01`. State is read via `d6 d1`. Verified only on WH-ULT900N; **UNVERIFIED** on the XM series. |

#### Speak-to-chat and voice guidance

| Feature | V1 | V2 |
|---|---|---|
| S2C enable | G `f6 05`; S `f8 05 01 <01 on/00 off>`; R `f7`/`f9 05 01 <v>` | G `f6 0c`; S `f8 0c <00 on/01 off> 01` (**inverted**); R `f7`/`f9 0c <00 on/01 off> xx` |
| S2C config | G `fa 05`; S `fc 05 00 <sens> <focusVoice> <timeout>`; R `fb`/`fd 05 00 <sens> <focus> <timeout>` | G `fa 0c`; S `fc 0c <sens> <timeout>`; R `fb`/`fd 0c <sens> <timeout>` (no focus byte) |
| S2C codes | Sensitivity: AUTO 00, HIGH 01, LOW 02. Timeout: SHORT 00, STANDARD 01, LONG 02, OFF 03 (`DEV/prefs/SpeakToChatConfig.java`). | Same |
| Voice notifications (MT 0e) | G `46 01 01`; S `48 01 01 <01 on/00 off>` | G `46 01`; S `48 01 <00 on/01 off>`; volume G `46 20`, S `48 20 <int8 −2..+2> 00` |

Evidence for the "inverted" flags: the source comments say "this is reversed on V2...?" and "TODO it's reversed?", and the tests confirm the vectors. Examples:
- `TEST/v2/SonyProtocolImplV2Test.java`: `setSpeakToChatEnabled(true) = f8:0c:00:01`, `setPauseWhenTakenOff(true) = f8:01:00`, `setVoiceNotifications(true) = 48:01:00`.

**Auto power off codes** (`DEV/prefs/AutomaticPowerOff.java`):

| Mode | b1 b2 |
|---|---|
| OFF | 11 00 |
| WHEN_TAKEN_OFF | 10 00 |
| AFTER_5_MIN | 00 00 |
| AFTER_30_MIN | 01 01 |
| AFTER_1_HOUR | 02 02 |
| AFTER_3_HOUR | 03 03 |

**Sound position codes:** OFF 00, FRONT_LEFT 01, FRONT_RIGHT 02, FRONT 03, REAR_LEFT 11, REAR_RIGHT 12.

**Surround codes:** OFF 00, OUTDOOR_STAGE 01, ARENA 02, CONCERT_HALL 03, CLUB 04.

**NC optimizer status codes:** NOT_RUNNING 00, WEARING_CONDITION 01, ATMOSPHERIC_PRESSURE 02, ANALYZING 10, FINISHED 11.

---

## 4. Per-model coordinators and capability matrix

**Capability enum** (`DEV/SonyHeadphonesCapabilities.java`):
AdaptiveVolumeControl, AmbientSoundControl, AmbientSoundControl2, AmbientSoundControlButtonMode, AncOptimizer, AudioSettingsOnlyOnSbcCodec, AudioLDAC, AudioUpsampling, AutomaticPowerOffByTime, AutomaticPowerOffWhenTakenOff, BatteryCase, BatteryDual, BatteryDual2, BatterySingle, ButtonModesLeftRight, ButtonFunctionNcAmbient, CaptureVoiceDuringCall, ConnectTwoDevices, EqualizerSimple, EqualizerWithCustomBands, NoNoiseCancelling, PauseWhenTakenOff, PowerOffFromPhone, QuickAccess, ServiceLink, SoundPosition, SpeakToChatConfig, SpeakToChatEnabled, SpeakToChatFocusOnVoice, SurroundMode, TouchSensorSingle, VoiceAssistantFunction, VoiceNotifications, Volume, WideAreaTap, WindNoiseReduction.

**Coordinator files:** `DEV/coordinators/SonyWH1000XM{2,3,4,5,6}Coordinator.java`.
- All extend `DEV/SonyHeadphonesCoordinator.kt` (`AbstractBLClassicDeviceCoordinator`, connection type BT_CLASSIC).
- None override `preferServiceV2()`.
- Only XM6 sets `isExperimental() = true`, with the comment "Battery, equalizer, and quite a few others probably not working".

**MDR-1000X:** **not present.** There is no coordinator, no `DeviceType` entry, and no "MDR" match anywhere in `model/DeviceType.java` or the Sony paths. The website does not list it either. Supporting it is new work. **UNVERIFIED:** it probably speaks MDR v1 like the XM2.

### Capability matrix (WH-1000X series)

Legend: ✔ = enabled in the coordinator; TODO = commented out as TODO; — = absent.

| Capability | XM2 | XM3 | XM4 | XM5 | XM6 |
|---|---|---|---|---|---|
| Protocol (auto-detected by init reply length) | v1 (inferred; V1-only features) **UNVERIFIED** | **v1** (init reply `01:00:40:10` in source comment) | v1 (inferred: uses V1-only AncOptimizer and S2C focus) **UNVERIFIED** | **v2** (init reply `01:00:03:00:00:00:00:00`, fw 1.1.3) | v2 (inferred) **UNVERIFIED**; the device was never tested |
| BatterySingle | ✔ | ✔ | ✔ | ✔ | ✔ |
| AmbientSoundControl | ✔ (V1 `66 02`) | ✔ | ✔ | ✔ (V2 `66 15`) | ✔ (V2 `66 15`) |
| WindNoiseReduction | ✔ | ✔ | ✔ | — | — |
| AmbientSoundControl2 | — | — | — | — | — |
| AncOptimizer | ✔ | ✔ | ✔ | — | — |
| EqualizerWithCustomBands (5 bands + Clear Bass) | ✔ | ✔ | ✔ | ✔ | **TODO "9 bands"** |
| AudioUpsampling (DSEE) | ✔ | ✔ | ✔ | TODO | TODO ("DSEE Extreme") |
| AudioLDAC toggle | — | ✔ | — | — | — |
| AudioSettingsOnlyOnSbcCodec | ✔ | ✔ | — | — | — |
| SoundPosition / SurroundMode | ✔ / ✔ | ✔ / ✔ | — | — | — |
| ButtonFunctionNcAmbient | — | ✔ | — (TODO "CUSTOM button") | — | — |
| TouchSensorSingle | — | ✔ | ✔ | TODO | TODO |
| SpeakToChatEnabled / Config | — | — | ✔ / ✔ | ✔ / ✔ | ✔ / ✔ |
| SpeakToChatFocusOnVoice | — | — | ✔ | ✔ (no effect in V2 encoding) | — |
| PauseWhenTakenOff (wear detection) | — | — | ✔ | ✔ | ✔ |
| AutomaticPowerOffWhenTakenOff | — | — | ✔ | ✔ | ✔ |
| AutomaticPowerOffByTime | — | ✔ | — | — | — |
| VoiceNotifications | — | ✔ | ✔ | ✔ | TODO |
| Volume | — | ✔ | — | — | — |
| QuickAccess | — | — | — | ✔ | — |
| PowerOffFromPhone | — | — | — | ✔ | — |
| AmbientSoundControlButtonMode | — | — | — | TODO | — |
| ConnectTwoDevices (multipoint) | — | — | TODO | TODO | — |
| Adaptive NC / auto ANC | — | — | — | TODO ("automatic ANC depending on state") | — |

**Website status** (gadgetbridge.org Sony page):

| Model | Status | Tested firmware | Missing features per the page |
|---|---|---|---|
| XM2 | Highly supported | fw 4.5.2 | "Missing features are unknown" |
| XM3 | Highly supported | fw 4.5.2 | Notification and voice guide language, firmware updates |
| XM4 | Highly supported | fw 2.5.0, "not extensively tested" | Power off, CUSTOM button, two-device, S2C settings beyond enable, voice guide language |
| XM5 | Partially supported | fw 1.1.3 | Multipoint, adaptive NC, power-off from app, upsampling, ASC button mode, touch sensor, Spotify, voice assistant |
| XM6 | Partially supported, experimental | Added "without access to the device" | DSEE Extreme, voice notifications, touch sensor, **equalizer** |

**Out of scope but present:** WF-1000XM3/XM4/XM5, WF-SP800N, WF-C500/C510/C700N/C710N, LinkBuds, LinkBuds S, WH-CH720N, WH-ULT900N, WI-C100, WI-SP600N.
- WF models use BatteryDual/BatteryDual2 + BatteryCase and ButtonModesLeftRight.
- Only LinkBuds, LinkBuds S and ULT900N set `preferServiceV2() = true`.
- WF-1000XM4 is detected as v2 by its init reply but has EqualizerSimple only.

---

## 5. Equalizer

Sources: `DEV/prefs/EqualizerPreset.java`, `DEV/prefs/EqualizerCustomBands.java`, `SonyProtocolImplV1.java`/`V2.java`, and the EQ tests in both test files.

### 5.1 Bands, ranges and presets

- **Bands:** exactly **5** bands plus **Clear Bass**. The `EqualizerCustomBands` constructor throws unless `bands.size() == 5`.
- **Frequencies**, from the pref keys and labels: **400 Hz, 1 kHz, 2.5 kHz, 6.3 kHz, 16 kHz** (`PREF_SONY_EQUALIZER_BAND_400/1000/2500/6300/16000`), plus `PREF_SONY_EQUALIZER_BASS` for Clear Bass.
- **Range:** −10..+10 per band and for Clear Bass. **Wire encoding:** `value + 10`, giving `0x00..0x14`. The UI seekbars are 0..20 (`DEV/SonyHeadphonesDeviceSettings.kt` lines 291-329).

**Presets** (byte = preset ID):

| Preset | ID | Preset | ID |
|---|---|---|---|
| OFF | `00` | TREBLE_BOOST | `15` |
| BRIGHT | `10` | BASS_BOOST | `16` |
| EXCITED | `11` | SPEECH | `17` |
| MELLOW | `12` | MANUAL | `a0` |
| RELAXED | `13` | CUSTOM_1 | `a1` |
| VOCAL | `14` | CUSTOM_2 | `a2` |

### 5.2 Wire layouts

| Operation | V1 | V2 |
|---|---|---|
| Get | `56 01` | `56 00` |
| Set preset | `58 01 <preset> 00` | `58 00 <preset> 00` |
| Set custom bands | `58 01 **ff** 06 <bass> <400> <1k> <2.5k> <6.3k> <16k>` (10 bytes) | `58 00 **a0** 06 <bass> <400> <1k> <2.5k> <6.3k> <16k>` (10 bytes) |
| RET/NOTIFY parse | `57`/`59 <01> <preset> <count=06> <bass> <b1..b5>`. **Length must be exactly 10**, otherwise the reply is dropped. | Same parser (V2 delegates to V1). `57`/`59 00 <preset> 06 <bass> <b1..b5>` |

- `06` is the number of following values (Clear Bass + 5 bands). Gadgetbridge does not validate it; it hard-codes the 5+1 layout.
- V1 custom bands use preset byte `ff`; V2 uses `a0` (MANUAL).
- **V2 RET vectors from the tests** (`TEST/v2/...Test.java` `handleEqualizer`, frame payloads):

| Preset | Payload |
|---|---|
| OFF | `59 00 00 06 0a 0a 0a 0a 0a 0a` |
| BRIGHT | `59 00 10 06 09 0a 0f 11 11 13` |
| EXCITED | `59 00 11 06 12 09 0b 0a 0d 0f` |
| MELLOW | `59 00 12 06 07 09 08 07 06 04` |
| RELAXED | `59 00 13 06 01 07 09 07 05 02` |
| VOCAL | `59 00 14 06 0a 10 0e 0c 0d 09` |
| TREBLE_BOOST | `59 00 15 06 0a 0a 0a 0c 10 14` |
| BASS_BOOST | `59 00 16 06 11 0a 0a 0a 0a 0a` |
| SPEECH | `59 00 17 06 00 0e 0d 0b 0c 00` |
| MANUAL / CUSTOM_1 / CUSTOM_2 | `59 00 a0`/`a1`/`a2 06 0a 0a 0a 0a 0a 0a` |

  These show the device reporting each preset's built-in curve. For example, BRIGHT is Clear Bass −1 and bands 0, +5, +7, +7, +9.
- On devices with `AudioSettingsOnlyOnSbcCodec` (XM2, XM3), the EQ, sound position and surround settings are hidden unless the codec is SBC (`sonyAudioSettingsVisible` in `DEV/SonyHeadphonesDeviceSettings.kt`).

**Caveat on V2 pause-when-taken-off:** V2 `getPauseWhenTakenOff()` sends `26 01` (AUTOMATIC_POWER_OFF_GET family). The reply parser for that family (`handleAutomaticPowerOff`) rejects any sub other than `05`, while the pause handler expects `f7`/`f9 01 …`. So the initial read may never be parsed. This looks like a Gadgetbridge bug or quirk (**UNVERIFIED** on hardware).

### 5.3 XM6 equalizer status

**Not implemented.**
- In `DEV/coordinators/SonyWH1000XM6Coordinator.java`, `EqualizerWithCustomBands` is commented out as `// TODO 9 bands SonyHeadphonesCapabilities.EqualizerWithCustomBands`.
- `isExperimental()` is true with the comment "Battery, equalizer, and quite a few others probably not working".
- The website lists Equalizer under the XM6's missing features.

Consequences:
- No EQ GET is sent at init, and no EQ UI is shown for XM6.
- Even if forced via the override-features setting, the parser requires a 10-byte reply and exactly 5 bands. Any XM6 EQ reply with more bands would be dropped as "Unexpected payload length".

**Band count:**
- The Gadgetbridge developers' note says **9 bands**. Your brief expected **10 bands**. Gadgetbridge contains no capture, test vector or code that settles this.
- It is possible that "9 bands + Clear Bass" equals 10 values, or that the Sony app shows 10 bands. **UNVERIFIED.** Capture the XM6 EQ RET (`57 00 …` with `payload[3]` = count) before designing the UI.
- The existing pattern suggests the wire format is `58 00 <preset> <count> <values…>` with a `+10` offset. That is a reasonable starting hypothesis, also **UNVERIFIED** for XM6.

---

## 6. Noise cancelling and ambient sound

Sources: `SonyProtocolImplV1.setAmbientSoundControl`/`handleAmbientSoundControl`, `SonyProtocolImplV2` overrides, `DEV/prefs/AmbientSoundControl.java`, `TEST/v1/SonyProtocolImplV1Test.java` lines 27-70.

**Modes** (`AmbientSoundControl.Mode`): OFF, NOISE_CANCELLING, WIND_NOISE_REDUCTION, AMBIENT_SOUND.

**Level:**
- The ambient level is 0..20 on the wire.
- The UI seekbar is 0..19 and is stored as `pref + 1`, so the UI sends 1..20 (`AmbientSoundControl.fromPreferences`; seekbar `max = 19`).
- The ambient level and focus-on-voice are only written to prefs when the mode is AMBIENT_SOUND.

### 6.1 V1 layout (WH-1000XM2, XM3, XM4)

Inquired type is always **`0x02`**. GET is `66 02`. SET and RET are 8 bytes.

| Index | SET (0x68) | RET/NOTIFY (0x67/0x69) parse |
|---|---|---|
| 0 | `68` | `67`/`69` |
| 1 | `02` (inquired type) | `02` |
| 2 | `00` = OFF, `11` = ON | `00` = OFF, `01` = ON. The SET writes `11` but the parser expects `01`; the meaning of the `1x` nibble is **UNVERIFIED**. |
| 3 | `00` = NC/ASM model; `02` = model with wind (WindNoiseReduction capability) | Same |
| 4 | If [3]=00: `01` NC, `00` ambient/off. If [3]=02: `02` NC, `01` wind, `00` ambient/off. | Same mapping |
| 5 | `01` (constant, unknown) | ignored |
| 6 | focus on voice `00`/`01` | Same |
| 7 | ambient level (0..20); `00` when NC or wind | 0..20 |

Test vectors with wind capability (`68:02:…`):

| Mode | Payload |
|---|---|
| OFF | `68 02 00 02 00 01 00 00` |
| AMBIENT, level 10, focus | `68 02 11 02 00 01 01 0a` |
| NC | `68 02 11 02 02 01 00 00` |
| WIND | `68 02 11 02 01 01 00 00` |

Without wind: NC is `68 02 11 00 01 01 00 00`.

### 6.2 V2 layout (WH-1000XM5, XM6, WF-1000XM4/XM5, LinkBuds, ULT900N…)

**Inquired-type byte** (`payload[1]`):

| Byte | When used |
|---|---|
| `0x15` | Standard NC/ASM. Used when the model has neither `WindNoiseReduction` nor `AmbientSoundControl2`. **This is what Gadgetbridge sends for WH-1000XM5 and XM6.** |
| `0x17` | Used when `WindNoiseReduction` (for example WF-1000XM4) or `AmbientSoundControl2` (WF-C510/C700N/C710N, WH-ULT900N). The packet is 8 bytes with wind, 7 without. |
| `0x22` | Accepted only by the parser, meaning "no noise cancelling, ambient only". Never requested. |
| `0x19` | **Not present anywhere in Gadgetbridge.** If newer firmware or the XM6 uses `0x19` (for example for adaptive or "auto NC" variants), Gadgetbridge does not handle it. **UNVERIFIED.** |

GET is `66 <15|17>`. SET is 7 bytes (8 with wind):

| Index | SET, 0x15 (XM5/XM6) | SET, 0x17 with wind |
|---|---|---|
| 0 | `68` | `68` |
| 1 | `15` | `17` |
| 2 | `01` (comment: "0x00 while dragging the slider?") | `01` |
| 3 | `01` = NC/ASM on, `00` = OFF | Same |
| 4 | `01` = AMBIENT, `00` = NC (or off) | Same |
| 5 | focus on voice `00`/`01` | `03` = WIND, `02` = otherwise |
| 6 | ambient level 0..20 (always sent, even in NC) | focus |
| 7 | — | level |

Example XM5/XM6 payloads:

| Mode | Payload |
|---|---|
| NC | `68 15 01 01 00 00 <lvl>` |
| Ambient, focus on, level 10 | `68 15 01 01 01 01 0a` |
| Off | `68 15 01 00 00 <focus> <lvl>` |

**RET/NOTIFY parse** (`67`/`69`, length 6..8):
- `payload[3]`: `00` = OFF, `01` = on.
- If on:
  - With type `17` and length > 7: `payload[5]` of `03` or `05` means WIND; `02` means use `payload[4]` (`00` NC, `01` AMBIENT).
  - With type `22`: AMBIENT.
  - Otherwise: `payload[4]` `00` = NC, `01` = AMBIENT.
- Focus is `payload[len-2]`; level is `payload[len-1]`.

**Special handling for XM5 and XM6:** none beyond using type `0x15` and the 7-byte form.
- No adaptive NC, auto-NC or "NC optimizer" support for V2 (`getNoiseCancellingOptimizerState()` returns null in V2).
- The XM5 coordinator has a TODO for "automatic ANC depending on state".
- **UNVERIFIED** whether the XM6 actually accepts or reports `0x15`, or requires another type such as `0x17` or `0x19`. Gadgetbridge never tested on an XM6.

V2 `encodeButtonMode` uses `0x35` for "ASC" when wind or NoNoiseCancelling is present. `setAmbientSoundControlButtonMode` uses `0x00` with ASC2, else `0x35`.

---

## 7. Model detection

- **By Bluetooth device name only.**
  - `AbstractDeviceCoordinator.supports(GBDeviceCandidate)` returns `getSupportedDeviceName().matcher(candidate.getName()).matches()` (`devices/AbstractDeviceCoordinator.java` lines 145-160).
  - Patterns: `.*WH-1000XM2.*`, `.*WH-1000XM3.*`, `.*WH-1000XM4.*`, `.*WH-1000XM5.*`, `.*WH-1000XM6.*`. Others include `^LinkBuds$`, `.*LinkBuds S.*`, `^Sony ULT|ULT WEAR.*$` and `(?!LE_).*WF-C710N$`.
  - A user-renamed device would not match. Users can also add a device manually.
- **Device type registry:** `model/DeviceType.java` lines 1060-1076, from `SONY_WH_1000XM3` to `SONY_WH_1000XM6`, among others.
- **Protocol version** is decided at runtime from the INIT reply payload length (4 → v1, 8 → v2), and can be overridden by pref (§3.4).
- **Service UUID** is decided from cached SDP UUIDs (§2.2).
- **Capabilities** are static per coordinator, with no device query, and can be overridden by user pref.
- No model ID or "support function" inquiry is used. `INIT_2` (`06`/`07`) and `JSON` (`c4`/`c9`) are defined but not used to identify the model.

---

## 8. Things not to copy, and lessons learned

1. **UUID handling:** there is no null check on `getUuids()`, and `fetchUuidsWithSdp()` is never called.
2. **ACK/seq bookkeeping is loose (§2.6):**
   - There is no per-command timeout or resend.
   - Config writes bypass the queue.
   - `pendingAcks` can go negative.
   - INITIALIZED can be set before the INIT reply.
3. **Inverted booleans in V2.** Several V2 booleans are inverted (`00` = enabled): S2C enable, pause-when-taken-off, touch sensor, voice notifications, adaptive volume, wide area tap, capture voice and service link. Treat this as a protocol convention for V2 "general setting" toggles.
4. **XM5 S2C focus-on-voice:** the capability is set but V2 has no focus byte, so it is a dead UI toggle.
5. **V2 pause-when-taken-off read** uses `26 01`, which mismatches its reply parser (`f7`/`f9 01`). Likely a bug.
6. **XM6:** EQ, DSEE Extreme, touch sensor and voice notifications are not implemented. The whole model is experimental and untested on hardware. Treat every XM6 byte as **UNVERIFIED** until captured, ideally with an Android HCI snoop log of the Sony Sound Connect app.
7. **MDR-1000X** is entirely absent from Gadgetbridge.
8. **Reassembly buffer** is 2048 bytes. A JSON or log payload could exceed it; use a larger buffer that can grow.

## 9. File index (relative to the clone root)

| Area | Files |
|---|---|
| Transport | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/{BtBRQueue,AbstractBTBRDeviceSupport,TransactionBuilder}.java`, `.../service/btbr/actions/WriteAction.java`, `.../service/serial/{AbstractSerialDeviceSupportV2,AbstractHeadphoneSerialDeviceSupportV2}.java`, legacy `.../service/btclassic/BtClassicIoThread.java` |
| Sony driver | `SVC/SonyHeadphonesSupport.java`, `SVC/SonyHeadphonesProtocol.java`, `SVC/deviceevents/SonyHeadphonesEnqueueRequestEvent.java` |
| Framing | `SVC/protocol/{Message,MessageType,Request}.java` |
| Protocol | `SVC/protocol/impl/AbstractSonyProtocolImpl.java`, `SVC/protocol/impl/v1/{SonyProtocolImplV1,PayloadTypeV1}.java`, `SVC/protocol/impl/v1/params/{AudioCodec,BatteryType,NoiseCancellingOptimizerStatus,VirtualSoundParam}.java`, `SVC/protocol/impl/v2/{SonyProtocolImplV2,PayloadTypeV2}.java` |
| Models | `DEV/SonyHeadphonesCoordinator.kt`, `DEV/SonyHeadphonesCapabilities.java`, `DEV/coordinators/*.java` |
| Settings and UI | `DEV/SonyHeadphonesDeviceSettings.kt`, `DEV/SonyHeadphonesQuickControls.kt`, `DEV/prefs/*.java` |
| Tests (byte vectors) | `TEST/v1/SonyProtocolImplV1Test.java`, `TEST/v2/SonyProtocolImplV2Test.java`, `TEST/SonyTestUtils.java` |
| License | `LICENSE`, `README.md` (§ Code Licenses) |
