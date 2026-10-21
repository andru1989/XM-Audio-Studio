#!/bin/bash
# Packages the built app into a drag-to-Applications disk image.
# Usage: Scripts/build_xcode.sh && Scripts/make_dmg.sh   (or build_app.sh for a build without widgets)
# The image is signed with the same identity as the app when one is available. It is not
# notarized: that needs a Developer ID certificate (see docs/distribution/INSTALL.md).
set -euo pipefail
cd "$(dirname "$0")/.."

# Prefer the Xcode build (it embeds the WidgetKit extension); fall back to the SwiftPM one.
if [ -d "build/XM Audio Studio.app" ]; then
    APP="build/XM Audio Studio.app"
else
    APP=".build/XM Audio Studio.app"
fi
[ -d "${APP}" ] || { echo "Build the app first: Scripts/build_xcode.sh (with widgets) or Scripts/build_app.sh"; exit 1; }
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
