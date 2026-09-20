#!/bin/sh
# 编译 bgroot：Debian(aarch64) 环境下生成 Android 可执行的静态 PIE 二进制
# 用法：./build.sh   （产物输出到 ../app/src/main/assets/root/arm64-v8a/bgroot）
set -e
cd "$(dirname "$0")"

OUT="../assets/root/arm64-v8a"
mkdir -p "$OUT"

# 直接输出到 assets（cpp 目录只保留源码，避免产物误入库）
gcc -O2 -static-pie -fPIE -Wall -o "$OUT/bgroot" bgroot.c
chmod 755 "$OUT/bgroot"

echo "OK -> $OUT/bgroot ($(wc -c < "$OUT/bgroot") bytes)"
