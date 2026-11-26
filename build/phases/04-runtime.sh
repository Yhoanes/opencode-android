#!/usr/bin/env bash
#
# 04-runtime.sh - assemble assets/runtime/{bin,certs,seed} (Pure Bionic, zero glibc).
#

log "[2/11] assemble assets/runtime/{bin,certs,seed}"
mkdir -p "$RT/bin" "$RT/certs" "$RT/seed"

# Pure Bionic OpenCode v2 engine
cp -f "$STAGED_CORE" "$RT/bin/$CORE_NAME"

# Native Android bridge tools (rish, rish_shizuku.dex, xdg-open, open, chrome shims)
for tool in "$TOOLS_SRC_DIR"/*; do
    [ -e "$tool" ] || continue
    cp -f "$tool" "$RT/bin/"
done

# TLS root certificates + default Android OpenCode workspace instructions
cp -f "$CERTS_DIR"/* "$RT/certs/"
cp -f "$SEED_DIR"/* "$RT/seed/"
chmod 755 "$RT/bin"/* 2>/dev/null || true
