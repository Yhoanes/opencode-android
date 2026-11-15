#!/usr/bin/env bash
#
# 01-stage-core.sh - stage & verify Pure Bionic ARM64 OpenCode binary.
#

log "[1/11] stage Pure Bionic ARM64 OpenCode core"
if [ -f "$CORE_SRC" ]; then
    cp -f "$CORE_SRC" "$STAGED_CORE"
elif [ -f "${CORE_SRC}.tar.gz" ]; then
    log "  unpacking compressed core archive: ${CORE_SRC}.tar.gz"
    python3 - "${CORE_SRC}.tar.gz" "$STAGED_CORE" <<'PY'
import os, shutil, sys, tarfile
archive_path, out_path = sys.argv[1], sys.argv[2]
with tarfile.open(archive_path, "r:gz") as tar:
    member = None
    for m in tar.getmembers():
        if m.isfile() and os.path.basename(m.name) == "opencode":
            member = m
            break
    if member is None:
        sys.exit("opencode binary not found inside " + archive_path)
    src = tar.extractfile(member)
    with open(out_path, "wb") as dst:
        shutil.copyfileobj(src, dst, 1 << 20)
PY
else
    die "OpenCode core binary or archive not found: $CORE_SRC"
fi

CORE_DESC="$(file -b "$STAGED_CORE")"
log "  staged core: $CORE_DESC"
printf '%s' "$CORE_DESC" | grep -q 'ELF 64-bit LSB' \
    || die "staged core is not a 64-bit ELF binary: $CORE_DESC"
printf '%s' "$CORE_DESC" | grep -q 'ARM aarch64' \
    || die "staged core is not ARM64 (aarch64): $CORE_DESC"
printf '%s' "$CORE_DESC" | grep -q '/system/bin/linker64' \
    || die "staged core is not pure Android Bionic (/system/bin/linker64): $CORE_DESC"
