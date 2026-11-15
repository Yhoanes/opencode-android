#!/usr/bin/env bash
#
# paths.sh - loads config/*.env and resolves all project, toolchain, and staging paths.
#

load_app_env() {
    local file="$1" line key value
    [ -f "$file" ] || { printf '\n[FATAL] env file not found: %s\n' "$file" >&2; exit 1; }
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|\#*) continue ;;
            *=*) ;;
            *) continue ;;
        esac
        key="${line%%=*}"
        value="${line#*=}"
        key="$(printf '%s' "$key" | tr -d '[:space:]')"
        [ -n "$key" ] || continue
        case "$value" in
            \"*\") value="${value#\"}"; value="${value%\"}" ;;
            \'*\') value="${value#\'}"; value="${value%\'}" ;;
        esac
        export "$key=$value"
    done < "$file"
}

resolve_proj_path() {
    local val="$1"
    case "$val" in
        /*) printf '%s' "$val" ;;
        *)  printf '%s/%s' "$PROJECT" "$val" ;;
    esac
}

setup_paths() {
    local cfg="$PROJECT/config"
    [ -d "$cfg" ] || { printf '\n[FATAL] config dir not found: %s\n' "$cfg" >&2; exit 1; }

    set -a
    # shellcheck disable=SC1090
    . "$cfg/paths.env"
    . "$cfg/tools.env"
    set +a
    load_app_env "$cfg/app.env"

    CONFIG_DIR="$cfg"
    CONFIG_APP_ENV="$cfg/app.env"

    # Project directories (resolved relative to $PROJECT when relative in paths.env)
    CORE_DIR="$(resolve_proj_path "${CORE_DIR:-core}")"
    CORE_SRC="$(resolve_proj_path "${CORE_BIN:-core/opencode}")"
    CERTS_DIR="$(resolve_proj_path "${CORE_CERTS_DIR:-core/certs}")"
    SEED_DIR="$(resolve_proj_path "${CORE_SEED_DIR:-core/seed}")"
    TOOLS_SRC_DIR="$(resolve_proj_path "${CORE_TOOLS_DIR:-core/tools}")"

    WEB_DIR="$(resolve_proj_path "${WEB_DIR:-web}")"
    BUS_DIR="$PROJECT/bus"

    ENV_DIR="$(resolve_proj_path "${ENV_DIR:-env}")"
    ENV_DIST="${ENV_DIST:-$ENV_DIR/dist/env}"

    APP_DIR="$PROJECT/app"
    APP_SRC_MAIN="$(resolve_proj_path "${APP_SRC_DIR:-app/src/main}")"
    TOOLS_DIR="$(resolve_proj_path "${TOOLS_DIR:-tools}")"
    BUILD_DIR="$PROJECT/build"
    DIST_DIR="$(resolve_proj_path "${DIST_DIR:-dist}")"

    # Host toolchain jars & signing keystore
    ANDROID_JAR="${ANDROID_JAR:-$TOOLS_DIR/android.jar}"
    R8_JAR="${R8_JAR:-$TOOLS_DIR/r8.jar}"
    KEYSTORE="${KEYSTORE:-$TOOLS_DIR/debug.keystore}"

    # Build script directories (never deleted by clean_build)
    BUILD_LIB_DIR="$BUILD_DIR/lib"
    PHASES_DIR="$BUILD_DIR/phases"
    BUILD_TOOLS_DIR="$BUILD_DIR/tools"
    GEN_ANDROID_CONFIG="$BUILD_TOOLS_DIR/gen_android_config.py"

    # App source layout
    MANIFEST_XML="$APP_SRC_MAIN/AndroidManifest.xml"
    RES_DIR="$APP_SRC_MAIN/res"
    JAVA_DIR="$APP_SRC_MAIN/java"

    # Pure Bionic runtime constants (zero glibc, zero binary patches)
    CORE_NAME="opencode"
    MIN_API="${MIN_API:-28}"
    TARGET_SDK="${TARGET_SDK:-28}"

    # Build scratch / staging paths
    STAGE_APK="$BUILD_DIR/apk"
    STAGE_ASSETS="$STAGE_APK/assets"
    RT="$STAGE_ASSETS/runtime"
    STAGED_CORE="$BUILD_DIR/staging/opencode"
    GEN_DIR="$BUILD_DIR/gen"
    OBJ_DIR="$BUILD_DIR/obj"
    DEX_DIR="$BUILD_DIR/dex"

    # Finalized in 00-dev.sh depending on --dev
    TARGET_MANIFEST="$MANIFEST_XML"
    TARGET_RES="$RES_DIR"
    OUT_APK="$DIST_DIR/OpenCode-v${VERSION_NAME}.apk"
    OUT_APK_ALIAS="$DIST_DIR/opencode.apk"

    export CONFIG_DIR CONFIG_APP_ENV \
        CORE_DIR CORE_SRC CERTS_DIR SEED_DIR TOOLS_SRC_DIR \
        APP_DIR APP_SRC_MAIN WEB_DIR BUS_DIR ENV_DIR ENV_DIST \
        TOOLS_DIR BUILD_DIR DIST_DIR \
        ANDROID_JAR R8_JAR KEYSTORE \
        BUILD_LIB_DIR PHASES_DIR BUILD_TOOLS_DIR GEN_ANDROID_CONFIG \
        MANIFEST_XML RES_DIR JAVA_DIR \
        CORE_NAME MIN_API TARGET_SDK \
        STAGE_APK STAGE_ASSETS RT STAGED_CORE GEN_DIR OBJ_DIR DEX_DIR \
        TARGET_MANIFEST TARGET_RES OUT_APK OUT_APK_ALIAS
}
