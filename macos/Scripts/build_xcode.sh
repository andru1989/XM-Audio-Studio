#!/bin/bash
# Builds XM Audio Studio with Xcode, including the WidgetKit extension, into
# build/XM Audio Studio.app. Needs full Xcode and XcodeGen (brew install xcodegen).
# The SwiftPM path (swift build / Scripts/build_app.sh) is unchanged and has no widget.
#
# Usage: Scripts/build_xcode.sh [Release|Debug]
#
# Environment:
#   XMAS_ARCHS          default "arm64 x86_64" (universal)
#   XMAS_TEAM_ID        default P5M226DH69 (must match the App Group prefix in
#                       Config/*.entitlements and Sources/WidgetBridgeKit/AppGroup.swift)
#   XMAS_SIGNING        auto (default): Xcode automatic signing, then the manual fallback
#                       automatic: Xcode automatic signing only
#                       manual: build unsigned, then codesign with the entitlements
#   XMAS_SIGN_IDENTITY  identity for manual signing (default: first "Apple Development")
#
# Signing notes (details in docs/macos/WIDGETS.md): the App Group is macOS-style
# (team-prefixed), which needs no provisioning profile, so automatic signing works with
# just an Apple Development certificate of the team in the keychain, no Xcode account.
# The manual fallback signs inside-out (extension, then app) with the same entitlements.
# Ad-hoc signing builds the bundle but leaves the widget without data: the App Group is
# authorised by the team ID, which an ad-hoc signature doesn't have.
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIG="${1:-Release}"
ARCHS="${XMAS_ARCHS:-arm64 x86_64}"
TEAM_ID="${XMAS_TEAM_ID:-P5M226DH69}"
SIGNING="${XMAS_SIGNING:-auto}"
PROJECT="XMAudioStudio.xcodeproj"
SCHEME="XMAudioStudioApp"
DERIVED="build/DerivedData"
APP_NAME="XM Audio Studio.app"
APPEX_NAME="XMAudioStudioWidget.appex"
PRODUCTS="${DERIVED}/Build/Products/${CONFIG}"
OUTPUT="build/${APP_NAME}"
LOG_DIR="build/logs"
APP_ENTITLEMENTS="Config/XMAudioStudio.entitlements"
WIDGET_ENTITLEMENTS="Config/XMAudioStudioWidget.entitlements"
RESOURCES="Sources/XMAudioStudio/Resources"

if ! command -v xcodegen >/dev/null; then
    echo "error: xcodegen not found (brew install xcodegen)" >&2
    exit 1
fi
if ! xcodebuild -version >/dev/null 2>&1; then
    echo "error: xcodebuild needs full Xcode (xcode-select -s /Applications/Xcode.app)" >&2
    exit 1
fi

mkdir -p "${LOG_DIR}"

if [ ! -f "${RESOURCES}/AppIcon.icns" ] || [ "Scripts/make_icon.swift" -nt "${RESOURCES}/AppIcon.icns" ]; then
    echo "==> Generating app icon..."
    swift Scripts/make_icon.swift
fi

echo "==> Generating ${PROJECT} (XcodeGen)..."
xcodegen generate --spec project.yml --quiet

COMMON_ARGS=(
    -project "${PROJECT}" -scheme "${SCHEME}" -configuration "${CONFIG}"
    -derivedDataPath "${DERIVED}" -destination "generic/platform=macOS"
    "ARCHS=${ARCHS}" ONLY_ACTIVE_ARCH=NO
)

build_automatic() {
    local log="${LOG_DIR}/xcodebuild-automatic.log"
    echo "==> xcodebuild ${CONFIG}, automatic signing (team ${TEAM_ID}, -allowProvisioningUpdates)..."
    if xcodebuild "${COMMON_ARGS[@]}" -allowProvisioningUpdates \
        CODE_SIGN_STYLE=Automatic "DEVELOPMENT_TEAM=${TEAM_ID}" build >"${log}" 2>&1; then
        return 0
    fi
    echo "    Automatic signing failed; last errors (full log: ${log}):"
    grep -E "error:|Provisioning|No Account|No signing certificate" "${log}" | sort -u | tail -8 | sed 's/^/    /'
    return 1
}

build_unsigned() {
    local log="${LOG_DIR}/xcodebuild-unsigned.log"
    echo "==> xcodebuild ${CONFIG}, unsigned (signed afterwards)..."
    if ! xcodebuild "${COMMON_ARGS[@]}" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" build >"${log}" 2>&1; then
        grep -E "error:" "${log}" | sort -u | tail -20 >&2
        echo "error: xcodebuild failed (full log: ${log})" >&2
        exit 1
    fi
}

sign_manually() {
    local app="$1" identity="${XMAS_SIGN_IDENTITY:-}"
    if [ -z "${identity}" ]; then
        identity="$(security find-identity -v -p codesigning 2>/dev/null \
            | sed -n 's/.*"\(Apple Development: [^"]*\)".*/\1/p' | head -1)"
    fi
    local appex="${app}/Contents/PlugIns/${APPEX_NAME}"
    if [ -n "${identity}" ]; then
        echo "==> Signing with: ${identity}"
        # Inside-out: the extension first, then the app (no --deep: each part gets its own entitlements).
        codesign --force --options runtime --timestamp=none --sign "${identity}" \
            --entitlements "${WIDGET_ENTITLEMENTS}" "${appex}"
        codesign --force --options runtime --timestamp=none --sign "${identity}" \
            --entitlements "${APP_ENTITLEMENTS}" "${app}"
    else
        echo "==> No Apple Development identity: ad-hoc signing."
        echo "    warning: the App Group can't be authorised without a team ID; the widget will"
        echo "    install but show no data, and its buttons can't reach the app."
        local tmp
        tmp="$(mktemp -d -t xmas-sign)"
        /usr/libexec/PlistBuddy -c "Add :com.apple.security.app-sandbox bool true" "${tmp}/widget.entitlements" >/dev/null
        codesign --force --sign - --entitlements "${tmp}/widget.entitlements" "${appex}"
        codesign --force --sign - "${app}"
        rm -rf "${tmp}"
    fi
}

case "${SIGNING}" in
    auto)
        if ! build_automatic; then
            build_unsigned
            sign_manually "${PRODUCTS}/${APP_NAME}"
        fi
        ;;
    automatic)
        build_automatic || exit 1
        ;;
    manual)
        build_unsigned
        sign_manually "${PRODUCTS}/${APP_NAME}"
        ;;
    *)
        echo "error: XMAS_SIGNING must be auto, automatic or manual" >&2
        exit 1
        ;;
esac

echo "==> Copying to ${OUTPUT}"
rm -rf "${OUTPUT}"
ditto "${PRODUCTS}/${APP_NAME}" "${OUTPUT}"

APPEX="${OUTPUT}/Contents/PlugIns/${APPEX_NAME}"
if [ ! -d "${APPEX}" ]; then
    echo "error: ${APPEX_NAME} is not embedded in the app" >&2
    exit 1
fi
codesign --verify --deep --strict "${OUTPUT}"

team="$(codesign -dv "${OUTPUT}" 2>&1 | sed -n 's/^TeamIdentifier=//p')"
echo "==> Architectures: app $(lipo -archs "${OUTPUT}/Contents/MacOS/XMAudioStudio"), widget $(lipo -archs "${APPEX}/Contents/MacOS/XMAudioStudioWidget")"
echo "==> Signed by team: ${team:-none}"
echo "==> App Groups: app $(codesign -d --entitlements - --xml "${OUTPUT}" 2>/dev/null | grep -o '[A-Z0-9]*\.group\.[a-z0-9.]*' | head -1 || true)," \
     "widget $(codesign -d --entitlements - --xml "${APPEX}" 2>/dev/null | grep -o '[A-Z0-9]*\.group\.[a-z0-9.]*' | head -1 || true)"
if [ "${team}" != "${TEAM_ID}" ]; then
    echo "    warning: not signed by team ${TEAM_ID}; widget IPC through the App Group won't work."
fi
echo "==> Done: ${OUTPUT}"
echo "    Launch it once (open \"${OUTPUT}\") so macOS registers the widget, then check with:"
echo "    pluginkit -m -p com.apple.widgetkit-extension | grep xmaudiostudio"
