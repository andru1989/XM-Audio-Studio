#!/bin/bash
# Builds XM Audio Studio and packages it into a double-clickable .app bundle.
# Needs only the Swift toolchain (Command Line Tools); Xcode is not required.
#
# Usage: Scripts/build_app.sh [release|debug]
#
# Code signing, in order of preference:
#   1. $XMAS_SIGN_IDENTITY, if set.
#   2. The first "Apple Development" identity in the keychain.
#   3. A self-signed identity named "XMAudioDev".
#   4. Ad-hoc signing.
# A stable identity (1-3) lets macOS keep the Bluetooth permission across rebuilds;
# ad-hoc signing re-prompts after every build.
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIG="${1:-release}"
APP_NAME="XM Audio Studio"
EXECUTABLE="XMAudioStudio"
BUILD_DIR=".build/${CONFIG}"
APP_DIR=".build/${APP_NAME}.app"
RESOURCES="Sources/XMAudioStudio/Resources"

# Swift precompiled headers record absolute module-cache paths, so moving the
# checkout breaks incremental builds. Detect that signature and rebuild clean once.
# (Approach from plagnioj/xm6-control@72ddda5.)
build_with_cache_recovery() (
    local build_log
    build_log="$(mktemp -t xmas-build.XXXXXX)"
    trap 'rm -f "${build_log}"' EXIT

    if swift build -c "${CONFIG}" 2>&1 | tee "${build_log}"; then
        return 0
    fi
    if grep -Eq "PCH was compiled with module cache path|PCH file .* was built from a different branch|missing required module 'SwiftShims'" "${build_log}"; then
        echo "==> Stale Swift module cache detected; cleaning and retrying..."
        swift package clean
        swift build -c "${CONFIG}"
        return
    fi
    return 1
)

echo "==> Building (${CONFIG})..."
build_with_cache_recovery

if [ ! -f "${RESOURCES}/AppIcon.icns" ] || [ "Scripts/make_icon.swift" -nt "${RESOURCES}/AppIcon.icns" ]; then
    echo "==> Generating app icon..."
    swift Scripts/make_icon.swift
fi

echo "==> Assembling ${APP_NAME}.app..."
rm -rf "${APP_DIR}"
mkdir -p "${APP_DIR}/Contents/MacOS" "${APP_DIR}/Contents/Resources"
cp "${BUILD_DIR}/${EXECUTABLE}" "${APP_DIR}/Contents/MacOS/${EXECUTABLE}"
cp "${RESOURCES}/Info.plist" "${APP_DIR}/Contents/Info.plist"
cp "${RESOURCES}/AppIcon.icns" "${APP_DIR}/Contents/Resources/AppIcon.icns"
for lproj in "${RESOURCES}"/*.lproj; do
    [ -d "${lproj}" ] && cp -R "${lproj}" "${APP_DIR}/Contents/Resources/"
done

identity="${XMAS_SIGN_IDENTITY:-}"
if [ -z "${identity}" ]; then
    identity="$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*"\(Apple Development: [^"]*\)".*/\1/p' | head -1)"
fi
if [ -z "${identity}" ] && security find-identity -p codesigning 2>/dev/null | grep -q '"XMAudioDev"'; then
    identity="XMAudioDev"
fi

if [ -n "${identity}" ]; then
    echo "==> Signing with: ${identity}"
    codesign --force --deep --options runtime --sign "${identity}" "${APP_DIR}"
else
    echo "==> Ad-hoc signing (Bluetooth permission will be asked again after each rebuild)"
    codesign --force --deep --sign - "${APP_DIR}"
fi
codesign --verify --deep --strict "${APP_DIR}"

echo "==> Done: ${APP_DIR}"
echo "    Launch with: open \"${APP_DIR}\""
