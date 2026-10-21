#!/bin/bash
# End-to-end check of the widget IPC without WidgetKit: builds a tiny command-line tool
# from Sources/WidgetBridgeKit, signs it with the *widget extension's* entitlements
# (App Sandbox + App Group) and the same Apple Development identity, then does what a
# widget button does: drop a command in the App Group mailbox, post the Darwin
# notification, and wait for the running app to apply it and publish a new snapshot.
#
# Usage (with the Xcode build running, e.g. open "build/XM Audio Studio.app" --args --demo):
#   Scripts/verify_widget_ipc.sh show
#   Scripts/verify_widget_ipc.sh noise ambientSound|noiseCancelling|off
#   Scripts/verify_widget_ipc.sh eq off|heavy|clear|hard|soft|custom
#
# With the app NOT running, XMAS_LAUNCH_APP=/path/to/XM\ Audio\ Studio.app makes the probe
# launch it, as the widget's intents do. Careful: a sandboxed launcher can't pass arguments,
# so the app starts in the mode its settings say (real Bluetooth unless demo mode is on).
#
# Side effect: the sandbox creates ~/Library/Containers/io.github.andru1989.xmaudiostudio.ipcprobe.
set -euo pipefail

cd "$(dirname "$0")/.."

# Outside the checkout on purpose: a sandboxed executable inside a TCC-protected folder
# (~/Documents, ~/Desktop, ~/Downloads) can't start, because secinitd may not read it.
# The same applies to the widget extension; see docs/macos/WIDGETS.md.
WORK="${TMPDIR:-/tmp}/xmas-ipcprobe"
mkdir -p "${WORK}"

cat > "${WORK}/main.swift" <<'SWIFT'
import Foundation
import AppKit

func describe(_ s: WidgetSnapshot?) -> String {
    guard let s else { return "no snapshot" }
    let outcome = s.lastOutcome.map { "\($0.action) -> \($0.result.rawValue)" } ?? "-"
    return "link=\(s.link.rawValue) model=\(s.modelID ?? "-") battery=\(s.batteryLevel.map(String.init) ?? "-")"
        + " noise=\(s.noiseMode?.rawValue ?? "-") eq=\(s.eqPreset?.rawValue ?? s.eqPresetCode.map { String($0) } ?? "-")"
        + " profile=\(s.eqProfileName ?? "-") demo=\(s.isDemo) pending=\(s.pendingActions.count) lastOutcome=\(outcome)"
        + " updated=\(s.updatedAt)"
}

let args = Array(CommandLine.arguments.dropFirst())
print("sandboxed: \(NSHomeDirectory().contains("/Library/Containers/"))")
guard let store = WidgetStore.appGroup(requireEntitlement: true) else {
    print("FAIL: this binary doesn't carry the App Group entitlement \(AppGroup.identifier)")
    exit(2)
}
print("group container: \(store.directory.path)")
print("before: \(describe(store.readSnapshot()))")
guard args.count == 2 else { exit(0) }

let action: WidgetAction
switch args[0] {
case "noise":
    guard let mode = NoiseMode(rawValue: args[1]) else { print("unknown mode"); exit(64) }
    action = .setNoiseMode(mode)
case "eq":
    guard let preset = EqPresetValue(rawValue: args[1]) else { print("unknown preset"); exit(64) }
    action = .applyEqPreset(preset)
default:
    print("usage: show | noise <mode> | eq <preset>"); exit(64)
}

func reached(_ s: WidgetSnapshot) -> Bool {
    switch action {
    case .setNoiseMode(let mode): return s.noiseMode == mode
    case .applyEqPreset(let preset): return s.eqPreset == preset
    }
}

let command = WidgetCommand(action: action)
let started = Date()
do {
    try store.post(command) // file in Commands/ + Darwin notification
} catch {
    print("FAIL: couldn't write the command: \(error)")
    exit(1)
}
print("posted \(action) id=\(command.id)")

// Like AppLauncher in WidgetExtension/WidgetIntents.swift: nothing listens if the app isn't
// running, so launch it (from inside the sandbox) and let it take the command at startup.
let running = !NSRunningApplication.runningApplications(withBundleIdentifier: AppGroup.appBundleIdentifier).isEmpty
print("app running: \(running)")
if !running, let path = ProcessInfo.processInfo.environment["XMAS_LAUNCH_APP"] {
    let configuration = NSWorkspace.OpenConfiguration()
    configuration.activates = false
    let done = DispatchSemaphore(value: 0)
    NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: path), configuration: configuration) { app, error in
        print("launched: \(app?.processIdentifier.description ?? "no") \(error.map { "error: \($0)" } ?? "")")
        done.signal()
    }
    done.wait()
}

while Date().timeIntervalSince(started) < 30 {
    if let s = store.readSnapshot(), let outcome = s.lastOutcome, outcome.commandID == command.id {
        if outcome.result.isFailure {
            print("after:  \(describe(s))")
            print(String(format: "FAIL: the app reported %@ after %.1f s", outcome.result.rawValue, Date().timeIntervalSince(started)))
            exit(1)
        }
        if reached(s) {
            print("after:  \(describe(s))")
            print(String(format: "OK: applied (%@) and published in %.1f s", outcome.result.rawValue, Date().timeIntervalSince(started)))
            exit(0)
        }
    }
    usleep(100_000)
}
print("after:  \(describe(store.readSnapshot()))")
let leftover = store.queuedCommands(maxAge: 3600).contains { $0.id == command.id }
print("FAIL: no result within 30 s" + (leftover ? " (command still in the mailbox: is the app running and team-signed?)" : ""))
exit(1)
SWIFT

cat > "${WORK}/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>io.github.andru1989.xmaudiostudio.ipcprobe</string>
<key>CFBundleName</key><string>ipcprobe</string>
</dict></plist>
PLIST

swiftc -O -swift-version 5 Sources/WidgetBridgeKit/*.swift "${WORK}/main.swift" -o "${WORK}/ipcprobe" \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "${WORK}/Info.plist"

identity="${XMAS_SIGN_IDENTITY:-$(security find-identity -v -p codesigning 2>/dev/null \
    | sed -n 's/.*"\(Apple Development: [^"]*\)".*/\1/p' | head -1)}"
if [ -z "${identity}" ]; then
    echo "error: needs an Apple Development identity (the App Group is authorised by its team ID)" >&2
    exit 1
fi
codesign --force --options runtime --sign "${identity}" \
    --entitlements Config/XMAudioStudioWidget.entitlements "${WORK}/ipcprobe" 2>/dev/null

"${WORK}/ipcprobe" "$@"
