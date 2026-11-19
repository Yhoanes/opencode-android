#!/usr/bin/env bash
#
# 05b-env.sh - stage Bionic Linux userspace toolchain (assets/env/{pkg,overlay,bootstrap}).
#

log "[5/11] stage assets/env (Bionic Linux toolchain + oc-box + rg)"
ENV_ASSETS="$STAGE_ASSETS/env"
export ENV_ASSETS

ENV_ROOT=""
if [ -d "$ENV_DIST/pkg" ] || [ -d "$ENV_DIST/overlay" ] \
        || [ -f "$ENV_DIST/bootstrap/bootstrap.tar.gz" ] \
        || [ -f "$ENV_DIST/bootstrap/bootstrap.tar" ]; then
    ENV_ROOT="$ENV_DIST"
    log "  source: env/dist/env"
elif [ -d "$ENV_DIR/pkg" ] || [ -d "$ENV_DIR/overlay" ] \
        || [ -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" ] \
        || [ -f "$ENV_DIR/bootstrap/bootstrap.tar" ]; then
    ENV_ROOT="$ENV_DIR"
    log "  source: env/{pkg,overlay,bootstrap}"
else
    die "env/ Bionic userspace directory missing: $ENV_DIR"
fi

[ -d "$ENV_ROOT/pkg" ]                    || die "env pkg missing: $ENV_ROOT/pkg"
[ -d "$ENV_ROOT/overlay" ]                || die "env overlay missing: $ENV_ROOT/overlay"
[ -f "$ENV_ROOT/overlay/bin/oc-box" ]     || die "compiled oc-box missing: $ENV_ROOT/overlay/bin/oc-box"
[ -f "$ENV_ROOT/overlay/bin/rg" ]         || die "ripgrep (rg) missing: $ENV_ROOT/overlay/bin/rg"
[ -f "$ENV_ROOT/overlay/lib/libpcre2-8.so" ] || die "libpcre2-8.so missing: $ENV_ROOT/overlay/lib/libpcre2-8.so"

ENV_TGZ="$ENV_ROOT/bootstrap/bootstrap.tar.gz"
ENV_TAR="$ENV_ROOT/bootstrap/bootstrap.tar"
if [ -f "$ENV_TGZ" ]; then
    file -b "$ENV_TGZ" | grep -q '^gzip compressed' \
        || die "env bootstrap is not gzip despite .tar.gz name: $ENV_TGZ"
    BOOTSTRAP_SRC="$ENV_TGZ"
    BOOTSTRAP_GZ=1
elif [ -f "$ENV_TAR" ] && file -b "$ENV_TAR" | grep -q 'tar archive'; then
    BOOTSTRAP_SRC="$ENV_TAR"
    BOOTSTRAP_GZ=0
else
    die "env bootstrap missing: $ENV_ROOT/bootstrap/bootstrap.{tar.gz,tar}"
fi

rm -rf "$ENV_ASSETS"
mkdir -p "$ENV_ASSETS/bootstrap" "$ENV_ASSETS/pkg" "$ENV_ASSETS/overlay"
cp -r "$ENV_ROOT/pkg/."     "$ENV_ASSETS/pkg/"
cp -r "$ENV_ROOT/overlay/." "$ENV_ASSETS/overlay/"

BOOTSTRAP_STAGED="$ENV_ASSETS/bootstrap/bootstrap.tar.gz"
if [ "$BOOTSTRAP_GZ" -eq 1 ]; then
    cp -f "$BOOTSTRAP_SRC" "$BOOTSTRAP_STAGED"
else
    log "  source bootstrap is plain tar; gzip -9 -> bootstrap.tar.gz"
    gzip -9 -c "$BOOTSTRAP_SRC" > "$BOOTSTRAP_STAGED"
fi

# When building --dev (com.oc.dev, also 10 ASCII bytes), rewrite b"com.oc.ide" -> b"com.oc.dev"
# in the staged bootstrap and overlay ELFs so side-by-side dev builds run zero-PRoot natively too.
if [ "$DEV_MODE" -eq 1 ] && [ "${#PKG}" -eq "${#PKG_DEV}" ]; then
    log "  --dev: rewriting 10-byte prefix $PKG -> $PKG_DEV in staged env"
    python3 - "$ENV_ASSETS" "$PKG" "$PKG_DEV" <<'PY'
import gzip, io, os, sys, tarfile

env_assets, src_pkg, dst_pkg = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
assert len(src_pkg) == len(dst_pkg)

# 1. Rewrite overlay files
for dp, _, fns in os.walk(os.path.join(env_assets, "overlay")):
    for fn in fns:
        p = os.path.join(dp, fn)
        with open(p, "rb") as f:
            data = f.read()
        if src_pkg in data:
            with open(p, "wb") as f:
                f.write(data.replace(src_pkg, dst_pkg))

# 2. Rewrite bootstrap.tar.gz members & symlink targets
tgz = os.path.join(env_assets, "bootstrap", "bootstrap.tar.gz")
tmp_tgz = tgz + ".devtmp"
with tarfile.open(tgz, "r:gz") as tin, gzip.GzipFile(tmp_tgz, "wb", compresslevel=6, mtime=0) as gz:
    with tarfile.open(mode="w", fileobj=gz) as tout:
        for m in tin.getmembers():
            if m.issym() or m.islnk():
                if src_pkg.decode() in m.linkname:
                    m.linkname = m.linkname.replace(src_pkg.decode(), dst_pkg.decode())
                tout.addfile(m)
            elif m.isfile():
                raw = tin.extractfile(m).read()
                if src_pkg in raw:
                    raw = raw.replace(src_pkg, dst_pkg)
                m.size = len(raw)
                tout.addfile(m, io.BytesIO(raw))
            else:
                tout.addfile(m)
os.replace(tmp_tgz, tgz)
PY
else
    for _tree in pkg overlay; do
        diff -r "$ENV_ROOT/$_tree" "$ENV_ASSETS/$_tree" >/dev/null 2>&1 \
            || die "invariant failed: assets/env/$_tree != $ENV_ROOT/$_tree"
    done
fi

file -b "$BOOTSTRAP_STAGED" | grep -q '^gzip compressed' \
    || die "staged bootstrap is not gzip: $BOOTSTRAP_STAGED"
gzip -t "$BOOTSTRAP_STAGED" || die "staged bootstrap gzip integrity failed: $BOOTSTRAP_STAGED"
log "  staged assets/env ready (bootstrap.tar.gz gzip ok)"
