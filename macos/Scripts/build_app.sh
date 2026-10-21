#!/bin/bash
# Builds XM Audio Studio and packages it into a double-clickable .app bundle.
# Needs only the Swift toolchain (Command Line Tools); Xcode is not required.
#
# Usage: Scripts/build_app.sh [release|debug]
#
# Builds a universal binary (Apple Silicon arm64 + Intel x86_64) by default.
# Set XMAS_ARCHS="arm64" (or "x86_64") for a faster single-architecture build.
#
# Code signing, in order of preference:
#   1. $XMAS_SIGN_IDENTITY, if set.
#   2. The first "Apple Development" identity in the keychain.
#   3. A self-signed identity named "XMAudioDev".
#   4. Ad-hoc signing.
# A stable identity (1-3) lets macOS keep the Bluetooth and System Audio Recording
# permissions across rebuilds; ad-hoc signing re-prompts after every build.
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIG="${1:-release}"
APP_NAME="XM Audio Studio"
EXECUTABLE="XMAudioStudio"
ARCHS="${XMAS_ARCHS:-arm64 x86_64}"
ARCH_FLAGS=()
for arch in ${ARCHS}; do ARCH_FLAGS+=(--arch "${arch}"); done
if [ "$(echo ${ARCHS} | wc -w | tr -d ' ')" -gt 1 ]; then
    # Multi-arch builds go through the Xcode build system into .build/apple.
    BUILD_DIR=".build/apple/Products/$(tr '[:lower:]' '[:upper:]' <<< "${CONFIG:0:1}")${CONFIG:1}"
else
    BUILD_DIR=".build/${ARCHS}-apple-macosx/${CONFIG}"
fi
APP_DIR=".build/${APP_NAME}.app"
RESOURCES="Sources/XMAudioStudio/Resources"
# Hardened-runtime entitlements: audio input for the optional local DSP's process tap.
ENTITLEMENTS="${RESOURCES}/XMAudioStudio.entitlements"

# Swift precompiled headers record absolute module-cache paths, so moving the
# checkout breaks incremental builds. Detect that signature and rebuild clean once.
# (Approach from plagnioj/xm6-control@72ddda5.)
build_with_cache_recovery() (
    local build_log
    build_log="$(mktemp -t xmas-build.XXXXXX)"
    trap 'rm -f "${build_log}"' EXIT

    if swift build -c "${CONFIG}" "${ARCH_FLAGS[@]}" 2>&1 | tee "${build_log}"; then
        return 0
    fi
    if grep -Eq "PCH was compiled with module cache path|PCH file .* was built from a different branch|missing required module 'SwiftShims'" "${build_log}"; then
        echo "==> Stale Swift module cache detected; cleaning and retrying..."
        swift package clean
        swift build -c "${CONFIG}" "${ARCH_FLAGS[@]}"
        return
    fi
    return 1
)

echo "==> Building (${CONFIG}, ${ARCHS})..."
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
if [ -d "${RESOURCES}/Models" ]; then
    cp -R "${RESOURCES}/Models" "${APP_DIR}/Contents/Resources/"
fi
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
    codesign --force --deep --options runtime --entitlements "${ENTITLEMENTS}" --sign "${identity}" "${APP_DIR}"
else
    echo "==> Ad-hoc signing (Bluetooth permission will be asked again after each rebuild)"
    codesign --force --deep --entitlements "${ENTITLEMENTS}" --sign - "${APP_DIR}"
fi
codesign --verify --deep --strict "${APP_DIR}"
echo "==> Architectures: $(lipo -archs "${APP_DIR}/Contents/MacOS/${EXECUTABLE}")"

echo "==> Done: ${APP_DIR}"
echo "    Launch with: open \"${APP_DIR}\""
