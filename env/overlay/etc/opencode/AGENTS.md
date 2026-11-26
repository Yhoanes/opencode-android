# OpenCode Android Agent Rules & Environment Guidelines

> [!NOTE]
> This rule is automatically loaded into every session in OpenCode for Android (`v2.4.2-r1`).
> It defines the native Android ARM64 operating environment, execution rules, package management, and elevated permissions.

---

## 1. Platform & Environment Identity

- **Platform:** Native Android (Linux kernel 5.10–6.6+, ARM64 / `aarch64` architecture, pure Bionic `libc.so` — zero PRoot, zero glibc).
- **Application Context:** Runs directly inside the `com.oc.ide` (`OpenCode`) or `com.oc.dev` (`OpenCode Dev`) package sandbox.
- **Key Filesystem Paths:**
  - Application Root (`$HOME`): `/data/user/0/com.oc.ide/files`
  - Linux Userspace Prefix (`$PREFIX`): `/data/user/0/com.oc.ide/files/usr`
  - User Binary Path: `/data/user/0/com.oc.ide/files/usr/bin` (always ensure this is in `$PATH`)
  - Runtime Internals: `/data/user/0/com.oc.ide/files/runtime/bin`
  - External / Shared Storage: `/storage/emulated/0` (Default workspace: `/storage/emulated/0/Documents/OpenCode`)
  - OpenCode Config & MCP State: `/data/user/0/com.oc.ide/files/.config/opencode/` (`opencode.json`, `skills/`, `rules/`)
  - OpenCode Data & Sessions: `/data/user/0/com.oc.ide/files/.local/share/opencode/`

---

## 2. Terminal & Shell Execution Rules

- **No Bubblewrap / Desktop Sandboxing / Docker:**
  Desktop Linux bubblewrap (`bwrap`) and unprivileged user namespaces are **not supported** by the Android kernel and SELinux. Never invoke `bwrap` or Docker. Execute commands directly in the native Bionic shell.
- **Shared Storage (`noexec`) Awareness:**
  `/sdcard` (`/storage/emulated/0`) is mounted with `noexec` via FUSE. Source code and project files live cleanly in `/sdcard/...`, while compiled native binaries, Rust `cargo` target directories (`CARGO_TARGET_DIR="$PREFIX/tmp/cargo-target"`), or temporary scripts that require `+x` execution bits should run via `bash script.sh` or execute from `$PREFIX/tmp`.
- **Path Quoting & Environment Variables:**
  Always quote paths that may contain spaces, and ensure core environment variables are set when spawning clean subshells:
  ```bash
  export PREFIX="${PREFIX:-/data/user/0/com.oc.ide/files/usr}"
  export HOME="${HOME:-/data/user/0/com.oc.ide/files}"
  export PATH="$PREFIX/bin:$HOME/runtime/bin:$PATH"
  export TMPDIR="$PREFIX/tmp"
  export GIT_CONFIG_NOSYSTEM=1
  ```

---

## 3. Native Package Management (`pkg`)

OpenCode for Android includes an ultra-fast native Rust multicall package manager (`pkg` / `oc-box` / `agy-box`):

- **Correct Syntax:**
  ```bash
  # Update package indexes:
  pkg update

  # Install packages (list package names directly):
  pkg install git python nodejs clang rust ripgrep jq

  # Search and query:
  pkg search <pattern>
  pkg installed
  pkg info <package>
  pkg remove <package>
  ```
- **Post-Install Shebang & Prefix Fixes:**
  After installing global Python/Node scripts (`pip`, `npm`), fix hardcoded desktop shebangs using:
  ```bash
  oc-fix-paths
  ```

---

## 4. Elevated Operations via Shizuku / Wireless ADB (`rish`)

OpenCode has native access to Android's elevated shell (`uid=2000(shell)`) via `rish` (Shizuku or `agy-adb-bridge` local wireless ADB):
- **How to Execute:**
  ```bash
  rish -c "<command>"
  ```
- **Common Uses:**
  - Install/inspect APKs: `rish -c "pm install -r /sdcard/.../app.apk"`, `rish -c "pm list packages -3"`
  - Launch & debug apps: `rish -c "am start -n com.example/.MainActivity"`, `rish -c "logcat -d -t 150"`
  - Device diagnostics: `rish -c "getprop ro.build.version.release"`, `rish -c "dumpsys gfxinfo <pkg>"`

---

## 5. Browser, URL Opening & Headless Automation on Android

- **Opening URLs, Local Servers & OAuth Links (`xdg-open`):**
  To open any web page, local dev server (`http://localhost:3000`), or OAuth login URL in the user's Android browser / Chrome Custom Tab, run:
  ```bash
  xdg-open "https://example.com"
  ```
- **Headless Web Inspection & DevTools:**
  - For static pages/APIs, use `curl -fsSL` or Python.
  - For full JS-rendered DOM inspection, screenshots, or CDP automation, install native ARM64 Chromium (`pkg update && pkg install nodejs chromium`) and use `$CHROME_PATH` (`runtime/bin/chrome`, which automatically injects `--no-sandbox --disable-gpu --disable-dev-shm-usage`) or install the **Chrome DevTools** plugin from **Settings → Extensions → Plugins**.

---

## 6. Local Servers, MCP Hot-Reload & Background Daemon

- **Port Binding:** Bind local dev servers (Vite, Next.js, Express, FastAPI) to `127.0.0.1` or `localhost` on ports like `3000`, `5173`, `8000`, `8080`. Avoid ports `45167` and `45168` (reserved for OpenCode core) and `45157`/`45158`.
- **Hot-Reloading OpenCode Config & MCP Servers:**
  After editing `~/.config/opencode/opencode.json` or adding skills under `~/.config/opencode/skills/`, apply changes live without dropping active sessions:
  ```bash
  opencode reload
  ```
- **Android Notifications & Background Daemon (`oc-notify` / `oc-daemon`):**
  ```bash
  # Post native Android status notification:
  oc-notify post -t "Build Finished" -m "APK compiled and signed cleanly"

  # Schedule autonomous background task:
  oc-daemon add --name "Health Check" --interval 30m --command "df -h > ~/.config/opencode/disk.log"
  oc-daemon list
  ```
