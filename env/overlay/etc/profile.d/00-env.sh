# 00-env.sh -- core environment of the OpenCode Bionic prefix.
export HOME="${HOME:-$(dirname "$PREFIX")}"
export OPENCODE_CONFIG_DIR="${OPENCODE_CONFIG_DIR:-$HOME/.config/opencode}"
export ANTIGRAVITY_APP_DATA_DIR="${ANTIGRAVITY_APP_DATA_DIR:-$HOME/.local/share/opencode}"
export TMPDIR="$PREFIX/tmp"
export PATH="$PREFIX/bin:$HOME/runtime/bin:$HOME/.local/bin:/system/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
if [ -f "$PREFIX/lib/libagy-go-dns.so" ] && [ -f "$PREFIX/lib/libtermux-exec.so" ]; then
    export LD_PRELOAD="$PREFIX/lib/libagy-go-dns.so:$PREFIX/lib/libtermux-exec.so"
elif [ -f "$PREFIX/lib/libtermux-exec.so" ]; then
    export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
elif [ -f "$PREFIX/lib/libagy-go-dns.so" ]; then
    export LD_PRELOAD="$PREFIX/lib/libagy-go-dns.so"
fi
export CHROME_PATH="$HOME/runtime/bin/chrome"
export PUPPETEER_EXECUTABLE_PATH="$HOME/runtime/bin/chrome"
export TERMUX__PREFIX="$PREFIX"
export TERMUX__ROOTFS="/"
export TERMUX_APP__DATA_DIR="$(dirname "$(dirname "$PREFIX")")"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export GIT_CONFIG_NOSYSTEM="1"
export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"
export LANG="${LANG:-C.UTF-8}"
export TZ="${TZ:-UTC}"
export JDK_JAVA_OPTIONS="-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp"
export CFLAGS="-D__ANDROID_API__=28 -march=armv8-a -moutline-atomics ${CFLAGS:-}"
export CXXFLAGS="-D__ANDROID_API__=28 -march=armv8-a -moutline-atomics ${CXXFLAGS:-}"
mkdir -p "$TMPDIR" "$HOME" "$OPENCODE_CONFIG_DIR" "$HOME/.local/bin" 2>/dev/null || true
