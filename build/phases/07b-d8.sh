#!/usr/bin/env bash
#
# 07b-d8.sh - compile JVM bytecode into Dalvik/ART DEX via R8/D8.
#

log "[8/11] d8 (min-api $MIN_API)"
mapfile -d '' CLASS_FILES < <(find "$OBJ_DIR" -name '*.class' -print0 | sort -z)
[ "${#CLASS_FILES[@]}" -gt 0 ] || die "no compiled .class files under $OBJ_DIR"

java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --output "$DEX_DIR" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_API" \
    "${CLASS_FILES[@]}"

[ -f "$DEX_DIR/classes.dex" ] || die "d8 produced no classes.dex"
