#!/usr/bin/env bash
#
# tools/m0/p0-1-run.sh —— M0 spike P0-1 一键探测（docs/spikes/M0-runbook.md §P0-1）
#
# 前置：一台支持 AVF 的 arm64 Android 设备（Pixel 8+/Android 16），adb 可见。
# 动作：环境预检 → 跑 AvfFirmwareProbeTest（纯反射探测，不创建 VM）→ 采集 logcat
#       → 解析 VERDICT 并映射到设计文档 §3.2 方案 A/B/C 决策。
# 产物：tools/m0/out/p0-1-<时间戳>.log（完整探测输出）+ stdout 结论行。
#
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUTDIR="$HERE/out"
mkdir -p "$OUTDIR"
TS=$(date +%Y%m%d-%H%M)
LOG="$OUTDIR/p0-1-$TS.log"

die() { echo "p0-1: ERROR: $*" >&2; exit 1; }

# ---- 0. 环境预检 --------------------------------------------------------------
command -v adb >/dev/null || die "adb missing"
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(printf '%s' "$DEVICES" | grep -c . || true)
[ "$COUNT" -ge 1 ] || die "无 adb 设备（adb devices 为空）——P0-1 需要真机"
[ "$COUNT" = 1 ] || die "多台设备（$COUNT）：请只连一台或手动指定"

SERIAL=$DEVICES
REL=$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')
ABI=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')

{
  echo "== P0-1 probe @ $(date -Is)"
  echo "device=$SERIAL model=$MODEL release=$REL abi=$ABI"
} | tee "$LOG"

[ "$ABI" = "arm64-v8a" ] || die "非 arm64 设备（$ABI）——AVF 不适用"
if [ "${REL%%.*}" -lt 16 ] 2>/dev/null; then
  echo "p0-1: warn: Android $REL < 16 —— AVF 公开 API 可能缺失，探测照跑（预期 SIGNAL-C）" | tee -a "$LOG"
fi

# ---- 1. 旁证：shell 侧 AVF 能力（不装 APK 也能看的）----------------------------
{
  echo "-- adb shell 旁证 --"
  adb -s "$SERIAL" shell cmd -l 2>/dev/null | grep -i virtual || echo "(无 virtual_service shell 命令)"
  adb -s "$SERIAL" shell dumpsys virtualization 2>/dev/null | head -40 || true
} 2>&1 | tee -a "$LOG" || true

# ---- 2. 跑探测（instrumentation；gradle 会自动构建+安装 debug 包）--------------
echo "p0-1: running AvfFirmwareProbeTest (build+install+probe, 首次可能数分钟)..." | tee -a "$LOG"
set +e
(cd "$REPO" && ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.ltbkq.vmdroid.engine.avf.AvfFirmwareProbeTest \
    2>&1) | tee -a "$LOG"
GRADLE_RC=${PIPESTATUS[0]}
set -e
[ "$GRADLE_RC" -eq 0 ] || echo "p0-1: warn: gradle rc=$GRADLE_RC（探测可能未跑完，看 log）" | tee -a "$LOG"

# ---- 3. 采集 + 判定 -------------------------------------------------------------
adb -s "$SERIAL" logcat -d -s AvfFirmwareProbe 2>/dev/null | tee -a "$LOG" || true

VERDICT=$(grep -a "VERDICT=" "$LOG" | tail -1 | sed 's/.*VERDICT=//')
echo
echo "== 结论：${VERDICT:-未取到（看 $LOG）}"
case "$VERDICT" in
  SIGNAL-A*|*"no-api-but-accepted"*)
    echo "→ 方案 A 可行（AVF 固件启动）：续做启动级验证 —— 手工 v2 镜像 + addDisk(rw) + run() 至 Ready!"
    echo "  通过则 QEMU/AVF 同构（最干净）；P0-2 的 D10 AVF/KVM ≤ +2s 档可立即实测（M-c）" ;;
  SIGNAL-B*)
    echo "→ 方案 A 不可行：按设计文档 §3.2 实现 ESP 提取直启（FAT32 读取器 + setKernelPath/setInitrdPath + addParams）"
    echo "  D10 的 AVF/KVM 时延档作废（AVF 走直启，与 v1 同）" ;;
  SIGNAL-C*)
    echo "→ 设备不支持 AVF：该设备走 QEMU 后端；P0-1 结论按『AVF 未知，B 兜底』处理" ;;
  *)
    echo "→ 未判定：读 $LOG 的 keyword-matched API 段与 build 异常原文" ;;
esac
echo "完整输出：$LOG"
