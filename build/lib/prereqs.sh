#!/usr/bin/env bash
#
# prereqs.sh - host tool + input existence checks and build scratch cleanup.
#

need() { command -v "$1" >/dev/null 2>&1 || die "missing host tool: $1"; }

check_tools() {
    for t in aapt javac java zipalign apksigner zip unzip python3 sha256sum gzip file mktemp; do
        need "$t"
    done

    [ -f "$CORE_SRC" ] || [ -f "${CORE_SRC}.tar.gz" ] || die "OpenCode core binary not found: $CORE_SRC (or ${CORE_SRC}.tar.gz)"
    [ -f "$CERTS_DIR/ca-certificates.crt" ] || die "CA bundle not found: $CERTS_DIR/ca-certificates.crt"
    [ -f "$SEED_DIR/AGENTS.md" ]            || die "Seed AGENTS.md not found: $SEED_DIR/AGENTS.md"
    [ -f "$TOOLS_SRC_DIR/rish" ]            || die "Shizuku rish script not found: $TOOLS_SRC_DIR/rish"
    [ -f "$ANDROID_JAR" ]                   || die "android.jar not found: $ANDROID_JAR"
    [ -f "$R8_JAR" ]                        || die "r8.jar not found: $R8_JAR"
    if [ ! -f "$KEYSTORE" ] && [ "$KEY_ALIAS" = "androiddebugkey" ]; then
        mkdir -p "$(dirname "$KEYSTORE")"
        keytool -genkeypair \
            -keystore "$KEYSTORE" \
            -storetype PKCS12 \
            -alias "$KEY_ALIAS" \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -storepass "$STORE_PASS" \
            -keypass "$KEY_PASS" \
            -dname "CN=Local Dev Build, O=OpenCode" >/dev/null 2>&1 || true
    fi
    [ -f "$KEYSTORE" ]                      || die "keystore not found: $KEYSTORE"
    [ -f "$MANIFEST_XML" ]                  || die "AndroidManifest.xml not found: $MANIFEST_XML"
    [ -d "$WEB_DIR" ]                       || die "web/ directory not found: $WEB_DIR"
    [ -f "$GEN_ANDROID_CONFIG" ]            || die "gen_android_config.py not found: $GEN_ANDROID_CONFIG"
}

clean_build() {
    log "clean build scratch directories"
    rm -rf "$BUILD_DIR/staging" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
           "$BUILD_DIR/apk" "$BUILD_DIR/res" "$BUILD_DIR/AndroidManifest.xml" \
           "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/unaligned.apk.tmp" \
           "$BUILD_DIR/aligned.apk" "$BUILD_DIR/bootstrap.tar.gz.hold"
    mkdir -p "$BUILD_DIR/staging" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
             "$STAGE_ASSETS" "$DIST_DIR"
}
