#!/bin/sh
# 编译 bgroot：Debian(aarch64) 环境下生成 Android 可执行的静态 PIE 二进制
# 用法：./build.sh   （产物输出到 ../app/src/main/assets/root/arm64-v8a/bgroot）
set -e
cd "$(dirname "$0")"

gcc -O2 -static-pie -fPIE -Wall -o bgroot bgroot.c

OUT="../assets/root/arm64-v8a"
mkdir -p "$OUT"
cp bgroot "$OUT/bgroot"
chmod 755 "$OUT/bgroot"

echo "OK -> $OUT/bgroot ($(wc -c < "$OUT/bgroot") bytes)"
