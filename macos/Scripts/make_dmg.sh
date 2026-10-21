#!/bin/bash
# Packages the built app into a drag-to-Applications disk image.
# Usage: Scripts/build_app.sh && Scripts/make_dmg.sh
# The image is signed with the same identity as the app when one is available. It is not
# notarized: that needs a Developer ID certificate (see docs/distribution/INSTALL.md).
set -euo pipefail
cd "$(dirname "$0")/.."

APP=".build/XM Audio Studio.app"
[ -d "${APP}" ] || { echo "Build the app first: Scripts/build_app.sh"; exit 1; }
VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${APP}/Contents/Info.plist")"
OUT_DIR="dist"
DMG="${OUT_DIR}/XM Audio Studio ${VERSION}.dmg"
STAGE="$(mktemp -d -t xmas-dmg)"
trap 'rm -rf "${STAGE}"' EXIT

mkdir -p "${OUT_DIR}"
cp -R "${APP}" "${STAGE}/"
ln -s /Applications "${STAGE}/Applications"
rm -f "${DMG}"
hdiutil create -volname "XM Audio Studio" -srcfolder "${STAGE}" -fs HFS+ -format UDZO -ov "${DMG}" >/dev/null

identity="$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*"\(Apple Development: [^"]*\)".*/\1/p' | head -1)"
if [ -n "${identity}" ]; then
    codesign --force --sign "${identity}" "${DMG}"
fi
hdiutil verify "${DMG}" >/dev/null
echo "==> ${DMG} ($(du -h "${DMG}" | cut -f1))"
