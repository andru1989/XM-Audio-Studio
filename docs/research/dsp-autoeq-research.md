> Research snapshot from 2026-10-21 (Etapa 0). Paths under `scratchpad/` refer to temporary clones
> that are not part of this repository.

# DSP / AutoEQ research for XM Audio Studio

Date: 2026-10-21. Scope: (a) an internal "AutoEQ" that fits a target correction curve onto the WH-1000XM6's fixed hardware graphic-EQ bands; (b) a future system-wide parametric DSP EQ on macOS.

Reference clones (`git clone --depth 1`, read-only):

| Repo | Path | Commit (HEAD) |
|---|---|---|
| wabsto1/ParaEQ | `scratchpad/refs/ParaEQ` | `51d6f6d` (2026-07-09) |
| jaakkopasanen/AutoEq | `scratchpad/refs/AutoEq` (`--filter=blob:none --sparse`. Code plus the Sony WH-1000XM result and measurement files were checked out. The other ~75k data files were left out.) | `7ae0f56` (2025-07-20, `master`) |

Artifacts written:

- `(not stored in the repo)`: exact copies of `results/Kuulokenurkka/over-ear/Sony WH-1000XM6/` (README.md, ParametricEQ.txt, FixedBandEQ.txt, GraphicEQ.txt, CSV). The `other-sources/` subfolder holds the Super Review XM6 results (Bluetooth and analog cable).
- `scratchpad/scripts/fit_fixed_band.py`: a library-free (pure Python, no numpy) reference implementation of the recommended fixed-band fitter. It was validated against AutoEq's own FixedBandEQ output (§4.8). `scripts/vectors.py` prints the RBJ test vectors.

(All paths in this report are relative to `/private/tmp/claude-501/-Users-andru1989-Documents-Proyectos-XM-Audio-Studio/8d43e2e4-55d7-46a4-b7cd-015f337907a2/scratchpad/`.)

---

## 0. Summary

- **ParaEQ** uses the **MIT** license (Copyright (c) 2026 wabsto1). It captures system audio with the **Core Audio process-tap API** (`CATapDescription(stereoGlobalTapButExcludeProcesses:)`, `.mutedWhenTapped`, own PID excluded). The tap goes into a **private aggregate device** whose main sub-device is the real output device. A **single IOProc** reads the tap as input and writes the processed audio to the device output. It needs no virtual driver, no BlackHole and no AudioServerPlugIn, and it never changes the default output. The filters are RBJ cookbook biquads run through `vDSP_biquadm` with ramped live coefficient updates. Requirements: macOS 14.4+, one hardened-runtime entitlement (`com.apple.security.device.audio-input`) and the "System Audio Recording" TCC prompt (`NSAudioCaptureUsageDescription`).
  - **Verdict:** the DSP files (biquad, response, parser, limiter, FIR/convolver, spectrum) are high quality and reusable as they are under MIT. Treat the engine as a reference design plus a list of documented platform gotchas, not as drop-in code.
- **AutoEq**: the software is **MIT** (Copyright (c) 2018-2022 Jaakko Pasanen). The `measurements/` and `results/` folders contain **no separate license or terms file**. The per-source READMEs are only link lists. The data comes from third parties (Kuulokenurkka, oratory1990, crinacle, Rtings, Super Review…) and its redistribution terms are **undocumented**. Don't bundle it in a commercial app without permission. Fetching it at runtime with attribution (ParaEQ's approach) is the lower-risk path.
- **XM6 data in AutoEq**: Kuulokenurkka (recommended entry, ANC-on, KB501x+711 rig) and Super Review (Bluetooth and analog cable). No oratory1990, crinacle or Rtings XM6 is in the repo snapshot.
- **Fitting algorithm (recommended)**: weighted, box-constrained least squares on a 240-point log grid. It uses projected Gauss–Newton outer iterations with a projected Gauss–Seidel inner solver (both deterministic, under 100 lines in either language), followed by greedy integer-step quantization. It reproduces AutoEq's FixedBandEQ gains for the XM6 to within about 0.3 dB, with a slightly lower loss.
- **XM6 hardware EQ facts from sibling reference clones**:
  - `refs/xm6-control` (`SonyHeadphonesKit/Protocol/Commands.swift`) reports **10 bands**, wire byte = gain + 6, **range −6…+6 integer steps**, verified by echo on a live XM6. The band frequencies are not in the protocol.
  - Gadgetbridge's XM6 coordinator has the comment `// TODO 9 bands`, which conflicts with this.
  - Keep the band list, bounds and step size fully configurable.

---

## 1. ParaEQ (https://github.com/wabsto1/ParaEQ)

### 1.1 Identity and license

- `LICENSE`: **MIT License, "Copyright (c) 2026 wabsto1"** (standard MIT text). The README's License section also says "MIT".
- Version: `Info.plist` reports 2.2.0 and `CHANGELOG.md` already lists 2.3.0 (2026-07-09). It has a single maintainer. The README states it was "developed with heavy AI assistance (Claude)" and that DSP changes are covered by tests: 51 DSP tests, 20 feature tests, 31 calibration tests and 41 App Mixer tests, about 143 in total.
- Pure Swift (SwiftPM, `swift-tools-version 5.10`, `platforms: [.macOS("14.4")]`), SwiftUI menu-bar app (`LSUIElement`). The only frameworks are CoreAudio, AudioToolbox and Accelerate, with no third-party dependencies.

### 1.2 System-audio capture (how it works)

From `Sources/AudioEngine.swift` (`createTap()`, `createAggregate()`, `startIO()`) and `docs/ARCHITECTURE.md`:

1. Translate its own PID to a HAL process object (`kAudioHardwarePropertyTranslatePIDToProcessObject`).
2. **Global tap**: `CATapDescription(stereoGlobalTapButExcludeProcesses: [selfProcess] + excludedApps)`, with `isPrivate = true` and `muteBehavior = .mutedWhenTapped`, created by `AudioHardwareCreateProcessTap`. The tap format is read from `kAudioTapPropertyFormat`. Optional per-app "exception" taps (`CATapDescription(stereoMixdownOfProcesses:)`) support the App Mixer.
3. **Private aggregate device** (`AudioHardwareCreateAggregateDevice`) with these keys:
   - `kAudioAggregateDeviceMainSubDeviceKey = <real output UID>`
   - `SubDeviceList = [output]`
   - `kAudioAggregateDeviceTapListKey = [{tap UUID, DriftCompensation: true}, …]`
   - `IsPrivate = true`, `IsStacked = false`, `TapAutoStart = true`

   Gotcha #6: a tap-only aggregate silently produces zeros. The real output **must** be the main sub-device.
4. **One IOProc** (`AudioDeviceCreateIOProcIDWithBlock` on a dedicated serial queue; on macOS 26 the queue must be non-nil) receives the tap as *input* and writes the device *output* in the same callback. There is no ring buffer and no AUHAL/AVAudioEngine.

So it is **not** a virtual driver, not BlackHole and not an AudioServerPlugIn. It is the macOS 14.4+ process-tap API combined with an aggregate device. `Prototypes/TapProto/main.swift` (282 lines) is a minimal standalone version of the same idea and a good starting point.

### 1.3 Output routing

- It follows the system default output, or a user-selected device UID. **The system default device is never changed.**
- HAL listeners on `kAudioHardwarePropertyDefaultOutputDevice` and `kAudioHardwarePropertyDevices` run on a dedicated `listenerQueue`, which avoids a documented deadlock. They are debounced by 0.5 s. The engine restarts (tear down tap and aggregate, rebuild) only when the *effective output UID* changes, because its own aggregate creation also fires device-list notifications.
- Output writing (`startIO`) walks all output buffers and channels, alternating L/R by channel index, assuming Float32. Frames beyond `maxFrames = 4096` are zero-filled.
- If ParaEQ crashes, coreaudiod destroys the tap, the source audio un-mutes and plays unprocessed ("crash-safe by construction").

### 1.4 Sample-rate handling

- Biquad coefficients, FIR design, limiter lookahead and calibration tones are all computed for `tapFormat.mSampleRate` at start. `FrequencyResponse.sampleRate` is set to the same value, so the graph is evaluated at the real rate.
- A listener on the aggregate's `kAudioDevicePropertyNominalSampleRate` triggers a full restart when the rate changes by more than 0.5 Hz (`handleSampleRateChange`). Mixed rates are absorbed by drift compensation on the tap.
- Relevant to the XM6: a Bluetooth A2DP↔HFP switch (microphone in use) changes the device and rate. This path covers it with a restart (about 50 ms gap).

### 1.5 Latency

The README FAQ breaks it down as follows:

- The lookahead limiter always adds 5 ms.
- The FIR stage, present only when a GraphicEQ curve or impulse response is loaded, adds a 512-sample block (about 10.7 ms at 48 kHz).
- Device buffering comes on top. Typical totals are about 5–10 ms for the parametric path and about 16–21 ms with FIR.
- Bluetooth codec latency is extra.

### 1.6 DSP chain and implementation

Signal flow inside the IOProc:

1. stage input (`InputStaging.swift`; handles interleaved and planar input)
2. pre-EQ spectrum ring
3. × preamp
4. optional Mid/Side encode
5. `vDSP_biquadm` cascade
6. optional M/S decode
7. FIR or convolution
8. crossfeed
9. balance and volume
10. lookahead limiter
11. post-EQ spectrum ring
12. output

| Feature | Implementation | File |
|---|---|---|
| Biquad math | **RBJ Audio-EQ-Cookbook**, computed in Double, normalized by a0, coefficient order `[b0 b1 b2 a1 a2]` | `Sources/BiquadEQ.swift` (`BiquadCoefficients.compute`) |
| Realtime filtering | **`vDSP_biquadm`** (multichannel, all sections in one call). Live edits use `vDSP_biquadm_SetTargetsDouble(setup, coeffs, 0.005, 0.05, 0, 0, sections, channels)`, which ramps per sample with no glitches. Argument-order pitfall: gotcha #13. | `BiquadEQ.swift` (`BiquadEQ`) |
| Filter types | Peak, Low Shelf, High Shelf, LP, HP, BP, Notch. Crossovers: BW 6 dB/oct (true first order via bilinear transform), BW 24 dB/oct (Q 0.5412/1.3066), LR12 (one section, Q 0.5), LR24 (2× Q 0.7071) | `Models.swift`, `BiquadEQ.swift` |
| Shelf Q | Uses the cookbook "Q" form `alpha = sin(w0)/(2Q)`. This is the same convention as AutoEq and Equalizer APO's `LSC/HSC … Q`. | `BiquadEQ.swift` |
| Magnitude response | Direct complex evaluation of `|H(e^jw)|` in dB, summed over sections at the engine's sample rate | `BiquadEQ.swift` (`magnitudeDB`), `FrequencyResponse.swift` |
| Preamp | Auto mode sets `preamp = −max(0, peak of summed response on 200 log points)`; manual mode allows ±24 dB. Applied as a linear multiply before the EQ. | `AudioEngine.swift` (`computeAutoPreamp`), `FrequencyResponse.peakGainDB` |
| Limiter | Linked-stereo lookahead: 5 ms lookahead, 60 ms release, ceiling 0.985. Sliding-window minimum (monotonic deque, O(1)), instant attack, exponential release, no allocation | `Limiter.swift` |
| FIR / GraphicEQ | GraphicEQ nodes become a **16384-tap minimum-phase FIR**: log-frequency interpolation, real cepstrum, fold, exp, half-Hann tail window. Runs in a **uniform-partition overlap-save FFT convolver** (512-sample blocks, IRs up to 131072 taps) | `Convolution.swift` (`MinPhaseFIR`, `FIRConvolver`) |
| IR loading | `AVAudioFile`, auto-resampled | `IRLoader.swift` |
| Crossfeed | Chu Moy / Jan Meier style low-passed opposite-channel bleed | `Crossfeed.swift` |
| AutoEQ import | Parses Equalizer APO `Preamp:`, `Filter N: ON|OFF <TYPE> Fc … Gain … Q …` (also `BW Oct`), and `GraphicEQ:` lines. Input is sanitized: finite check, clamping, band cap. | `AutoEQParser.swift` |
| AutoEQ export | Writes `ParametricEQ.txt` in APO format | `AudioEngine.exportEqualizerAPOText` |
| AutoEQ browser | Fetches `https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md` (5 MB cap), then `results/<path>/<name> ParametricEQ.txt` (64 KB cap), **at runtime, only when the user opens the browser** | `AutoEQPickerView.swift` |
| Spectrum analyzer | IO thread writes mono samples into two 2048-sample rings (pre and post); the UI computes a Hann window, `vDSP_fft_zrip` and 120 log bins at 30 fps, with 3 dB/frame release | `Spectrum.swift` |
| A/B | Bypass flag skips all tonal stages; volume and limiter stay active | `AudioEngine.swift` |
| Per-device presets | A preset can be pinned to an output UID and auto-applies when audio routes there | `AudioEngine.deviceProfile` |

### 1.7 Entitlements, permissions, OS, distribution

- `ParaEQ.entitlements` contains only `com.apple.security.device.audio-input = true`. Hardened runtime denies audio input without it, and this one entitlement covers both the tap and the microphone. **There is no `com.apple.security.app-sandbox`**, so the app is not sandboxed. Sandbox and Mac App Store compatibility of process taps is **not demonstrated** by this project; verify it yourself if the Mac App Store matters.
- `Info.plist`:
  - `NSAudioCaptureUsageDescription` drives the "System Audio Recording" TCC prompt.
  - `NSMicrophoneUsageDescription` is only for the calibration wizard.
  - `LSMinimumSystemVersion 14.4`.
- Distribution: Developer ID, hardened runtime and notarization (`scripts/release.sh`).
- TCC is keyed to the signing identity. Ad-hoc rebuilds re-prompt, so use a stable dev certificate (gotcha #4). A headless or CLI-subprocess launch captures **silence with no errors** (gotcha #11).

### 1.8 Reusable files and quality estimate

| File | Lines | Reuse for XM Audio Studio | Quality |
|---|---|---|---|
| `Sources/BiquadEQ.swift` | 277 | **Reuse as-is.** RBJ coefficients, crossover cascades, `vDSP_biquadm` wrapper with live ramped updates and pre-allocated pointer tables. | High (tests cover center gain, shelf asymptotes, slopes, end-to-end vDSP, live update) |
| `Sources/FrequencyResponse.swift` | 60 | Reuse (graph and auto-preamp from the exact coefficients) | High (trivial) |
| `Sources/AutoEQParser.swift` | 120 | Reuse with small fixes. `LS`/`HS` (non-`C`) are mapped to the same shelf as `LSC`/`HSC`, but APO defines them differently. The filter number and `ON` placement are lenient. | Good (parse, sanitize and round-trip tests) |
| `Sources/Limiter.swift` | 103 | Reuse | High |
| `Sources/Convolution.swift` | 322 | Reuse if we want GraphicEQ or IR support | Good (FIR accuracy and convolver identity/delay tests) |
| `Sources/Spectrum.swift` | 150 | Reuse for the analyzer | Good |
| `Sources/AudioDeviceManager.swift` | 107 | Reuse (device enumeration, default output) | Good |
| `Sources/InputStaging.swift` | 51 | Reuse if we tap several streams | Good |
| `Sources/AudioEngine.swift` | 1217 | **Reference only.** It mixes engine, persistence, undo and UI state (`@Observable`). Copy the tap, aggregate and IOProc lifecycle, the listeners and the watchdog into our own engine. | Good, battle-tested on hardware, monolithic |
| `Prototypes/TapProto/main.swift` | 282 | Best minimal starting point for a spike | Prototype |
| `docs/ARCHITECTURE.md` (gotchas 1–15) | — | **Must-read**: HAL listener deadlock, self-triggered restarts, App Nap, TCC and signing, macOS 26 IOProc queue, aggregate shape, stalled aggregates, coreaudiod churn | Very valuable |

MIT requires keeping the copyright and permission notice in copies or substantial portions. Add it to our `THIRD_PARTY_NOTICES`.

### 1.9 Risks to plan for

1. **Feedback loop**: avoided only because ParaEQ excludes **its own PID** from the global tap. Any helper process of ours that plays audio (preview sounds, XPC helper) must also be excluded, or it will be re-captured.
2. **Silent default-output change**: not an issue. ParaEQ never sets the default device, and if the process dies the audio returns unprocessed. Do **not** adopt the older "virtual driver + switch default output" design, which can leave users silent after a crash.
3. **`.mutedWhenTapped` failure modes**:
   - If the aggregate stalls (the IOProc is never called after rapid create/destroy, gotcha #8), the user hears **silence**. ParaEQ runs a 5 s watchdog that restarts once.
   - After heavy tap churn, coreaudiod can stop muting (gotcha #10), and the user hears **double audio** (the original plus the processed copy, comb-filtered). This needs `sudo killall coreaudiod` or a reboot.
   - Design rule: create and destroy taps only on explicit user actions.
4. Other process-tap apps and some DRM paths can bypass the system mix.
5. **Bluetooth (XM6)**: A2DP↔HFP switches change the device and sample rate and force a restart. Bluetooth latency comes on top of DSP latency.
6. **Double correction**: if the hardware EQ (AutoEQ interno) and the Mac DSP EQ both apply the headphone correction, it is applied twice. Make them mutually exclusive, or fit the device EQ first and let the Mac EQ apply only the residual.
7. A boost really increases level. Use auto-preamp plus the limiter by default.

---

## 2. AutoEq (https://github.com/jaakkopasanen/AutoEq)

### 2.1 Software license

`LICENSE` (repo root): **MIT License, "Copyright (c) 2018-2022 Jaakko Pasanen"**. `pyproject.toml` (autoeq 4.1.2) declares `License :: OSI Approved :: MIT License`.

### 2.2 Measurement and result data: license / terms

These findings come from a search of the whole tree for license, copyright, permission, commercial and terms:

- There is **no LICENSE, COPYING or terms file** inside `measurements/` or `results/`. The per-source files (`results/<source>/README.md`, for example `results/Kuulokenurkka/README.md`, `results/oratory1990/README.md`, `results/crinacle/README.md`, `results/Rtings/README.md`) are just link lists. `measurements/<source>/` holds `name_index.tsv` (source URL, original name, normalized name, form, rig) plus CSVs.
- The root README only names the sources ("Collection of headphone measurements as numerical data from oratory1990, crinacle, Innerfidelity, Rtings and legacy headphone.com"). It does not grant or describe any data license.
- `webapp/data/legal/terms-of-service.html` (for autoeq.app) says: *"The service is intended for personal use but also commercial use is permitted."* That covers using the **autoeq.app service**. It does not license third parties' measurements.
- Provenance per `name_index.tsv`:
  - Kuulokenurkka data comes from `kuulokenurkka.squig.link`.
  - Super Review data comes from `superreview.squig.link`.
  - Rtings data comes from `i.rtings.com` JSON (a commercial site with its own terms).
- An **unverified** forum report (headphones.com, Feb 2022) says oratory1990 allows use of their measurements "as long as they do not profit" and objected to a paid app (SoundSource) shipping AutoEQ presets.
- **Conclusion**: the root MIT license nominally covers the repository, but the raw measurements belong to third parties and their terms are undocumented.
  - For a commercial app, **don't bundle the measurements or results**.
  - Prefer one of these:
    1. User-initiated runtime fetch with attribution (ParaEQ's pattern).
    2. Explicit permission from the measurement source (for the XM6, Kuulokenurkka).
    3. Our own measurements.
  - Shipping derived EQ numbers (a handful of gains) is lower risk but still not clearly licensed.
  - Also note: the repo HEAD is from 2025-07-20, so newer measurements may exist only on autoeq.app.

### 2.3 XM6 result files (not reproduced here)

The Kuulokenurkka WH-1000XM6 result (ANC-on measurement, Harman over-ear 2018 target without bass
plus a 6 dB low shelf at 105 Hz) lives at
https://github.com/jaakkopasanen/AutoEq/tree/master/results/Kuulokenurkka/over-ear/Sony%20WH-1000XM6
(ParametricEQ.txt with 10 filters and a -4.4 dB preamp, FixedBandEQ.txt, GraphicEQ.txt, CSV).
The measurement data carries no licence, so its contents are deliberately **not** copied into this
repository (see ADR-0006). Users import these files into the apps themselves.

### 2.4 Other sources: WH-1000XM6 / XM5 / XM4 / XM3 in the repo snapshot

| Model | Source (rig) | results path | measurements path |
|---|---|---|---|
| XM6 | **Kuulokenurkka** (KB501x+711, ANC on) — recommended | `results/Kuulokenurkka/over-ear/Sony WH-1000XM6/` | `measurements/Kuulokenurkka/data/over-ear/Sony WH-1000XM6.csv` |
| XM6 | Super Review (KB006x+711) | `results/Super Review/over-ear/Sony WH-1000XM6/` | `measurements/Super Review/data/over-ear/Sony WH-1000XM6.csv` |
| XM6 (analog cable) | Super Review | `results/Super Review/over-ear/Sony WH-1000XM6 (analog cable)/` | `measurements/Super Review/data/over-ear/Sony WH-1000XM6 (analog cable).csv` |
| XM5 | **oratory1990** — recommended | `results/oratory1990/over-ear/Sony WH-1000XM5/` | `measurements/oratory1990/data/over-ear/Sony WH-1000XM5.csv` |
| XM5 | Rtings (B&K 5128) | `results/Rtings/Bruel & Kjaer 5128 over-ear/Sony WH-1000XM5/` | `measurements/rtings/data/over-ear/Bruel & Kjaer 5128/Sony WH-1000XM5.csv` |
| XM5 | Super Review | `results/Super Review/over-ear/Sony WH-1000XM5/` | `measurements/Super Review/data/over-ear/Sony WH-1000XM5.csv` |
| XM4 | **oratory1990** — recommended | `results/oratory1990/over-ear/Sony WH-1000XM4/` | `measurements/oratory1990/data/over-ear/Sony WH-1000XM4.csv` |
| XM4 | Rtings (B&K 5128) | `results/Rtings/Bruel & Kjaer 5128 over-ear/Sony WH-1000XM4/` | `measurements/rtings/data/over-ear/Bruel & Kjaer 5128/Sony WH-1000XM4.csv` |
| XM4 (ANC Off / ANC on) | HypetheSonics (B&K 5128) | `results/HypetheSonics/over-ear/Sony WH-1000XM4 (ANC Off)/`, `…/Sony WH-1000XM4 (ANC on)/` | `measurements/HypetheSonics/data/over-ear/Sony WH-1000XM4 (ANC Off).csv`, `(ANC on).csv` |
| XM3 | **oratory1990** — recommended | `results/oratory1990/over-ear/Sony WH-1000XM3/` | `measurements/oratory1990/data/over-ear/Sony WH-1000XM3.csv` |
| XM3 (ANC off / ANC on) | Kuulokenurkka | `results/Kuulokenurkka/over-ear/Sony WH-1000XM3 (ANC off)/`, `(ANC on)/` | `measurements/Kuulokenurkka/data/over-ear/Sony WH-1000XM3 (ANC off).csv`, `(ANC on).csv` |
| XM3 | Rtings (HMS II.3) | `results/Rtings/HMS II.3 over-ear/Sony WH-1000XM3/` | `measurements/rtings/data/over-ear/HMS II.3/Sony WH-1000XM3.csv` |
| XM3 | Super Review | `results/Super Review/over-ear/Sony WH-1000XM3/` | `measurements/Super Review/data/over-ear/Sony WH-1000XM3.csv` |

- **crinacle**: no WH-1000XM over-ear entries (only WF-1000XM in-ear and IER models).
- **Rtings XM6** and **oratory1990 XM6** are not in this snapshot.
- Each results folder contains `README.md`, `<name> ParametricEQ.txt`, `<name> FixedBandEQ.txt`, `<name> GraphicEQ.txt`, `<name>.csv`, `<name>.png`, and `minimum phase 44100Hz.wav` / `48000Hz.wav`.

**Cross-source warning (XM6)**: Super Review's FixedBandEQ (Preamp −8.4) is −9.2 −6.0 −5.0 −2.3 +2.3 +2.8 −3.1 +2.8 +3.2 **+8.3**, while Kuulokenurkka's 16 kHz band is **−9.8**. Above about 8 kHz, rigs and fit disagree by more than 15 dB. This is a strong reason to down-weight the treble in our fit (§4.3) and to show the source and rig in the UI.

### 2.5 How AutoEq's FixedBandEQ fitting works

Files: `autoeq/frequency_response.py` (`optimize_fixed_band_eq`, `_optimize_peq_filters`), `autoeq/peq.py` (`PEQ`, `Peaking`, `LowShelf`, `HighShelf`), `autoeq/constants.py` (`PEQ_CONFIGS['10_BAND_GRAPHIC_EQ']`), and `autoeq/batch_processing.py` (where `--ten-band-eq` selects that config).

1. **Target**: the `equalization` curve, which is the target minus the smoothed measurement, after AutoEq's own max-gain (default +6 dB) and slope limiting, plus an optional preamp. It is interpolated onto the optimizer grid `f = 20·1.02^k` up to 20 kHz (349 points) with a linear spline in log10(f).
2. **Configuration** `10_BAND_GRAPHIC_EQ`: 10 `PEAKING` filters with `fc = 31.25·2^i` (i = 0…9), `q = √2`, `min_gain = −12`, `max_gain = +12`, and `optimizer.min_std = 0.01`. Because `fc` and `q` are given, only the gains are optimized (`optimize_fc = optimize_q = False`).
3. **Band model**: RBJ peaking biquad at `fs` (results are generated with `fs[0]`, default 44100). Its dB response uses the phi form (§3.3).
4. **Initialization** (`_init_optimizer_params`): greedy. For each filter, `Peaking.init(remaining_target)` uses `scipy.signal.find_peaks` on the positive and negative parts of the remaining target within `[min_fc, max_fc] = [20, 10000]`. It picks the largest peak by width × height and sets **gain = that peak's height** (signed, clipped to bounds). It then subtracts the filter's response from the remaining target. With fixed fc this gives only a rough start.
5. **Loss** (`PEQ._optimizer_loss`):

   ```
   T'(f), M'(f) = T(f), M(f) for f < 10 kHz; for f ≥ 10 kHz both are replaced by their mean over [10 kHz, 20 kHz]
   loss = sqrt( mean_{k in [min_f_ix, max_f_ix)} (T'_k − M'_k)^2  +  Σ_i sharpness_penalty_i )
   M(f) = Σ_i dB_i(f; g_i)                       (cascade = sum of dB)
   ```

   `sharpness_penalty` is a sigmoid penalty for peaking filters steeper than about 18 dB/oct. At Q = √2 the limit is `gain_limit = −0.0950 + 20.575/Q ≈ 14.45 dB`, so it is inactive within ±12 dB. The loss is unweighted, so all grid points count equally in log frequency. Above 10 kHz only the average energy is matched.
6. **Optimizer**: `scipy.optimize.fmin_slsqp` (SLSQP, bounded) with a callback. It stops when the standard deviation of the last 8 losses is below `min_std = 0.01`, or the last 4 are below 0.005, and restores the best parameters seen.
7. **Output**: `write_eqapo_parametric_eq` writes `Preamp: {-max(Σ fr):.1f} dB` and one `Filter` line per band.

Optional `gain_range`: each band's bounds are re-centered on `equalization(fc) ± gain_range`.

The ParametricEQ (free fc/Q/gain) uses the same loss and optimizer with these configs chained: `4_PEAKING_WITH_LOW_SHELF` then `4_PEAKING_WITH_HIGH_SHELF` (5 + 5 filters). The second config fits the residual of the first. Shelves start at fc 105 Hz and 10 kHz with Q 0.7. The default bounds are:

- Peaking: fc 20–10k, Q 0.18248–6, gain ±20.
- Shelf: Q 0.4–0.7, gain ±20.

### 2.6 File formats (for our parsers)

**ParametricEQ.txt / FixedBandEQ.txt** (Equalizer APO subset written by AutoEq):

```
Preamp: <float %.1f> dB\n
Filter <n>: ON <PK|LSC|HSC> Fc <int %.0f> Hz Gain <float %.1f> dB Q <float %.2f>\n     (n = 1…N, repeated)
```

- UTF-8, LF line endings, one trailing newline.
- Filters are in **optimization order, not sorted**.
- `PK` is the RBJ peaking filter. `LSC`/`HSC` are RBJ shelves with `alpha = sin(w0)/(2Q)`.
- The preamp equals −max(summed response), with no extra headroom.

Lenient grammar for other APO files (regex, case-insensitive):

```
^\s*Preamp:\s*(-?\d+(\.\d+)?)\s*dB
^\s*Filter(\s*\d+)?\s*:\s*(ON|OFF)\s+([A-Z]+)(\s+\d+dB)?\s+Fc\s+(-?[\d.]+)\s*Hz(\s+Gain\s+(-?[\d.]+)\s*dB)?(\s+(Q\s+([\d.]+)|BW\s+Oct\s+([\d.]+)))?
```

- Map `PK`/`PEQ`/`Modal` to peaking, `LSC`/`LS`/`LSQ` to low shelf, `HSC`/`HS`/`HSQ` to high shelf.
- Treat `LS`/`HS` (no C, optional `6dB`/`12dB` slope) as a different APO filter. Approximate it or warn.
- `BW Oct N` converts to `Q = sqrt(2^N)/(2^N − 1)`.
- Ignore lines starting with `#`.
- Clamp everything: Fc 10 Hz–0.49·fs, Q 0.05–30, gain ±30, and at most 64 filters.

**GraphicEQ.txt**:

```
GraphicEQ: <f1> <g1>; <f2> <g2>; …; <fN> <gN>          (single line, NO trailing newline)
```

- 127 integer frequencies `int(20·1.0563^k)`, de-duplicated, from 20 to 19871 Hz.
- Gains in dB with 1 decimal place.
- **Already normalized**: `gains −= max(gains) + 0.2`, so the maximum is −0.2 dB. The first point is clamped to ≤ 0. There is no separate Preamp line.
- Between nodes, interpolate linearly in log f. Outside the range, hold flat.
- The separator is `"; "`, with a single space between f and g.

**Result CSV**:

- Header: `frequency,raw,smoothed,error,error_smoothed,equalization,parametric_eq,fixed_band_eq,equalized_raw,equalized_smoothed,target`. Any subset is allowed; see `autoeq/csv.py` `header_pattern`.
- 695 rows with `f = 20·1.01^i`, written to 2 decimal places.
- The column to use as **correction target** is `equalization`, which is what to add to the headphone response. `target` is the absolute target curve and `raw` is the measurement normalized near 1 kHz.
- Measurement CSVs in `measurements/` have `frequency,raw` only.

---

## 3. Formulas to implement in Swift and Kotlin

All coefficient and response math must run in **Double**. Only the realtime sample loop may use Float.

### 3.1 RBJ Audio-EQ-Cookbook biquads (peaking, low shelf, high shelf)

Inputs: f0 in Hz, Q (> 0), gain G in dB, and fs in Hz. Clamp `f0` to `[10, 0.499·fs]`.

```
A     = 10^(G/40)            // sqrt of linear gain
w0    = 2π·f0/fs
cosw  = cos(w0),  sinw = sin(w0)
alpha = sinw / (2Q)
```

**Peaking EQ**

```
b0 = 1 + alpha·A      b1 = −2·cosw      b2 = 1 − alpha·A
a0 = 1 + alpha/A      a1 = −2·cosw      a2 = 1 − alpha/A
```

**Low shelf** (with `k = 2·sqrt(A)·alpha`)

```
b0 =    A·((A+1) − (A−1)·cosw + k)
b1 =  2·A·((A−1) − (A+1)·cosw)
b2 =    A·((A+1) − (A−1)·cosw − k)
a0 =       (A+1) + (A−1)·cosw + k
a1 =   −2·((A−1) + (A+1)·cosw)
a2 =       (A+1) + (A−1)·cosw − k
```

**High shelf** (with `k = 2·sqrt(A)·alpha`)

```
b0 =    A·((A+1) + (A−1)·cosw + k)
b1 = −2·A·((A−1) + (A+1)·cosw)
b2 =    A·((A+1) + (A−1)·cosw − k)
a0 =       (A+1) − (A−1)·cosw + k
a1 =    2·((A−1) − (A+1)·cosw)
a2 =       (A+1) − (A−1)·cosw − k
```

Normalize: `b0/=a0, b1/=a0, b2/=a0, a1/=a0, a2/=a0`, which leaves a0 = 1.

Notes:

- Shelf "Q" here is the cookbook Q form, which AutoEq, Equalizer APO `LSC/HSC` and ParaEQ all use. Q = 0.7071 gives the steepest shelf without overshoot. If you ever expose a shelf slope S instead, use `alpha = sinw/2 · sqrt((A + 1/A)(1/S − 1) + 2)`.
- Bandwidth in octaves N converts to Q as `Q = sqrt(2^N)/(2^N − 1)` (N = 1 gives Q = √2 ≈ 1.414). The inverse is `N = (2/ln2)·asinh(1/(2Q))`. This is the analog relation that AutoEq, APO's `BW Oct` and ParaEQ use. The cookbook's bilinear-warped digital form is `1/Q = 2·sinh(ln2/2 · N · w0/sin w0)`; don't mix the two.
- The difference equation (Direct Form I, or better Transposed Direct Form II for Float) is `y[n] = b0·x[n] + b1·x[n−1] + b2·x[n−2] − a1·y[n−1] − a2·y[n−2]`. `vDSP_biquad` and `vDSP_biquadm` take coefficients per section in the order `[b0, b1, b2, a1, a2]` with the same sign convention.
- Useful property: for the RBJ peaking filter, `H_{−G}(z) = 1/H_{+G}(z)` (A ↔ 1/A swaps numerator and denominator). So `dB(f; −G) = −dB(f; G)` exactly, which is a free unit test.

### 3.2 Magnitude response |H(e^{jω})| in dB

With `ω = 2π f / fs`, for normalized coefficients (a0 = 1).

**Direct form:**

```
Nr = b0 + b1·cos ω + b2·cos 2ω      Ni = −(b1·sin ω + b2·sin 2ω)
Dr = 1  + a1·cos ω + a2·cos 2ω      Di = −(a1·sin ω + a2·sin 2ω)
dB = 10·log10( (Nr²+Ni²) / (Dr²+Di²) )
```

**Numerically stable phi form** (as used by AutoEq `peq.py` and Equalizer APO). This is recommended because it avoids cancellation at low f/fs:

```
φ   = 4·sin²(ω/2)
|N|² = (b0+b1+b2)² + (b0·b2·φ − (b1·(b0+b2) + 4·b0·b2))·φ
|D|² = (1+a1+a2)²  + (a2·φ    − (a1·(1+a2)  + 4·a2))·φ
dB  = 10·log10(|N|²) − 10·log10(|D|²)
```

Derivation: `cos ω = 1 − φ/2`, `cos 2ω = 1 − 2φ + φ²/2`, substituted into `|N|² = Σb² + 2(b0b1+b1b2)cos ω + 2b0b2 cos 2ω`.

A cascade's response in dB is the sum of its sections' dB values. Guard against `|N|² ≤ 0` by returning about −200 dB, and against `|D|² ≤ 0` by returning 0.

### 3.3 Test vectors (fs = 48000; produced by `scripts/vectors.py` and cross-checked with the direct form)

| Filter | b0, b1, b2, a1, a2 | dB at 20 / 100 / 1000 / 5000 / 16000 Hz |
|---|---|---|
| PK 1000 Hz, Q 1.41, +6 dB | 1.031577910617, −1.919976143598, 0.904965631439, −1.919976143598, 0.936543542056 | 0.001302 / 0.032994 / **6.000000** / 0.128061 / 0.004683 |
| PK 31.25 Hz, Q √2, −4.9 dB | 0.999174844288, −1.996155576213, 0.996997433011, −1.996155576213, 0.996172277299 | −1.828762 / −0.289586 / −0.002518 / −0.000094 / −0.000004 |
| LSC 105 Hz, Q 0.7, −4.0 dB | 0.997737524012, −1.978018372753, 0.980429266596, −1.977974968456, 0.978210194905 | −3.991584 / −2.187503 / −0.001405 / −0.000034 / −0.000001 |
| HSC 10000 Hz, Q 0.7, −4.1 dB | 0.761777919891, −0.130147597812, 0.130937138738, −0.438666189216, 0.201233650032 | −0.000000 / −0.000006 / −0.000842 / −0.170793 / −3.928319 |

Shelf sanity checks: the response at f0 is G/2 (for example LS 105 Hz: −2.000 dB at 105 Hz), and the far asymptote equals G. Use tolerance 1e-9 on the coefficients and 1e-6 dB on the magnitudes.

---

## 4. Recommended fixed-band ("AutoEQ interno") fitting algorithm

### 4.1 Problem

Given:

- a target correction curve `T(f)` in dB (what should be added to the headphones),
- N device bands with fixed center frequency f0_i and a fixed shape (Q_i or a measured shape),
- gain bounds `[lo_i, hi_i]` and a gain step (device integer steps),

find gains `g` that minimize

```
J(g) = Σ_k w_k · ( Σ_i B_i(f_k; g_i) + c − T_k )²  +  λ·Σ_i g_i²  +  μ·Σ_i (g_{i+1} − g_i)²
subject to lo_i ≤ g_i ≤ hi_i   (and optionally c_lo ≤ c ≤ c_hi)
```

- `B_i(f; g)` is band i's dB response at gain g (an RBJ peaking filter by default; any `BandModel`).
- `c` is an optional free broadband level offset. It is not sent to the device; it only means "overall level doesn't matter". Only the shape is fitted.
- `λ` is a small ridge term (keeps the system positive definite and prefers small gains).
- `μ` is an optional smoothness penalty against zig-zag solutions between strongly overlapping adjacent bands.

`B_i` is nearly, but **not exactly**, linear in g, because RBJ peaking keeps the half-gain bandwidth constant. A purely linear basis ("1 dB response × g") is good to about 0.1–0.3 dB. Two to five Gauss–Newton passes make the fit exact.

### 4.2 Frequency grid

- **K = 240 log-spaced points, 20 Hz–20 kHz**: `f_k = 20 · 1000^(k/(K−1))`, k = 0…239 (about 1/24 octave).
- Alternative for AutoEq parity: `f_k = 20·1.02^k`, k = 0…348.
- Interpolate the target onto the grid **linearly in ln f**, holding flat outside the data range.
- Target sources, best first:
  1. The AutoEq CSV `equalization` column.
  2. Evaluating a `ParametricEQ.txt` cascade on the grid (sum of dB plus Preamp, or just enable the free offset).
  3. `GraphicEQ.txt` nodes. These carry a −(max + 0.2) offset, so enable the free offset c.
- Add user preference shaping (bass or treble tilt) to `T` before fitting.

### 4.3 Weighting

```
w(f) = 0.5                                    f < 40 Hz      (seal/leak dependent, little device authority)
       1.0                                    40 Hz ≤ f ≤ 6 kHz
       1.0 − 0.7·ln(f/6000)/ln(10000/6000)    6–10 kHz      (log-linear ramp 1.0 → 0.3)
       0.3                                    f > 10 kHz    (rig/pinna variance; sources disagree by >15 dB)
```

Optional, matching AutoEq: **collapse all points ≥ 10 kHz into one residual row**. The row is the mean of the model rows (and Jacobian rows) over those points minus the mean of the target there, with weight equal to the sum of their weights. Above 10 kHz, only the average energy then matters. This collapse was used in the validation run.

A log-spaced grid already weights each octave equally. Do **not** also apply 1/f weighting.

### 4.4 Band model abstraction

```
protocol/interface BandModel { func dB(bandIndex: Int, gain: Double, f: Double) -> Double }
```

- **Default**: RBJ peaking with `f0_i` and `Q_i`, evaluated at a nominal **device DSP rate** (fs_model = 48000). This is not the Bluetooth or host sample rate; the hardware EQ runs inside the headphones.
  - For XM6 v1, assume `f0 = {31.5, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k}` and `Q = √2`. **Both are unverified.**
  - Note the bilinear warping near Nyquist: at fs 48k the 16 kHz band is cramped. That is another reason to calibrate.
- **Table model** (for when Sony's real band shape is known): store the per-band dB response at a few gains (for example ±1…±6) on the grid, and interpolate linearly in gain. It can also take odd symmetry `dB(−g) = −dB(g)` if that is confirmed.
- **Extra controls** (for example a "Clear Bass" control on older Sony models, which ranged −10…+10 in Gadgetbridge's 5-band layout) are just another band with their own shape and bounds.

### 4.5 Solver (deterministic, no libraries)

**Outer loop: projected Gauss–Newton**, at most 10 iterations, starting from g = clamp(0).

1. Compute the model `M_k = Σ_i B_i(f_k; g_i) (+ c)` and the Jacobian `J_ik = ∂B_i/∂g_i` by central difference `(B_i(f_k; g_i+h) − B_i(f_k; g_i−h))/(2h)`, with h = 0.01 dB. The offset column, if used, is all ones.
2. Linearize around g and write the normal equations for the *new* point x:
   - `r0_k = M_k − Σ_i J_ik·g_i − T_k`
   - `H = Jᵀ W J + λI (+ μ·DᵀD)`
   - `b = −Jᵀ W r0`
3. Solve the box-constrained QP `min ½xᵀHx − bᵀx` subject to `lo ≤ x ≤ hi` with the **projected Gauss–Seidel** inner loop:

   ```
   repeat up to 10_000 sweeps:
     δ = 0
     for i in 0..<n:                         // fixed order => deterministic
       grad_i = Σ_j H[i][j]·x[j] − b[i]
       xi_new = clamp(x[i] − grad_i / H[i][i], lo[i], hi[i])
       δ = max(δ, |xi_new − x[i]|);  x[i] = xi_new
     until δ < 1e-9
   ```

   For symmetric positive definite H (guaranteed by λ > 0), this converges to the unique minimizer of the box-constrained QP. With n ≈ 10–11 each sweep takes about 100 multiply-adds.
4. Safeguard: if `J(x) > J(g)`, halve the step (`x = g + ½(x − g)`, at most 5 times).
5. Set `g = x`. Stop when `max|Δg| < 1e-4` dB.

Suggested defaults:

- λ = 2.5e-4·Σ_k w_k (about 0.05 on the default grid)
- μ = 0 (try 0.02·Σw/K if you see alternating ±)
- h = 0.01

Cost is about K·n·3 biquad evaluations per GN iteration (roughly 7k), well under a millisecond.

**Quantization to device steps** (integers, step s = 1):

1. `q_i = clamp(round(g_i/s)·s)`.
2. **Greedy descent**: repeatedly try `q_i ± s` for i = 0…n−1 in fixed order. Accept a change only if the true nonlinear cost drops by more than 1e-9. Stop when no move improves. This terminates because the cost strictly decreases over a finite set. On ties, keep the value with the smaller |q|.

A more accurate variant is sequential rounding: fix the band with the smallest rounding error, re-solve the rest continuously, and repeat.

**Preamp / headroom**: the device has no preamp control, so report `max_f Σ B_i(f; q_i)` as the peak boost and warn above about +6 dB. With the free offset c enabled, `c` is the recommended volume change; it is not sent to the device.

**Cross-platform determinism**: use the same grid, ordering and constants everywhere. JVM `Math` and Darwin libm can differ by 1 ulp, so compare continuous results with a 1e-6 dB tolerance and the quantized integers exactly. Use golden tests from §4.8.

### 4.6 Swift sketch

```swift
import Foundation

enum BiquadKind { case peaking, lowShelf, highShelf }
struct Biquad { var b0, b1, b2, a1, a2: Double }

func rbj(_ kind: BiquadKind, f0: Double, q: Double, gainDB: Double, fs: Double) -> Biquad {
    let A = pow(10.0, gainDB / 40.0)
    let w0 = 2.0 * Double.pi * min(max(f0, 10.0), 0.499 * fs) / fs
    let c = cos(w0), s = sin(w0), alpha = s / (2.0 * q)
    var b0, b1, b2, a0, a1, a2: Double
    switch kind {
    case .peaking:
        b0 = 1 + alpha * A; b1 = -2 * c; b2 = 1 - alpha * A
        a0 = 1 + alpha / A; a1 = -2 * c; a2 = 1 - alpha / A
    case .lowShelf:
        let k = 2 * A.squareRoot() * alpha
        b0 = A * ((A + 1) - (A - 1) * c + k); b1 = 2 * A * ((A - 1) - (A + 1) * c)
        b2 = A * ((A + 1) - (A - 1) * c - k); a0 = (A + 1) + (A - 1) * c + k
        a1 = -2 * ((A - 1) + (A + 1) * c);    a2 = (A + 1) + (A - 1) * c - k
    case .highShelf:
        let k = 2 * A.squareRoot() * alpha
        b0 = A * ((A + 1) + (A - 1) * c + k); b1 = -2 * A * ((A - 1) + (A + 1) * c)
        b2 = A * ((A + 1) + (A - 1) * c - k); a0 = (A + 1) - (A - 1) * c + k
        a1 = 2 * ((A - 1) - (A + 1) * c);     a2 = (A + 1) - (A - 1) * c - k
    }
    return Biquad(b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0)
}

func magnitudeDB(_ h: Biquad, f: Double, fs: Double) -> Double {
    let s = sin(Double.pi * f / fs), phi = 4 * s * s
    let n = pow(h.b0 + h.b1 + h.b2, 2) + (h.b0 * h.b2 * phi - (h.b1 * (h.b0 + h.b2) + 4 * h.b0 * h.b2)) * phi
    let d = pow(1 + h.a1 + h.a2, 2) + (h.a2 * phi - (h.a1 * (1 + h.a2) + 4 * h.a2)) * phi
    guard n > 0, d > 0 else { return n <= 0 ? -200 : 0 }
    return 10 * log10(n) - 10 * log10(d)
}

/// Projected Gauss–Seidel for min ½xᵀHx − bᵀx, lo ≤ x ≤ hi (H SPD).
func solveBoxQP(_ H: [[Double]], _ b: [Double], lo: [Double], hi: [Double], start: [Double]) -> [Double] {
    var x = start; let n = b.count
    for _ in 0..<10_000 {
        var delta = 0.0
        for i in 0..<n {
            var g = -b[i]; for j in 0..<n { g += H[i][j] * x[j] }
            let xi = min(max(x[i] - g / H[i][i], lo[i]), hi[i])
            delta = max(delta, abs(xi - x[i])); x[i] = xi
        }
        if delta < 1e-9 { break }
    }
    return x
}

/// response(i, gain, f) -> dB of band i. Last band may be the free offset (returns `gain`).
func fitGains(freqs: [Double], target: [Double], weights: [Double], lo: [Double], hi: [Double],
              ridge: Double, response: (Int, Double, Double) -> Double,
              iterations: Int = 10, h: Double = 0.01) -> [Double] {
    let n = lo.count, K = freqs.count
    var g = (0..<n).map { min(max(0, lo[$0]), hi[$0]) }
    for _ in 0..<iterations {
        var H = Array(repeating: Array(repeating: 0.0, count: n), count: n)
        var b = Array(repeating: 0.0, count: n)
        var J = Array(repeating: 0.0, count: n)
        for k in 0..<K {
            var r0 = -target[k]
            for i in 0..<n {
                let m = response(i, g[i], freqs[k])
                J[i] = (response(i, g[i] + h, freqs[k]) - response(i, g[i] - h, freqs[k])) / (2 * h)
                r0 += m - J[i] * g[i]
            }
            let w = weights[k]
            for i in 0..<n { b[i] -= w * J[i] * r0; for j in 0..<n { H[i][j] += w * J[i] * J[j] } }
        }
        for i in 0..<n { H[i][i] += ridge }
        let x = solveBoxQP(H, b, lo: lo, hi: hi, start: g)
        // (production: add cost check + step halving here)
        let step = zip(x, g).map { abs($0 - $1) }.max() ?? 0
        g = x
        if step < 1e-4 { break }
    }
    return g
}
```

(Add the optional ≥ 10 kHz collapse by building those rows as averages before accumulating `H` and `b`.)

### 4.7 Kotlin sketch

```kotlin
enum class BiquadKind { PEAKING, LOW_SHELF, HIGH_SHELF }
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

fun rbj(kind: BiquadKind, f0: Double, q: Double, gainDb: Double, fs: Double): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * PI * f0.coerceIn(10.0, 0.499 * fs) / fs
    val c = cos(w0); val s = sin(w0); val alpha = s / (2.0 * q)
    val (b, d) = when (kind) {
        BiquadKind.PEAKING -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a) to
                doubleArrayOf(1 + alpha / a, -2 * c, 1 - alpha / a)
        BiquadKind.LOW_SHELF -> { val k = 2 * sqrt(a) * alpha
            doubleArrayOf(a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k)) to
            doubleArrayOf((a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k) }
        BiquadKind.HIGH_SHELF -> { val k = 2 * sqrt(a) * alpha
            doubleArrayOf(a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k)) to
            doubleArrayOf((a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k) }
    }
    return Biquad(b[0] / d[0], b[1] / d[0], b[2] / d[0], d[1] / d[0], d[2] / d[0])
}

fun magnitudeDb(h: Biquad, f: Double, fs: Double): Double {
    val s = sin(PI * f / fs); val phi = 4 * s * s
    val n = (h.b0 + h.b1 + h.b2).pow(2) + (h.b0 * h.b2 * phi - (h.b1 * (h.b0 + h.b2) + 4 * h.b0 * h.b2)) * phi
    val d = (1 + h.a1 + h.a2).pow(2) + (h.a2 * phi - (h.a1 * (1 + h.a2) + 4 * h.a2)) * phi
    if (n <= 0) return -200.0; if (d <= 0) return 0.0
    return 10 * log10(n) - 10 * log10(d)
}

fun solveBoxQp(h: Array<DoubleArray>, b: DoubleArray, lo: DoubleArray, hi: DoubleArray, start: DoubleArray): DoubleArray {
    val x = start.copyOf(); val n = b.size
    repeat(10_000) {
        var delta = 0.0
        for (i in 0 until n) {
            var g = -b[i]; for (j in 0 until n) g += h[i][j] * x[j]
            val xi = (x[i] - g / h[i][i]).coerceIn(lo[i], hi[i])
            delta = maxOf(delta, abs(xi - x[i])); x[i] = xi
        }
        if (delta < 1e-9) return x
    }
    return x
}
// fitGains(): identical structure to the Swift version (outer GN loop, central-difference J, ridge, solveBoxQp).
```

Imports for the Kotlin sketch: `kotlin.math.*`.

### 4.8 Validation on the real XM6 data (`scripts/fit_fixed_band.py`, pure Python)

Run: `python3 -I scripts/fit_fixed_band.py "reports/autoeq-xm6/Sony WH-1000XM6.csv"`

| Setting | Gains 31 Hz … 16 kHz (dB) | Loss |
|---|---|---|
| AutoEq `FixedBandEQ.txt` (SLSQP), scored under AutoEq's own loss | −4.9 −2.1 −3.2 −1.4 −1.0 +3.4 −1.5 −0.7 +5.3 −9.8 | RMSE 0.738 dB |
| **Our solver**, AutoEq-equivalent setup (fs 44100, 1.02 grid, ±12, ≥ 10k averaged, unweighted) | −4.6 −2.4 −3.1 −1.6 −0.8 +3.2 −1.4 −0.8 +5.4 −10.1 | **RMSE 0.729 dB** (peak boost +4.70) |
| Recommended setup (fs 48k, 240 pts, §4.3 weights, ±10, λ 0.05) | −4.52 −2.45 −3.05 −1.59 −0.78 +3.15 −1.34 −0.85 +5.64 −9.42 | wRMSE 0.698 |
| … quantized to 1 dB | −5 −2 −3 −2 −1 +3 −1 −1 +6 −10 | wRMSE 0.730 |
| **XM6-like bounds ±6, integer steps**, no offset | −5 −2 −3 −2 −1 +3 −1 −1 +5 −6 | wRMSE 0.802 |
| ±6, integer, **free offset c** (c = −1.4 dB) | −3 −2 −2 −1 0 +4 −1 0 +6 −6 | wRMSE 0.712 |

Our deterministic solver lands within about 0.3 dB of AutoEq's SLSQP result and slightly beats it on AutoEq's own loss (SLSQP stops early at `min_std = 0.01`). With the narrow ±6 device range, the free offset noticeably improves the fit. **Enable it by default.**

The **Swift sketch in §4.6 was compiled and run** (`scripts/swiftcheck/`, `swiftc -O`) on the same data: 240 points, §4.3 weights without the ≥ 10 kHz collapse, ±6, λ 0.05. It printed `-4.5198 -2.4480 -3.0519 -1.5879 -0.7816 +3.1479 -1.3423 -0.8462 +5.2964 -6.0000`, which matches the Python reference to 4 decimals and reproduces the §3.3 PK test vector exactly. The Kotlin sketch was **not** compiled (no kotlinc on this machine), so port it with the same golden test.

### 4.9 XM6 device constraints and integration notes (verify on hardware)

- `refs/xm6-control/Sources/SonyHeadphonesKit/Protocol/Commands.swift`:
  - EQ set payload: `[0x58 (Opcode.equalizerSet), subtype 0x04, presetId 0xA0 (custom), bandCount 0x0A, (gain+6) × 10]`. Get is 0x56, ret 0x57, notify 0x59 (`Opcodes.swift`).
  - Constants: `defaultBandCount = 10`, `bandGainRange = −6…6`, `bandOffset = 6`.
  - The comment says a write `58 04 a0 0a 09 08 07 06 05 04 03 06 06 06` "is echoed back byte for byte… so the 10-band layout and the offset are both confirmed".
  - 6-band replies (the older ClearBass layout) use offset 10.
- Gadgetbridge `SonyWH1000XM6Coordinator.java` has the comment `// TODO 9 bands SonyHeadphonesCapabilities.EqualizerWithCustomBands`. This conflicts with the 10-band finding (it may count differently, or be stale). Older Sony devices use 5 bands plus Clear Bass, each −10…+10 (`EqualizerCustomBands.java`).
- Press (What Hi-Fi) reports a "ten-band equaliser" in Sound Connect. The exact center frequencies and dB per step were **not** found in any primary source.
- **Design**: `DeviceEQProfile { centers: [Double]?, q: [Double]?, minStep: Int, maxStep: Int, dbPerStep: Double, bandModel: BandModel }`, read from the device-reported band count. Keep assumed centers and Q as an overridable table. To calibrate the band shape later, measure one band at a time (for example +6 steps) with a measurement mic or coupler, then switch `BandModel` to the table model.
- The correction must match the ANC state of the measurement. The Kuulokenurkka XM6 result is **ANC on**. Expose ANC on/off and ambient variants if more data becomes available.

---

## 5. Recommendations

1. **AutoEQ interno (both platforms)**:
   - Implement §3 (RBJ, magnitude) and §4 (projected Gauss–Newton with projected Gauss–Seidel, then greedy quantization) in Swift and Kotlin with shared golden vectors (§3.3, §4.8).
   - Inputs: the AutoEq CSV `equalization` column or a ParametricEQ.txt cascade.
   - Defaults: 240-point grid, §4.3 weights, λ = 2.5e-4·Σw, free offset on, ±6 integer bounds for the XM6.
2. **Data**:
   - Don't bundle AutoEq results or measurements in a paid app without permission.
   - Fetch on user action from `raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/...` with clear attribution ("Measurement: Kuulokenurkka; EQ: AutoEq (MIT)"), or ask Kuulokenurkka for permission.
   - Cap sizes and sanitize as ParaEQ does.
3. **macOS system-wide DSP**:
   - Follow ParaEQ's architecture: process tap, private aggregate with the real output as main sub-device, single IOProc, macOS ≥ 14.4.
   - Reuse `BiquadEQ.swift`, `Limiter.swift`, `FrequencyResponse.swift` and `AutoEQParser.swift` (keeping the MIT notice).
   - Re-implement the engine lifecycle following ARCHITECTURE.md gotchas 1–15, and add a callback watchdog.
   - Keep the hardware EQ and the Mac DSP correction mutually exclusive, or composed as fit plus residual.
   - Verify sandbox and Mac App Store compatibility early if needed.

---

## Sources

- ParaEQ repository: https://github.com/wabsto1/ParaEQ. Files cited: `LICENSE`, `README.md`, `docs/ARCHITECTURE.md`, `Sources/*.swift`, `ParaEQ.entitlements`, `Info.plist`, `Package.swift`.
- AutoEq repository: https://github.com/jaakkopasanen/AutoEq. Files cited: `LICENSE`, `README.md`, `autoeq/peq.py`, `autoeq/frequency_response.py`, `autoeq/constants.py`, `autoeq/batch_processing.py`, `results/**`, `measurements/**/name_index.tsv`, `dbtools/create_webapp_data.py`, `webapp/data/legal/terms-of-service.html`.
- Robert Bristow-Johnson, *Audio EQ Cookbook* (also published as a W3C note), for the biquad formulas.
- [headphones.com forum, "EQ for both iOS & Mac" (page 4)](https://forum.headphones.com/t/eq-for-both-ios-mac/4874?page=4): unverified user claims about oratory1990's terms.
- [What Hi-Fi: WH-1000XM6 sound settings](https://www.whathifi.com/headphones/wireless-headphones/7-sound-settings-and-control-hacks-you-need-to-try-on-the-sony-wh-1000xm6): ten-band EQ in Sound Connect.
- [ShopSavvy WF-1000XM6 EQ page](https://shopsavvy.com/answers/sony-wf-1000xm6-equalizer-eq-settings): low-reliability claim of 31 Hz–16 kHz and ±6 dB (for the earbuds, not the WH model).
- Sibling reference clones in `scratchpad/refs`: `xm6-control` (Commands.swift) and `gadgetbridge` (SonyWH1000XM6Coordinator.java, EqualizerCustomBands.java).
