#!/usr/bin/env bash
#
# 00-dev.sh - configure release vs dev edition and generate BuildConfig.java + gen.xml.
#

log "[0/11] configure edition and generated Android config"
if [ "$DEV_MODE" -eq 1 ]; then
    OUT_APK="$DIST_DIR/OpenCode-Dev-v${VERSION_NAME}.apk"
    OUT_APK_ALIAS="$DIST_DIR/opencode-dev.apk"
    log "  Edition: DEV (package=$PKG_DEV, label='$APP_NAME_DEV', port=$PORT_DEV)"
else
    OUT_APK="$DIST_DIR/OpenCode-v${VERSION_NAME}.apk"
    OUT_APK_ALIAS="$DIST_DIR/opencode.apk"
    log "  Edition: RELEASE (package=$PKG, label='$APP_NAME', port=$PORT)"
fi
export OUT_APK OUT_APK_ALIAS

[ -d "$RES_DIR" ] || die "res dir not found: $RES_DIR"

mkdir -p "$GEN_DIR" "$BUILD_DIR/res"
DEV_MODE="$DEV_MODE" python3 "$GEN_ANDROID_CONFIG" "$CONFIG_APP_ENV" "$GEN_DIR" \
    || die "gen_android_config.py failed"

PKG_REL_DIR="$(printf '%s' "$PKG" | tr '.' '/')"
[ -f "$GEN_DIR/$PKG_REL_DIR/BuildConfig.java" ] || die "BuildConfig.java not generated at $GEN_DIR/$PKG_REL_DIR/BuildConfig.java"
[ -f "$GEN_DIR/res/values/gen.xml" ]            || die "gen.xml not generated"

# Copy resource tree (FUSE-safe cp -r without ownership preservation) + generated gen.xml
cp -r "$RES_DIR/." "$BUILD_DIR/res/"
mkdir -p "$BUILD_DIR/res/values"
cp -f "$GEN_DIR/res/values/gen.xml" "$BUILD_DIR/res/values/gen.xml"

STRINGS_COPY="$BUILD_DIR/res/values/strings.xml"
if [ -f "$STRINGS_COPY" ]; then
    sed -i -E '/<string name="(app_name|oauth_scheme)">/d; /<integer name="https_server_port">/d' "$STRINGS_COPY"
fi

TARGET_RES="$BUILD_DIR/res"
TARGET_MANIFEST="$BUILD_DIR/AndroidManifest.xml"
cp -f "$MANIFEST_XML" "$TARGET_MANIFEST"

# Always synchronize versionCode & versionName from config/app.env
sed -i -E "s|android:versionCode=\"[^\"]*\"|android:versionCode=\"$VERSION_CODE\"|g" "$TARGET_MANIFEST"
sed -i -E "s|android:versionName=\"[^\"]*\"|android:versionName=\"$VERSION_NAME\"|g" "$TARGET_MANIFEST"

if [ "$DEV_MODE" -eq 1 ]; then
    GEN_XML="$BUILD_DIR/res/values/gen.xml"
    sed -i "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">$APP_NAME_DEV</string>|" "$GEN_XML"
    sed -i "s|<integer name=\"https_server_port\">.*</integer>|<integer name=\"https_server_port\">$PORT_DEV</integer>|" "$GEN_XML"

    sed -i "s|package=\"$PKG\"|package=\"$PKG_DEV\"|g" "$TARGET_MANIFEST"
    sed -i "s|android:authorities=\"$PKG.updateprovider\"|android:authorities=\"$PKG_DEV.updateprovider\"|g" "$TARGET_MANIFEST"
    sed -i "s|<action android:name=\"$PKG.ACTION_TORCH\"|<action android:name=\"$PKG_DEV.ACTION_TORCH\"|g" "$TARGET_MANIFEST"
    sed -i "s|<application|<application android:debuggable=\"true\"|g" "$TARGET_MANIFEST"
fi
export TARGET_RES TARGET_MANIFEST
