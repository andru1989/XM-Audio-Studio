> Research snapshot from 2026-10-21 (Etapa 0). Paths under `scratchpad/` or `refs/` refer to temporary
> clones that are not part of this repository.

# xm6-control fork analysis

Upstream: `ruimartins23/xm6-control`, `origin/main` at `af6d89b` (2026-09-12).
Analysis date: 2026-10-21. Toolchain used for builds and tests: Xcode 26.6 (17F113), Swift 6.3.3, macOS 26.5.2 (arm64), XcodeGen 2.46.0.

The clone at `scratchpad/refs/xm6-control` was only read. Builds ran in detached worktrees under `scratchpad/wt-*`. `git status` in the main clone is still clean and `main` is still at `af6d89b`.

| Branch | PR | Base | Ahead of upstream | State |
|---|---|---|---|---|
| `Anneo22/release-channel-when-idle` | #4 | `af6d89b` | 2 | Open. Only a Copilot review, no maintainer reply. |
| `Anneo22/main` | none | `af6d89b` | 16 (the PR's 2 plus 14) | Personal fork |
| `yelloduxx/macos27-redesign` | #6 | `af6d89b` | 9 | Open, no comments |
| `plagnioj/agent/xm6-control-center` | #1 | `9b5db95` (19 behind) | 6 | Draft. The maintainer explained why it can't be merged. |
| `mlazzarotto/main` | #2 | — | 2 (a merge plus `69afad2`) | Merged on 2026-07-29 as `b96d4fc` |

Issues: #3 (closed by #2) was the CLT 26.6 manifest link failure. #5 (open) asks whether the maintainer wants a GitHub Actions workflow that builds and publishes a release `.app`. There has been no reply.

---

## 1. mlazzarotto/main (PR #2): already upstream

- The fork commit `69afad2` was merged upstream as `b96d4fc` ("Bump swift-tools-version to 6.0 with per-target Swift 5 language mode", Closes #3). `git diff 69afad2:Package.swift b96d4fc:Package.swift` is empty.
- `origin/main:Package.swift` is identical to `b96d4fc:Package.swift`. It has `// swift-tools-version:6.0` and `swiftSettings: [.swiftLanguageMode(.v5)]` on `SonyHeadphonesKit`, `XM6Control` and `XM6Probe`. **Confirmed present.**
- `mlazzarotto/main` has nothing else beyond upstream. Its tip `aee54c6` only merges upstream `dbfba63`.
- Maintainer's note on #2: he couldn't reproduce the link failure (his CLT ships only the public `PackageDescription.swiftinterface`). He accepted the change because the only cost is that the manifest now needs a Swift 6 toolchain.

---

## 2. Anneo22: PR #4 `release-channel-when-idle` (`1d3e3ec`, `efca6f6`)

### 2.1 The problem it solves
The XM6 accepts only one MDR control session. Upstream calls `autoConnect()` at launch and keeps the RFCOMM channel for the app's whole lifetime. While the app runs, Sony Sound Connect on the phone can't control the headset, and other clients get `IOReturn 0xe00002bc` when they try to open the channel.

### 2.2 Commits
| SHA | Summary | Files |
|---|---|---|
| `1d3e3ec` | Acquire the control channel on demand and release it 20 s after the last surface or command. Adds the setting, the transport seam and the test target. | Package.swift, README.md, RFCOMMConnection.swift, **ConnectionUsage.swift (new)**, HeadphonesController.swift, AppSettings.swift, ContentView.swift, CompactControlsView.swift, **ControlSurfaceObserver.swift (new)**, DashboardView.swift, DisconnectedView.swift, XM6ControlApp.swift, Tests/ConnectionUsageTests.swift, Tests/HeadphonesControllerTests.swift |
| `efca6f6` | Yields to another device when the headset closes the session itself, and reads the real ANC state on firmware 3.0.0 through `0x19`. | HeadphonesController.swift, AmbientSoundState.swift, Commands.swift, CompactControlsView.swift, NoiseControlCard.swift, README.md, Tests/AmbientSoundTests.swift (new), Tests/HeadphonesControllerTests.swift |

Diff vs upstream: 18 files, +1179 / −75.

### 2.3 Connection lifecycle design

**Policy object: `ConnectionUsage`** (`Sources/SonyHeadphonesKit/ConnectionUsage.swift`, internal struct)
- `static let gracePeriod: Duration = .seconds(20)` is the only timing constant in the policy.
- `visibleSurfaces: Set<UUID>`. `setSurface(_:visible:now:) -> Bool` returns whether anything changed and records activity when it did.
- `recordActivity(now:)` sets `idleDeadline = now + 20s` only when `releaseWhenIdle` is on and no surface is visible. Otherwise it sets the deadline to `nil`.
- `shouldRelease(now:)` returns `releaseWhenIdle && now >= idleDeadline`. The deadline uses `ContinuousClock`, which is monotonic.
- Protocol traffic (background state queries) does **not** count as activity, so it can't keep an idle link alive.

**Controller: `HeadphonesController`** (`Sources/SonyHeadphonesKit/HeadphonesController.swift`)
- `public var releaseWhenIdle` drives `usage.releaseWhenIdle`. Toggling it re-arms the timer. It connects if the setting is turned off, or if a surface is already visible.
- **On-demand acquire:**
  - `setControlSurface(_ id: UUID, visible: Bool)` is called by the UI. If release mode is on and the surface is visible, it calls `connectIfNeeded()`.
  - `connectIfNeeded()` acts only when the state is `.disconnected` or `.failed`. It reconnects to `lastConnectTarget` (so a manually picked headset is remembered) and falls back to `autoConnect()`.
- **Auto-connect before commands:**
  - Every setter (`setAmbientSound`, `setListeningMode`, `setEqualizerPreset`, `setSpeakToChat*`, `setAutomaticPowerOff`, `setPauseWhenTakenOff`, `switchPlayback`, `sendRaw`) now goes through `enqueueCommand(_:type:)`.
  - In release mode, `enqueueCommand` appends to `pendingCommands`, then calls `recordCommand()`, which records activity, re-arms the idle timer and runs `connectIfNeeded()`. It sends immediately only if the state is `.connected` and nothing is waiting for an ACK.
  - `setEqualizerBands` calls `recordCommand()` up front. Its 120 ms debounce task (`equalizerWriteTask`) survives connection retries, because `resetSessionState()` no longer cancels it in release mode.
  - `refreshState()` also counts as a command.
- **Pending operation queue:**
  - There are two queues. `outgoingQueue` holds handshake and state queries and is dropped on every session reset. `pendingCommands` holds user commands, survives drops and retries, and is cleared only by `disconnect()` or `fail()`.
  - `sendNextQueuedCommand()` sends user commands first once the state is `.connected`, and sets `awaitingCommandAck`. It also calls `disconnectIfIdle()` first.
- **Idle release:**
  - `scheduleIdleDisconnect()` starts a `Task.sleep(until: deadline, clock: .continuous)`, which calls `disconnectIfIdle(now:)`.
  - `disconnectIfIdle` releases only if `shouldRelease` is true, `pendingCommands` is empty, `!awaitingCommandAck` and `equalizerWriteTask == nil`. An accepted command finishes before release, but pending state queries don't hold the link.
- **Retries:**
  - Connect timeout is 8 s, with one retry (`connectRetryCount < 1`). The counter now resets to 0 when `protocolInfo` arrives, so a later drop gets a fresh retry.
  - Init handshake retries every 1.25 s, up to 2 times. Failure goes through the new `fail(_:)`, which calls `disconnect()` and sets `.failed(message)`.
  - ACK timeout is 3 s. `handleAckTimeout()` clears `awaitingCommandAck`. A new `startAckTimeout()` is armed when a protocol reply arrives while the init ACK is still outstanding ("missing init ACK must not hold commands forever").
  - In RFCOMM, the existing one-shot channel-open retry (after 1.5 s) is now tied to `attemptID`, so a delayed retry can't run after a newer attempt or a disconnect. `rfcommChannelOpenComplete` also checks that the channel is still current before it emits `.opened` after the 0.5 s settle delay.
- **Immediate disconnect:**
  - `disconnect()` increments `connectionGeneration`, cancels the idle task, the pending commands and the EQ task, clears `connectTarget`, closes the transport and resets the session.
  - The transport's `onEvent` hop to the MainActor is dropped if `connectionGeneration` changed or `connectTarget == nil`, so late callbacks are ignored.
  - `RFCOMMConnection.disconnect()` runs `DispatchQueue.main.sync` when it's called off the main thread, so the channel closes before termination.
  - `AppSettings` observes `NSApplication.willTerminateNotification` and calls `controller.disconnect()`. The explicit Disconnect button does the same.
- **Detecting that control went to another device** (`handle(.closed)`):
  - Suppose the session is in release mode, it was `.connected` or `.initializing`, no work is pending, and the headset closes it on its own. This happens about 20 s after Sound Connect opens on the phone.
  - The controller then treats the close as a hand-over. It calls `disconnect()`, which clears the target, so a surface that merely stays visible does **not** reconnect. It sets `lastError = "Another device is using the headphones. Click Connect or Try Again, or change a setting here, to take them back."`.
  - Only fresh user activity reacquires the channel: Connect or Try Again, a control command, or a surface that becomes visible again (`setSurface` must report a change).
- **Avoiding aggressive reconnects:**
  - On any other `.closed`, the controller resets and sets `.disconnected`. It reconnects only if a target is set and either the idle deadline hasn't passed or work is pending.
  - Legacy mode (`releaseWhenIdle == false`) keeps the old behaviour: no yield and no auto-reconnect.
  - The channel-open error message now explains contention: `RFCOMMConnection.channelOpenError` says "Close Sony Sound Connect on your phone…".
- **Transport seam:** the new `protocol HeadphonesConnection: AnyObject` has `onEvent`, `connect`, `disconnect` and `write`. `RFCOMMConnection` conforms to it. There's an internal `init(connection:pairedDevices:)` for tests, and `public convenience init()` keeps the old API.

**App layer**
- `AppSettings` (`Sources/XM6Control/AppSettings.swift`) has `@Published releaseWhenIdle`, persisted under the key `releaseHeadphonesWhenIdle`, **default `true`**. Its init is now `init(controller:)`. `shouldHideInitialWindow()` returns `releaseWhenIdle && menuBarOnly` the first time it's called.
- `XM6ControlApp` builds the controller and passes it to `AppSettings`. It attaches `.background(ControlSurfaceObserver(...))` to the main `WindowGroup`, the `MenuBarExtra(.window)` content and the floating `Window("XM6 Widget")`.
- `ContentView` doesn't call `autoConnect()` on appear in release mode. The visible-window path acquires the channel instead.
- UI:
  - The checkbox "Release the headphones when I'm not using the app" appears in both `DashboardView` and `CompactControlsView`.
  - The compact panel shows `LoadingRow` until the real ANC state arrives, and the prompt shows `lastError`.
  - The ambient sliders range over 1...20.
  - `DisconnectedView` says "Use Try Again to connect…".
- Startup writes are skipped in release mode on purpose. The legacy mode forces Off→NC and level 0→15 on the first report. In release mode the app reads the headset's real state instead.

### 2.4 Firmware 3.x ANC state (`0x17` / `0x19`)
- On FW 3.0.0 the `0x17` GET is answered with an all-zero placeholder, `67 17 00 00 00 00 00`, which decodes as Off at level 0. **Upstream bug:** in legacy mode the startup-defaults code reads that placeholder as Off/0 and **writes NC at level 15 to the headset on every connect**, which overwrites the user's real mode.
- Real reports use subtype `0x19` with 9 bytes: `[69][19][update][on][ambient][voice][level][?][?]`, for example `69 19 01 01 00 00 09 00 00`.
- `SonyCommands.decodeAmbientSound`:
  - Accepts `0x19` only when the payload is exactly 9 bytes, and reads voice and level at fixed offsets 5 and 6.
  - Sets `isUpdate = payload[2] != 0`.
  - Keeps the two unknown trailing bytes in `AmbientSoundState.trailingBytes`.
  - The older `0x15`/`0x17`/`0x22` layouts still decode as before.
- `buildAmbientSoundSet` maps subtype `0x19` back to `0x17`, the write dialect the headset accepts. It drops the wind-noise byte for `0x19` and clamps the level to 1...20.
- `requestFullState()` now also queries `0x19` (`66 19`).
- In release mode, the controller ignores a `0x17` report with `isUpdate == false`, so the UI stays in "loading" rather than showing a fake Off. It also clamps the displayed level to ≥ 1.

### 2.5 The Copilot review comment on initial window visibility (PR #4, `discussion_r4056744765`)
**The comment** (Copilot, on `Sources/XM6Control/Views/ControlSurfaceObserver.swift:57`, commit `efca6f6`, rated High):
> `publisher(for:)` is subscribed without the `.initial` option, so if the hosting window is already visible when `viewDidMoveToWindow()` runs … none of these publishers necessarily emits an event. The observer therefore leaves the surface marked invisible, so the controller does not acquire the channel and `hideFirstAppearance` is not applied.

**What the code does.** `SurfaceView.viewDidMoveToWindow()` does three things in order:
1. It calls `controller.setControlSurface(id, visible: false)`.
2. It subscribes to `Publishers.Merge3(window.publisher(for: \.isVisible), window.publisher(for: \.isMiniaturized), NSApp.publisher(for: \.isHidden))`, followed by `.receive(on: RunLoop.main)`.
3. In the sink, it re-reads live state, hides the window on its first appearance if `hideFirstAppearance` is set, and otherwise reports `isVisible && !isMiniaturized && !NSApp.isHidden`.

**Is the reported bug real? Partly. The stated cause is wrong.**
- In the macOS 26 SDK, `NSObject.publisher(for:options:)` defaults to `options: [.initial, .new]`. I checked this in `Foundation.swiftmodule/arm64e-apple-macos.swiftinterface`. So the initial value is emitted even without passing `.initial`.
- I copied `SurfaceView` into a SwiftUI test app, replaced the controller with a logger, and ran it on macOS 26.5.2. The harness is at `scratchpad/kvotest/probe.swift`, with logs `run1.log` to `run4.log`. Results:
  - **Launch window:** `viewDidMoveToWindow` runs with the window **already visible**. The `.initial` emissions fire synchronously. The sink runs 20–175 ms later and reports `visible: true`. So the initial state **is** picked up.
  - **Panel whose view is attached while hidden**, then `orderFront`. This mimics the menu-bar panel or a newly opened widget window. KVO on `isVisible` fires on `orderFront`, `orderOut`, `close`, reopen, app hide and unhide, and miniaturize. Detection works.
  - `NSApp.publisher(for: \.isHidden)` **never emits after the initial value**: `NSApplication.hidden` isn't KVO-observable. `isMiniaturized` emitted nothing on miniaturize either. Both cases are covered only incidentally, because the window's `isVisible` flips at the same time.

**The latent bugs that remain (why the comment still deserves a fix):**
1. **Initial state depends on implicit behaviour.**
   - Correctness relies on the default `[.initial, .new]` options and on undocumented KVO compliance of `NSWindow.isVisible`. Apple doesn't document either. Apple documents KVO compliance for only a few AppKit properties, and `isVisible` isn't among them.
   - The initial state is evaluated only inside the publisher chain. If a window becomes visible after the view attaches, and the system shows it without a KVO change, the surface stays invisible forever. Affected surfaces are the menu-bar panel's first open and the `Window("XM6 Widget")`.
   - That is exactly the symptom Copilot describes: the controller never acquires the channel. The app supports macOS 13+, and none of this was verified there.
2. **`hideFirstAppearance` flashes the window.** In menu-bar-only mode with release on, the main window is already visible when the view attaches. It's ordered out only after the `RunLoop.main` hop (20–175 ms measured), so the user sees the window flash at launch.
3. **`.receive(on: RunLoop.main)` delivers only in the default run-loop mode.** During live resize, menu tracking or drag tracking (event-tracking mode), surface changes wait until tracking ends.
4. **Two of the three publishers do nothing.** `NSApp.isHidden` and `isMiniaturized` only work because `isVisible` changes alongside them. Someone refactoring could drop `isVisible` and silently break hide and miniaturize.
5. **Design gap, not a crash.** A window that is open but on another Space, or fully covered, still counts as in use, so it holds the channel indefinitely. A user who never closes the main window never gets idle release.

**Proposed fix**
- Don't depend on KVO alone. Evaluate explicitly, and listen to notifications AppKit documents, delivered on `.main`:

```swift
final class SurfaceView: NSView {
    private var tokens: [NSObjectProtocol] = []
    private var visibleKVO: NSKeyValueObservation?
    var report: (UUID, Bool) -> Void          // injected; production passes controller.setControlSurface

    override func viewWillMove(toWindow newWindow: NSWindow?) {
        super.viewWillMove(toWindow: newWindow)
        stopObserving()
        if newWindow == nil { report(id, false) }
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard let window, !ProbeMode.active else { return }
        if hideFirstAppearance {                 // synchronous: the window is never drawn
            hideFirstAppearance = false
            window.orderOut(nil)
        }
        let nc = NotificationCenter.default
        func on(_ name: Notification.Name, _ object: AnyObject?, _ body: @escaping () -> Void) {
            tokens.append(nc.addObserver(forName: name, object: object, queue: .main) { _ in body() })
        }
        on(NSWindow.didChangeOcclusionStateNotification, window) { [weak self] in self?.evaluate() }
        on(NSWindow.didMiniaturizeNotification, window)          { [weak self] in self?.evaluate() }
        on(NSWindow.didDeminiaturizeNotification, window)        { [weak self] in self?.evaluate() }
        on(NSWindow.willCloseNotification, window)               { [weak self] in self.map { $0.report($0.id, false) } }
        on(NSApplication.didHideNotification, NSApp)             { [weak self] in self?.evaluate() }
        on(NSApplication.didUnhideNotification, NSApp)           { [weak self] in self?.evaluate() }
        // Keep KVO as a secondary signal, with explicit options.
        visibleKVO = window.observe(\.isVisible, options: [.initial, .new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.evaluate() }
        }
        evaluate()                                               // explicit initial state
        DispatchQueue.main.async { [weak self] in self?.evaluate() } // catches a same-turn orderFront
    }

    private func evaluate() {
        guard let window else { return report(id, false) }
        report(id, window.isVisible && !window.isMiniaturized && !NSApp.isHidden)
        // Optional policy: && window.occlusionState.contains(.visible), debounced ~1 s,
        // so a window parked on another Space or fully covered stops holding the channel.
    }
}
```

- Further changes:
  - **`willClose`.** Report `false` directly on `willClose`. `isVisible` is still `true` when that notification fires.
  - **Menu-bar-only launch.** Better still, don't create the main window at all in this mode. On macOS 15+, `WindowGroup(...).defaultLaunchBehavior(.suppressed)` does this. On 13–14, keep the synchronous `orderOut` shown above.
  - **Testability.** Inject the `report` closure so `SurfaceView` can be tested without `HeadphonesController`.
  - **Tests to add.** Add an XCTest in the `XM6ControlTests` target; Anneo22/main already shows how to test the executable target. The tests should:
    - attach the view before `orderFront` and expect `true`;
    - attach it to an already-visible window and expect `true` synchronously;
    - check that `orderOut`, `miniaturize`, `NSApp.hide` and `close` each report `false`;
    - check that `hideFirstAppearance` never leaves the window visible after `viewDidMoveToWindow` returns.

### 2.6 Tests added by PR #4
New target `SonyHeadphonesKitTests`, using XCTest. It has 43 tests and runs against the fake `HeadphonesConnection` transport, with no IOBluetooth.
- `ConnectionUsageTests` (4):
  - `testClosingLastSurfaceStartsGracePeriod`
  - `testCommandRestartsGraceAndOpeningCancelsIt`
  - `testRepeatedCloseDoesNotExtendDeadline`
  - `testDisabledPolicyNeverReleases`
- `AmbientSoundTests` (7), using captured `0x19` payloads as fixtures:
  - decode of observed modes
  - truncated, extended and invalid reports
  - writes use `0x17` and drop the trailer
  - placeholder keeps the update flag and raw level
  - unknown trailer preserved
  - level clamp
  - older layouts unchanged
- `HeadphonesControllerTests` (32):
  - surfaces acquire once
  - deferred commands applied in order after the handshake
  - an EQ drag connects and keeps the latest curve
  - explicit disconnect cancels commands and late callbacks
  - a fresh connection mirrors Off with no startup writes
  - zero placeholder vs `0x19` state
  - legacy startup defaults
  - remote-close yield despite a visible surface, and every way to reacquire after it (new surface, command, Connect)
  - remote close with a pending command, an awaited ACK or an EQ write does reconnect
  - local disconnect and idle release don't yield
  - close during handshake
  - retry teardown
  - legacy remote close
  - failure clears deferred commands
  - retry keeps the queue
  - missing init ACK
  - EQ debounce across a retry
  - idle expiry vs state queries
  - a **real 20 s timer** release (`testTimerReleasesAfterPendingCommandsSurviveDropPastDeadline`, about 21 s)
  - remembers a manually selected headset
  - enabling or disabling the setting

### 2.7 Build and test results (PR #4 branch, `efca6f6`)
| Command | Result |
|---|---|
| `swift build` | **OK** (no warnings) |
| `swift test` | **FAILS TO COMPILE**. `ConnectionUsageTests.swift:6/22/34: error: argument passed to call that takes no arguments` on `ConnectionUsage(releaseWhenIdle: true)`. `ConnectionUsage` has `private` stored properties, so on this toolchain the memberwise `init(releaseWhenIdle:)` isn't visible to the test module. |
| `swift test` with only the `ConnectionUsage.swift` hunk of `b1ef964` applied (adds an explicit `init(releaseWhenIdle: Bool = false)`) | **43/43 pass**, in 35 s |

Anneo22 hit the same failure on their hosted CI (`macos-15`) and fixed it in `b1ef964` on their `main`. The PR branch was never updated.

---

## 3. Anneo22/main: the 14 commits beyond PR #4

Anneo22/main is the PR branch (`efca6f6` is an ancestor) plus the commits below.

| # | SHA | Date | One-line summary | Files touched |
|---|---|---|---|---|
| 1 | `493360f` | 10-04 | Adds the **Bluetooth "sound quality" preference** (Quality / Stable / Low latency), read from the headset (AUDIO `0xe0`/`0xe1` capability and `0xe6`/`0xe7` param, subtype `0x05`). Adds a UI picker, plus a **local command bridge** (`AgentControl` and `Scripts/xm6control`). `applyConnectDefaults = false` disables the startup writes app-wide. | Package.swift (+`XM6ControlTests` target), README, Scripts/build_app.sh (`XM6_SIGN_IDENTITY`, `/usr/bin/trash`), **Scripts/xm6control (new, Python)**, HeadphonesController, **Models/SoundQualityMode.swift (new)**, Commands, HeadphonesEvent, Opcodes, **XM6Control/AgentControl.swift (new)**, AppSettings, ProbeMode, CompactControlsView, GroupedCards, **Views/SoundQualityPicker.swift (new)**, XM6ControlApp, Tests (HeadphonesController +171, **SoundQualityTests new**, **XM6ControlTests/AgentControlTests new**) |
| 2 | `a6bbd2a` | 10-04 | Fixes the quality write and notify formats from FW 3.1.5 captures. A write is now 4 bytes `e8 05 <mode> 01`. Notify is 4 bytes with a stream byte (≤ 2). RET is 3 bytes. | README, Commands, tests (3 files) |
| 3 | `c3fd178` | 10-04 | Makes Low latency read-only (`SoundQualityMode.canWriteLocally`), because the headset advertises it but ignores the write. | README, Scripts/xm6control, HeadphonesController, SoundQualityMode, AgentControl, SoundQualityPicker, tests |
| 4 | `4ea56b4` | 10-05 | Rebrands as a "personal fork". Adds **LICENSE-APACHE and NOTICE.md (Apache-2.0 for Anneo22's additions)**, a GitHub Actions workflow, a leak checker and a syntax checker. The README is rewritten with Castor branding and SVGs. | .github/workflows/checks.yml, LICENSE-APACHE, NOTICE.md, README.md, Scripts/build_app.sh (bundles licences into `Contents/Resources/Licences`), Scripts/check_leaks.py, Scripts/check_syntax.sh, docs/Literata-OFL.txt, docs/castor-footer.svg, docs/readme-mark.svg, docs/readme-title.svg |
| 5 | `7f8db28` | 10-05 | `build_app.sh` keeps earlier app bundles (it writes to a fresh `mktemp -d` dir instead of `rm -rf`). CI builds twice and verifies signatures and licences. | .github/workflows/checks.yml, README, Scripts/build_app.sh |
| 6 | `2a6afdb` | 10-05 | Updates CI to `actions/checkout@v7.0.1`. | .github/workflows/checks.yml |
| 7 | `27ec6bb` | 10-05 | Moves the studio link to the README footer only. | README.md |
| 8 | `b1ef964` | 10-05 | **Adds an explicit `ConnectionUsage.init(releaseWhenIdle:)`, which fixes test compilation** on hosted CI and on Swift 6.3.3. | ConnectionUsage.swift, NOTICE.md |
| 9 | `99c4c4b` | 10-05 | Changes the dark-mode colour of the README signature. | docs/castor-footer.svg |
| 10 | `1bff5ea` | 10-07 | Changes the footer colours. | docs/castor-footer.svg |
| 11 | `261c08d` | 10-11 | Standard Castor signature in the README. | README.md, docs/castor-footer.svg |
| 12 | `c2fce98` | 10-11 | Adds Castor and LinkedIn link rows to the README. | README.md, docs/link-castor.svg, docs/link-linkedin.svg |
| 13 | `4fae2f2` | 10-11 | Changes the signature colour order. | docs/castor-footer.svg |
| 14 | `428fea3` | 10-11 | Reverts the signature colour order. | docs/castor-footer.svg |

How the local command bridge (`AgentControl`) works:
1. `Scripts/xm6control` (Python) writes `<uuid>.request.json` into `$TMPDIR/XM6Control-<uid>/`. It checks that the directory is mode 0700 and owned by the user.
2. If the app isn't running, the script launches `/Applications/XM6 Control.app` with `--agent-control`, which hides the window.
3. The app polls the directory **every 250 ms forever**, executes `status` or `quality <mode>`, and confirms through a headset readback within 8–12 s.
4. The app writes `<uuid>.response.json`.

The bridge is scoped and defensive: symlink, size and age checks, and an allow-list of commands. But the polling breaks the README's "0% CPU at idle" claim, and the bundle path is hard-coded.

**Build and test (Anneo22/main `428fea3`):** `swift build` OK. `swift test` **59/59 pass** in 58 s, split as:
- AgentControlTests 3
- AmbientSoundTests 7
- ConnectionUsageTests 4
- HeadphonesControllerTests 40
- SoundQualityTests 5

**Licensing:** `NOTICE.md` on Anneo22/main declares Apache-2.0 for "original additions owned by Anneo22 in commits `1d3e3ec`, `efca6f6`, `493360f`, `a6bbd2a`, `c3fd178`, and subsequent…". That includes the PR #4 commits. The PR itself was offered to an MIT repo without a licence statement. If you take this code:
- Either comply with Apache-2.0: ship `LICENSE-APACHE` and the NOTICE text, and mark the changes.
- Or get Anneo22 to confirm in writing that the PR #4 contribution is MIT.

---

## 4. yelloduxx: PR #6 `macos27-redesign` (9 commits)

PR title: "Rebuild the interface for macOS 26 and add a WidgetKit widget". The author says it was tested on macOS 27.0.1 with a real XM6.

### 4.1 Per-commit summary
| SHA | Summary | Self-contained? |
|---|---|---|
| `78edeca` | **Redesign** (+694/−1504, 26 files). Grouped `Form`, Liquid Glass controls and a standard `Settings` scene. Deletes `Theme`, `CardBackground`, all the `*Card` views and `DesktopWidgetWindow`. Adds `BehaviorSections`, `DevicesSection`, `NoiseControlSection`, `SoundSection`, `SettingsView`. **`Package.swift` moves to tools 6.2 and `.macOS(.v26)`. `LSMinimumSystemVersion` becomes 26.0.** Also adds `--demo` (`HeadphonesController.loadDemoState()` plus an `isDemo` guard in `enqueue`) and `--dark`. | No. It's the foundation every later commit sits on. |
| `8b98102` | **WidgetKit widget** (small, medium, large) that replaces the floating panel. Adds `XM6Shared` (snapshot and mailbox), `WidgetBridge`, `XM6Widget`, `project.yml` (XcodeGen), `Config/XM6Widget-Info.plist` and `.entitlements`. Rewrites `build_app.sh` around `xcodebuild`. | Partly. Needs `SettingsView` and the `XM6ControlApp` changes from `78edeca`, and conflicts on Info.plist. |
| `78d81b9` | **Fixes the endless multipoint spinner.** Adds a 6 s `deviceListTimeoutTask` and `@Published deviceListTimedOut`, `refreshDeviceList()`, and a Retry row in `DevicesSection`. | The logic is portable. The UI lives in a file `78edeca` created, and the Retry button uses `.buttonStyle(.glass)`, which is macOS 26-only. |
| `0d80c3a` | Updates the README screenshot. | Not needed |
| `76fe590` | Adds widget and menu bar screenshots to the README. | Not needed |
| `242cfe8` | **ANC physical-button refresh.** `decodeAmbientSoundXM6Notify` accepts the 9-byte `0x19` report and stores subtype `0x17` for later writes. | Yes. Onto upstream it applies cleanly except for the `project.yml` version bump. It is superseded by PR #4 `efca6f6` (the two conflict in `Commands.swift`). |
| `0d71a47` | Widget publish debounce 250 → 100 ms. | Widget-only |
| `67b7df8` | `FitWindowToContent` modifier, which grows the main window to fit its content up to the screen height until the user resizes it. | Mostly. A new file plus one line in the redesigned `DashboardView`. Needs macOS 15 (`onScrollGeometryChange`). |
| `a962214` | **Open at login** using `SMAppService.mainApp`. The status is read back on `didBecomeActive`, and the "Open Login Items" link appears when approval is needed. | Yes for the `AppSettings` logic, which is macOS 13+ API. The toggle UI goes into `SettingsView`, which `78edeca` created. |

Every commit after `8b98102` bumps `CURRENT_PROJECT_VERSION` in `project.yml`, so cherry-picking any of them without the widget commit gives a `project.yml` modify/delete conflict. It's trivial: drop that hunk.

### 4.2 The WidgetKit widget in detail

**Build: SwiftPM, Xcode project, or XcodeGen?** XcodeGen.
- `project.yml` defines two Xcode targets:
  - `XM6ControlApp` (application). It compiles `Sources/XM6Control` directly and depends on the local package products `SonyHeadphonesKit` and `XM6Shared`.
  - `XM6Widget` (app-extension, `com.apple.widgetkit-extension`). It compiles `Sources/XM6Widget` and depends on `XM6Shared`.
- `Package.swift` exposes `SonyHeadphonesKit` and `XM6Shared` as library products. The widget isn't a SwiftPM target at all.
- `XM6Control.xcodeproj` is generated and git-ignored. `build_app.sh` runs `xcodegen generate` only "if command -v xcodegen", but the project isn't committed, so **XcodeGen and full Xcode are effectively required** for the `.app`.
- `swift build` still compiles the app executable (with `WidgetBridge`) but produces no bundle and no widget.

**Signing:**
- `xcodebuild … CODE_SIGNING_ALLOWED=NO`, then manual inside-out signing: `codesign --entitlements Config/XM6Widget.entitlements` on the appex, then the app. It uses `XM6Dev` when that identity exists, and ad-hoc otherwise.
- Hardened runtime is off. `MARKETING_VERSION` is 2.0.
- Bundle IDs are `com.local.xm6control` and `com.local.xm6control.widget`.
- `Info.plist` now uses build-setting variables (`$(EXECUTABLE_NAME)` and so on), so the old SwiftPM copy-the-plist packaging no longer works. The executable is renamed to "XM6 Control".

**How the app communicates state to the widget:**
- It doesn't use an App Group or a `UserDefaults` suite. The author says an ad-hoc-signed app can't use a group container shared with a sandboxed extension.
- It uses **plain files in `~/Library/Application Support/XM6 Control/Widget/`** (`SharedStore` in `Sources/XM6Shared/SharedStore.swift`). The widget finds the real home folder through `getpwuid`, because inside the sandbox `NSHomeDirectory()` points at its container.
- `snapshot.json` holds the app's `HeadphonesSnapshot` (Codable: battery, charging, `NoiseMode`, `ListeningModeOption`, `EqualizerOption`/label, `playingOn`, `updatedAt`).
- `WidgetBridge` (`@MainActor`, in the app):
  - subscribes to `controller.objectWillChange`, debounced 100 ms on `RunLoop.main`;
  - writes the snapshot only if it changed, ignoring `updatedAt`;
  - calls `WidgetCenter.shared.reloadTimelines(ofKind: "XM6HeadphonesWidget")`;
  - writes `.disconnected` on `willTerminate`.
- The widget's `TimelineProvider` returns one entry with `.never` policy. Nothing polls.
- **Widget to app:** a widget tap writes `command.json` (a single slot, so the newest command wins) and posts a **Darwin notification** `com.local.xm6control.widget-command` through `CFNotificationCenterGetDarwinNotifyCenter`. The app registers with `.deliverImmediately` and then `takeCommand()` reads and deletes the file.
- The widget's sandbox entitlement is `com.apple.security.temporary-exception.files.home-relative-path.read-write = /Library/Application Support/XM6 Control/Widget/`. Temporary exceptions aren't allowed on the Mac App Store; that's fine for direct distribution.

**AppIntents for ANC, listening mode and EQ preset** (`Sources/XM6Widget/WidgetIntents.swift`):
- There are 12 parameterless intents, all conforming to `WidgetCommandIntent`:
  - `SetNoiseCancellingIntent`, `SetAmbientSoundIntent`, `SetNoiseControlOffIntent`
  - `SetStandardListeningIntent`, `SetBackgroundMusicIntent`, `SetCinemaIntent`
  - `SetEqualizer{Off,Heavy,Clear,Hard,Soft,Custom}Intent`
- The author says an `AppEnum` parameter arrived as nil in the ad-hoc-signed bundle (`LNActionExecutorErrorDomain` 2010).
- `perform()` runs **in the widget process**:
  1. It optimistically patches `snapshot.json`.
  2. It posts the command.
  3. It reloads the timeline.
- Buttons are `Button(intent:)`.

**How an intent reaches the Bluetooth controller:**
1. The intent's `perform()` runs in the widget process.
2. `SharedStore.post` writes `command.json` and posts the Darwin notification.
3. In the app, `SharedStore.observeCommands` hops to the main queue and calls `WidgetBridge.runPendingCommand()`.
4. That calls `HeadphonesController.setAmbientSound`, `setListeningMode` or `setEqualizerPreset`.

Caveats:
- If the controller isn't `.connected`, the bridge **drops the command and republishes the real state**, which undoes the optimistic change.
- If the app isn't running, nothing happens. The stale command is discarded at the next launch.
- Darwin notifications and the mailbox aren't authenticated: any same-user process can post. That's acceptable locally.
- The author notes WidgetKit allows about one reload per 5 s while the app is frontmost.

**Interaction with PR #4 if both are integrated:**
- A WidgetKit widget runs out of process, so it isn't a `ControlSurfaceObserver` surface. In release mode the app releases the channel 20 s after its own windows close.
- `WidgetBridge.runPendingCommand` should **not** require `.connected`. With PR #4 it should call the controller setters directly, which auto-connect and queue the command.
- `snapshot(of:)` should publish "last known values plus released" instead of `isConnected: false`. Otherwise the widget shows "disconnected" whenever the link is idle.

**Verified build of the widget path:**
- `./Scripts/build_app.sh` on `a962214` succeeded (Xcode 26.6, XcodeGen 2.46.0, ad-hoc signed).
- The bundle contains `Contents/PlugIns/XM6Widget.appex` with `Metadata.appintents`. The appex has the sandbox and temporary-exception entitlements.
- `codesign --verify --deep --strict` passes. Both Info.plists have `LSMinimumSystemVersion` 26.0.
- Running `xcodegen` and `xcodebuild` **without** the script failed. `AppIcon.icns` is a git-ignored file that the script generates first, so the script is the only supported entry point.

### 4.3 Other topics
- **Autostart:** `SMAppService.mainApp.register()`/`unregister()`, with status read back (`.enabled`/`.requiresApproval`) and `SMAppService.openSystemSettingsLoginItems()`. It's macOS 13+ API and works with the SwiftPM app. I didn't test it with ad-hoc signing; register from the copy installed in `/Applications`.
- **Demo mode:**
  - `--demo` calls `loadDemoState()`, which fills in a plausible connected state, sets `connectionState = .connected` and blocks `enqueue`. `--dark` forces darkAqua.
  - About 35 lines, easy to port.
  - On top of PR #4, the `isDemo` guard must also cover `enqueueCommand`/`connectIfNeeded`. Otherwise a demo setter in release mode would try a real Bluetooth connect.
- **Multipoint infinite loader:** see `78d81b9` above. The author notes it fixes the spinner, not the missing list: the device acknowledges the device-list request (sent as `command2`) but never answers it, and no request variant the author tried got a reply.
- **ANC physical-button refresh:** see `242cfe8` above. It overlaps PR #4. PR #4's version is better: it also adds the `0x19` GET, the placeholder handling, the trailer preservation and fixture tests.
- **Minimum macOS:** **26.0** for app and widget (`Package.swift` `.macOS(.v26)`, `LSMinimumSystemVersion` 26.0, XcodeGen `deploymentTarget` 26.0). It needs tools 6.2 for `.v26`.
  - About 12 call sites use macOS 26-only APIs (`glassEffect`, `GlassEffectContainer`, `.buttonStyle(.glass/.glassProminent)`, `scrollEdgeEffectStyle`), with no availability guards. `openSettings`/`NSApp.activate()` need 14 and `onScrollGeometryChange` needs 15.
  - **Liquid Glass itself is macOS 26-only, but it doesn't force a 26 floor.** Upstream already guards `glassEffect` with `#if compiler(>=6.2)` plus `if #available(macOS 26.0, *)` and a material fallback (`CardBackground.swift`, `b65c95d`).
  - Desktop and Notification Center widgets with `Button(intent:)` need only macOS 14.
  - The author offered to do availability fallbacks instead.
- **Build results:** `swift build` OK (no warnings). There are no tests. `build_app.sh` (Xcode path) OK.

---

## 5. plagnioj: PR #1 `agent/xm6-control-center` (6 commits, based on `9b5db95`, 19 behind)

| SHA | Summary | Status upstream |
|---|---|---|
| `72ddda5` | `build_app.sh` recovers from a relocated Swift module cache. On "PCH was compiled with module cache path" or "built from a different branch", it runs `swift package clean` and retries once. | **Not upstream.** Cherry-picks cleanly onto `origin/main`. |
| `16b62b4` | Compile-time guard for `glassEffect` (`#if compiler(>=6.2)`). | **Already upstream** as `b65c95d` ("Suggested by plagnioj in #1"). Cherry-picking conflicts. |
| `e419557` | Removes the unused `let cx` in `HeadphoneImage.swift`. | **Already upstream.** `da88c33` removed the line, and the merge is a no-op. |
| `e0b562b` | Adds the `XM6SystemIntegration` library: `XM6SystemAction` enum, `XM6SystemActionStore` (single-slot `UserDefaults` key `pendingXM6SystemAction`), `XM6SystemActionMailbox` (posts `NotificationCenter.default` `didSubmit`), and 5 `AppIntent`s with `openAppWhenRun = true`. Adds `XM6SystemActionBridge`, a zero-size view attached to the **MenuBarExtra label** that consumes actions, opens windows, or queues an ANC change until connected. 3 tests. | Not upstream |
| `a1d4dc4` | `XM6ControlWidgets` extension with five **`ControlWidget`s** (`StaticControlConfiguration` + `ControlWidgetButton`): Open controls, Open widget, Noise Cancelling, Ambient Sound, Sound Control Off. They sit under `#if compiler(>=6.2)` with a compile-only fallback `StaticConfiguration` widget. Adds a **hand-written, committed `XM6Control.xcodeproj`** (with a .gitignore exception). App deployment target 13.0, extension 26.0. Entitlements: `app-sandbox` only. | Not upstream |
| `2829d5f` | `build_app.sh` picks the build path from the SDK. With SDK ≥ 26 it runs `xcodebuild` (app plus appex) and checks that the appex is embedded. Otherwise it uses the old SwiftPM path. It signs the extension, then the app. README docs. | Not upstream |

**Control Center controls.** Yes, WidgetKit `ControlWidget` with `ControlWidgetButton` and no toggles. That needs **macOS 26** (Control Center controls on the Mac arrived in macOS 26), so the extension's deployment target is 26.0. The app stays at 13.0.

**IPC design:**
1. An intent's `perform()` calls `XM6SystemActionMailbox.submit`, which writes `UserDefaults.standard[pendingXM6SystemAction]` and posts `NotificationCenter.default`.
2. The app-side bridge listens for `didSubmit` and also consumes the slot `onAppear`.
3. ANC actions call `autoConnect()` if needed and apply the change when `$connectionState` becomes `.connected`.

**The maintainer's documented problems** (ruimartins23, PR #1 comment):
1. There's no App Group in the extension entitlements (`app-sandbox` only). So `UserDefaults.standard` in the extension is a different store from the app's.
2. `NotificationCenter.default` doesn't cross processes. "A tap writes somewhere the app never looks and posts something the app never hears."
3. The store tests pass only because they use an explicit `suiteName` inside one process.
4. What's needed: an App Group on both targets with `UserDefaults(suiteName:)`, a Darwin notification, and a real-hardware run showing that a tile changes something.
5. He rejects the Xcode project and the second build path. He wants plain `swift build` only, and won't maintain a project file for a feature he can't test.
6. mlazzarotto also noted that the PR keeps tools 5.9, which breaks on CLT 26.6. PR #2 has since fixed that upstream.

Additional observations:
- Because the intents use `openAppWhenRun = true`, the system may run `perform()` in the app process, which would make the in-process mailbox work by accident. Nobody has verified this on macOS 26.
- Attaching the bridge to a `MenuBarExtra` label relies on SwiftUI keeping label modifiers live (`onReceive`/`onAppear`). SwiftUI doesn't guarantee that.
- yelloduxx's file mailbox plus Darwin notification already solves the cross-process part without an App Group.

**Build results:** `swift build` OK and `swift test` 3/3 pass on Swift 6.3.3. Tools 5.9 links fine on this machine. I didn't run the Xcode path (`build_app.sh`).

---

## 6. Build and test matrix (Swift 6.3.3 / Xcode 26.6 / macOS 26.5.2)

| Branch @ commit | `swift build` | `swift test` | Other |
|---|---|---|---|
| origin/main `af6d89b` | OK | no test target | — |
| Anneo22 PR #4 `efca6f6` | OK | **compile error** (`ConnectionUsage(releaseWhenIdle:)`) | 43/43 pass with `b1ef964`'s 4-line init applied |
| Anneo22/main `428fea3` | OK | **59/59 pass** (58 s) | — |
| yelloduxx PR #6 `a962214` | OK (app only, no widget) | no test target | `Scripts/build_app.sh` (XcodeGen + xcodebuild) **OK**. Appex embedded with `Metadata.appintents`; `codesign --verify --deep --strict` OK. |
| plagnioj PR #1 `2829d5f` | OK | 3/3 pass | Xcode path not run |

Logs are in `scratchpad/buildlogs/`. Worktrees are in `scratchpad/wt-origin`, `wt-anneo-pr`, `wt-anneo-pr-fixed` (uncommitted patch), `wt-anneo-main`, `wt-yelloduxx` (generated xcodeproj and icns, both ignored) and `wt-plagnioj`.

Conflict simulation used `git merge-tree`, which changes no refs:
- **PR #4 vs PR #6** conflict in 6 files: Commands.swift, AppSettings.swift, CompactControlsView.swift, DashboardView.swift, NoiseControlCard.swift (modify/delete), and XM6ControlApp.swift.
- `78edeca` onto PR #4 conflicts in 4 files.
- `72ddda5` and `a1d4dc4` cherry-pick cleanly onto `origin/main`.
- The `493360f → a6bbd2a → c3fd178` chain applies on top of PR #4 as a range.

---

## 7. Integration recommendation

| Component | Source branch / commit | Integrate as-is / adapt / reference only | Risk |
|---|---|---|---|
| Package.swift tools 6.0 + `swiftLanguageMode(.v5)` | mlazzarotto PR #2 → upstream `b96d4fc` | **Already in origin/main**, nothing to do | None |
| `glassEffect` compile-time guard | plagnioj `16b62b4` → upstream `b65c95d` | Already upstream | None |
| Unused `cx` removal | plagnioj `e419557` | Already upstream (`da88c33`) | None |
| On-demand control channel (`ConnectionUsage`, controller queue, retries, yield, immediate disconnect, `HeadphonesConnection` seam, setting) | Anneo22 `1d3e3ec`, `efca6f6` + `b1ef964` (only the `ConnectionUsage.swift` hunk) | **Adapt.** Take the commits, then add a follow-up that hardens `ControlSurfaceObserver` (§2.5) and decides the launch policy. | Medium: core controller rewrite and default-on behaviour change, validated on one headset with FW 3.0.0. Apache-2.0 claim in the fork's NOTICE. |
| FW 3.x ANC `0x19` decode, `0x19` query, placeholder handling, level clamp | Anneo22 `efca6f6` | **As-is** with the PR. Consider making placeholder suppression unconditional rather than release-mode-only, or set `applyConnectDefaults = false` as Anneo22/main does. | Low. It also fixes the upstream bug where the startup defaults write NC/15 over the real state. |
| ANC physical-button `0x19` notify decode (alternative) | yelloduxx `242cfe8` | **Reference only** (superseded by `efca6f6`). As-is only if PR #4 isn't taken: drop the `project.yml` hunk. | Low |
| Controller unit tests and fake transport | Anneo22 `1d3e3ec`, `efca6f6`, `b1ef964` | As-is | Low. One test sleeps a real 20 s. |
| Bluetooth sound-quality preference (read, write Quality/Stable) | Anneo22 `493360f`, `a6bbd2a`, `c3fd178` | **Adapt.** Keep the kit, model, decoder, picker and tests. Drop `AgentControl`/`xm6control` unless needed. | Medium: verified only on FW 3.1.5, a write briefly interrupts audio, Apache-2.0 |
| Local command bridge (`AgentControl`, `Scripts/xm6control`) | Anneo22 `493360f`, `c3fd178` | **Reference only** | Medium: polls every 250 ms forever, path hard-coded to `/Applications`, Python helper |
| CI workflow, leak and syntax checks, non-destructive `build_app.sh` | Anneo22 `4ea56b4`, `7f8db28`, `2a6afdb` | **Reference only / adapt** the workflow (it fits issue #5) | Low. Mixed with personal branding and licence files. |
| README branding, Castor SVGs | Anneo22 `27ec6bb`, `99c4c4b`, `1bff5ea`, `261c08d`, `c2fce98`, `4fae2f2`, `428fea3` | Skip | n/a |
| Liquid Glass Form redesign plus Settings scene | yelloduxx `78edeca` | **Reference only**, or adapt behind `#available(macOS 26)` with fallbacks | High: drops macOS 13–15, deletes about 12 view files, conflicts with PR #4 in 4–6 files |
| Demo mode (`--demo`, `--dark`) | yelloduxx `78edeca` (part) | **Adapt** (hand-port about 35 lines; guard `enqueueCommand`/`connectIfNeeded` too) | Low |
| Multipoint device-list timeout plus Retry | yelloduxx `78d81b9` | **Adapt.** Port `deviceListTimedOut`/`refreshDeviceList` into the controller and the Retry row into upstream's devices UI. Replace `.glass` with `.bordered` on < 26. | Low |
| Open at login (`SMAppService`) | yelloduxx `a962214` | **Adapt.** Port the `AppSettings` logic and add a toggle where upstream keeps app preferences. | Low (macOS 13+; check ad-hoc signing) |
| Main window fits content | yelloduxx `67b7df8` | Adapt with `if #available(macOS 15, *)` | Low |
| WidgetKit desktop and NC widget (file mailbox, Darwin notification, parameterless AppIntents) | yelloduxx `8b98102`, `0d71a47` | **Adapt.** Lower the widget target to macOS 14, keep `XM6Shared` in SwiftPM, change `WidgetBridge` to command-through-controller under PR #4, and publish "released" rather than "disconnected". | High: needs XcodeGen and full Xcode (a second build path the maintainer explicitly dislikes), temporary sandbox exception, app must be running, reload throttling |
| Control Center controls (`ControlWidget`, macOS 26) | plagnioj `a1d4dc4`, `e0b562b`, `2829d5f` | **Reference only.** Rebuild on yelloduxx's file-mailbox plus Darwin IPC if wanted. | High: cross-process IPC broken as written, committed hand-made xcodeproj, untested on 26 |
| Swift module-cache relocation recovery in `build_app.sh` | plagnioj `72ddda5` | **As-is** (applies cleanly onto origin/main) | Low (script only) |
| Screenshots | yelloduxx `0d80c3a`, `76fe590` | Skip | n/a |

### Recommended cherry-pick order (onto `origin/main`)
1. `1d3e3ec`, then `efca6f6` (PR #4).
2. `b1ef964` (keep only `Sources/SonyHeadphonesKit/ConnectionUsage.swift`, drop `NOTICE.md`). Without it, `swift test` doesn't compile.
3. Your own follow-up commit: harden `ControlSurfaceObserver` (§2.5) and add AppKit tests for it.
4. `72ddda5` (build-script cache recovery).
5. Optional: `493360f`, `a6bbd2a`, `c3fd178` (quality preference). Drop `AgentControl`, `Scripts/xm6control` and the `--agent-control` hook, or keep them deliberately.
6. Hand-port, not cherry-pick: the `a962214` `AppSettings` piece, the `78d81b9` controller piece, the `78edeca` demo-mode piece, and `67b7df8` behind `#available`.
7. Only if you accept an XcodeGen build path: `8b98102`, `0d71a47`, adapted as described above.

Do not take: `78edeca` wholesale (unless you go macOS 26-only), `242cfe8` (superseded), or the plagnioj `a1d4dc4`/`e0b562b`/`2829d5f`.

Before you ship Anneo22 code, resolve the licence question: either comply with Apache-2.0 or get an explicit MIT confirmation for the PR #4 commits.
