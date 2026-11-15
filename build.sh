#!/usr/bin/env bash
#
# build.sh - OpenCode for Android (v2.3.0-r4) Pure Bionic APK build orchestrator.
#
# Pipeline:
#   00-dev          -> generate BuildConfig.java + res/values/gen.xml + manifest version sync
#   01-stage-core   -> stage & verify Pure Bionic ARM64 core/opencode (no glibc, no binary patches)
#   04-runtime      -> assemble assets/runtime/{bin,certs,seed}
#   04c-rt-manifest -> compute SHA-256 integrity manifest (assets/runtime/manifest.json)
#   05-web-bus      -> stage assets/web (__oc_android_boot.js, MCP catalog, icon)
#   05b-env         -> stage assets/env/{pkg,overlay,bootstrap} (oc-box, rg, Bionic userspace)
#   06-rjava        -> aapt R.java generation (minSdk 28 / targetSdk 28)
#   07-javac        -> compile 116 Java sources + generated sources (UTF-8)
#   07b-d8          -> dex bytecode compilation via R8/D8
#   08-package      -> package resources + assets + classes.dex + stored bootstrap.tar.gz
#   09-sign         -> zipalign (4-byte page-safe) + apksigner v1/v2/v3 signing
#   09b-verify      -> apksigner verify + ELF Bionic & gzip integrity verification
#
# Flags:
#   --dev           -> build side-by-side Dev edition (com.oc.dev, OpenCode Dev, port 45168)
#   --help          -> print usage
#
set -euo pipefail

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# Ensure on-device Bionic userspace tools ($PREFIX/bin) are in PATH when building inside Android.
if [ -d "/data/user/0/com.agy/files/usr/bin" ]; then
    export PATH="/data/user/0/com.agy/files/usr/bin:$PATH"
elif [ -d "/data/user/0/com.oc.ide/files/usr/bin" ]; then
    export PATH="/data/user/0/com.oc.ide/files/usr/bin:$PATH"
fi

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT

. "$PROJECT/build/lib/log.sh"

. "$PROJECT/build/lib/flags.sh"
parse_args "$@"

. "$PROJECT/build/lib/paths.sh"
setup_paths

. "$PROJECT/build/lib/prereqs.sh"
check_tools

clean_build

shopt -s nullglob
for p in "$BUILD_LIB_DIR"/../phases/*.sh; do
    . "$p"
done
shopt -u nullglob
