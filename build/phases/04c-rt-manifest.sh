#!/usr/bin/env bash
#
# 04c-rt-manifest.sh - generate assets/runtime/manifest.json (SHA-256 of every
# bundled runtime file) for CoreInstaller / ManifestVerifier fail-closed checks.
#

log "[3/11] generate assets/runtime/manifest.json"
python3 - "$RT" <<'PY'
import hashlib, json, os, sys, time

rt = os.path.abspath(sys.argv[1])

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

files = {}
for dp, _, fns in os.walk(rt):
    for fn in sorted(fns):
        full = os.path.join(dp, fn)
        rel = os.path.relpath(full, rt).replace(os.sep, "/")
        if rel == "manifest.json":
            continue
        files[rel] = sha(full)

out = os.path.join(rt, "manifest.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump({
        "schema": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "files": files
    }, f, indent=2, sort_keys=True)
    f.write("\n")

print("[runtime.manifest] files=%d -> %s" % (len(files), out))
PY
