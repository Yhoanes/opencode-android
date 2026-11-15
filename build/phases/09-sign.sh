#!/usr/bin/env bash
#
# 09-sign.sh - zipalign and sign the APK with v1/v2/v3 signatures.
#

log "[10/11] zipalign + apksigner sign"
zipalign -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$OUT_APK" \
    "$BUILD_DIR/aligned.apk"

if [ "${OC_CREATE_APK_ALIAS:-0}" -eq 1 ] && [ -n "${OUT_APK_ALIAS:-}" ] && [ "$OUT_APK_ALIAS" != "$OUT_APK" ]; then
    cp -f "$OUT_APK" "$OUT_APK_ALIAS"
fi
