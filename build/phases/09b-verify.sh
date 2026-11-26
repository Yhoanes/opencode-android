#!/usr/bin/env bash
#
# 09b-verify.sh - verify APK cryptographic signature and packaged Pure Bionic payload.
#

log "[11/11] apksigner verify + payload integrity check"
apksigner verify --verbose "$OUT_APK"

python3 - "$OUT_APK" <<'PY'
import gzip, hashlib, json, sys, zipfile

apk = sys.argv[1]
required = [
    "AndroidManifest.xml",
    "classes.dex",
    "resources.arsc",
    "assets/runtime/bin/opencode",
    "assets/runtime/bin/opencode-pty",
    "assets/runtime/bin/rish",
    "assets/runtime/bin/rish_shizuku.dex",
    "assets/runtime/bin/xdg-open",
    "assets/runtime/certs/ca-certificates.crt",
    "assets/runtime/seed/AGENTS.md",
    "assets/runtime/manifest.json",
    "assets/web/__oc_android_boot.js",
    "assets/web/__oc_plugins_catalog.json",
    "assets/web/opencode-icon.png",
    "assets/env/bootstrap/bootstrap.tar.gz",
    "assets/env/overlay/bin/oc-box",
    "assets/env/overlay/bin/agy-box",
    "assets/env/overlay/bin/rg",
    "assets/env/overlay/lib/libpcre2-8.so",
]

with zipfile.ZipFile(apk, "r") as z:
    names = set(z.namelist())
    missing = [r for r in required if r not in names]
    if missing:
        sys.exit("[verify] FAIL missing required APK entries: %r" % missing)

    forbidden = [n for n in names if "liblanguage_server" in n or "libloader.so" in n or n == "assets/env/bootstrap/bootstrap.tar"]
    if forbidden:
        sys.exit("[verify] FAIL unexpected legacy/mangled entries in APK: %r" % forbidden)

    # 1. Verify bootstrap.tar.gz gzip magic & integrity
    bdata = z.read("assets/env/bootstrap/bootstrap.tar.gz")
    if bdata[:2] != b"\x1f\x8b":
        sys.exit("[verify] FAIL bootstrap.tar.gz is not gzip (magic=%r)" % bdata[:2])
    raw_tar = gzip.decompress(bdata)
    print("[verify] bootstrap.tar.gz ok (%d bytes compressed -> %d bytes tar)" % (len(bdata), len(raw_tar)))

    # 2. Stream-hash assets/runtime/bin/opencode and check ELF64 ARM64 header + manifest.json match
    manifest = json.loads(z.read("assets/runtime/manifest.json").decode("utf-8"))
    expected_sha = manifest["files"]["bin/opencode"]
    h = hashlib.sha256()
    head = b""
    total = 0
    with z.open("assets/runtime/bin/opencode") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            if len(head) < 64:
                head += chunk[:64 - len(head)]
            h.update(chunk)
            total += len(chunk)
    actual_sha = h.hexdigest()
    if head[:4] != b"\x7fELF" or head[4] != 2 or head[18] != 0xb7:
        sys.exit("[verify] FAIL bin/opencode is not ELF64 AArch64")
    if actual_sha != expected_sha:
        sys.exit("[verify] FAIL bin/opencode sha256 mismatch: %s != %s" % (actual_sha, expected_sha))
    print("[verify] Pure Bionic ARM64 bin/opencode ok (%d bytes, sha256=%s...)" % (total, actual_sha[:16]))

    # 3. Verify bundled native Bionic ARM64 opencode-pty Protocol v9 daemon
    pty_bytes = z.read("assets/runtime/bin/opencode-pty")
    pty_sha = hashlib.sha256(pty_bytes).hexdigest()
    if pty_bytes[:4] != b"\x7fELF" or pty_bytes[4] != 2 or pty_bytes[18] != 0xb7:
        sys.exit("[verify] FAIL bin/opencode-pty is not ELF64 AArch64")
    if pty_sha != manifest["files"].get("bin/opencode-pty"):
        sys.exit("[verify] FAIL bin/opencode-pty sha256 mismatch in manifest.json")
    print("[verify] Pure Bionic ARM64 bin/opencode-pty ok (%d bytes, sha256=%s...)" % (len(pty_bytes), pty_sha[:16]))
PY

# Clean intermediate build scratch while preserving build/{lib,phases,tools}
rm -rf "$BUILD_DIR/staging" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
       "$BUILD_DIR/apk" "$BUILD_DIR/res" "$BUILD_DIR/AndroidManifest.xml" \
       "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk" "$BUILD_DIR/bootstrap.tar.gz.hold"

log "BUILD OK -> $OUT_APK"
ls -lh "$OUT_APK"
if [ -n "${OUT_APK_ALIAS:-}" ] && [ -f "$OUT_APK_ALIAS" ] && [ "$OUT_APK_ALIAS" != "$OUT_APK" ]; then
    ls -lh "$OUT_APK_ALIAS"
fi
