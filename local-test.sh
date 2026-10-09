#!/bin/sh
# local-test.sh —— 本地「构建 → 装机 → 启动 → 取证」闭环（无需手机）
#
# 前置（一次性，已由 2026-10-09 环境准备完成）：
#   * Android SDK: /media/ltbkq/mydata/android-sdk（emulator + platform-tools +
#     system-images;android-34;google_apis;x86_64）
#   * AVD: vmdroid —— **必须用 google_apis 的 x86_64 镜像**：
#       - 本机是 x86_64 + /dev/kvm → x86_64 走 KVM，开机 ~20s
#       - APK 只有 arm64-v8a 的 so（libqemu-system-aarch64.so），靠镜像自带的
#         ARM 翻译层执行：build.prop 里 ro.dalvik.vm.native.bridge=libndk_translation.so
#       - 若改用 arm64-v8a 镜像 → x86 主机只能 TCG 软件模拟，里面还要再跑一层
#         QEMU（双重模拟），不可用
#   * /dev/kvm 存在且可读写
#
# 用法：
#   ./local-test.sh up                 # 启动模拟器（已运行则跳过），等待开机
#   ./local-test.sh build              # 单元测试 + 出 debug APK
#   ./local-test.sh install            # 安装/覆盖安装
#   ./local-test.sh run                # 拉起应用 + 截图 + 抓崩溃日志
#   ./local-test.sh push-image <img>   # 把系统镜像推进应用私有目录（大文件，慢）
#   ./local-test.sh smoke              # up + build + install + run（一键）
#   ./local-test.sh down               # 关闭模拟器
#   ./local-test.sh log                # 只看崩溃/native 库相关日志
set -eu

SDK=${ANDROID_SDK_ROOT:-/media/ltbkq/mydata/android-sdk}
export ANDROID_HOME="$SDK"
export PATH="$SDK/emulator:$SDK/platform-tools:$PATH"
APP_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
PKG=io.github.ltbkq.vmdroid.debug
ACT=$PKG/io.github.ltbkq.vmdroid.MainActivity
AVD=${VMDROID_AVD:-vmdroid}
SHOT_DIR=${VMDROID_SHOT_DIR:-/tmp/vmdroid-shots}
APK=$APP_DIR/app/build/outputs/apk/debug/app-debug.apk

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

device() { adb devices 2>/dev/null | grep -q "emulator-.*device$"; }

cmd_up() {
    if device; then log "模拟器已运行 ($(adb devices | grep emulator-))"; return 0; fi
    [ -d "$SDK/emulator" ] || die "缺 emulator 包：sdkmanager \"emulator\""
    [ -d "$SDK/system-images/android-34/google_apis/x86_64" ] \
        || die "缺系统镜像：sdkmanager \"system-images;android-34;google_apis;x86_64\""
    [ -e /dev/kvm ] || warn_kvm="警告：无 /dev/kvm，将回退软件模拟（很慢）"
    log "启动模拟器 $AVD（无头）"
    setsid nohup "$SDK/emulator/emulator" -avd "$AVD" \
        -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim \
        -no-snapshot -no-metrics -memory 4096 -cores 4 \
        > /tmp/vmdroid-emulator.log 2>&1 < /dev/null &
    disown || true
    [ -z "${warn_kvm:-}" ] || printf '%s\n' "$warn_kvm"
    adb wait-for-device
    i=0; B=0
    while [ "$i" -lt 300 ]; do
        B=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        [ "$B" = "1" ] && break
        i=$((i+3)); sleep 3
    done
    [ "$B" = "1" ] || die "开机超时（详见 /tmp/vmdroid-emulator.log）"
    log "开机完成（等了 ${i}s）  abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')  翻译层=$(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"
}

cmd_build() {
    cd "$APP_DIR"
    log "单元测试 + assembleDebug"
    ./gradlew testDebugUnitTest assembleDebug
    [ -f "$APK" ] || die "没产出 APK：$APK"
    log "APK: $APK ($(stat -c%s "$APK") bytes, $(date -r "$APK" '+%F %T'))"
}

cmd_install() {
    device || { cmd_up; }
    [ -f "$APK" ] || die "先跑 ./local-test.sh build"
    log "安装 $APK"
    adb install -r "$APK"
    adb shell dumpsys package "$PKG" | grep -E "versionCode|versionName|primaryCpuAbi" | sed 's/^ */    /' | head -3
}

cmd_run() {
    device || cmd_up
    mkdir -p "$SHOT_DIR"
    N=$(ls "$SHOT_DIR"/shot-*.png 2>/dev/null | wc -l)
    adb logcat -c || true
    log "启动 $ACT"
    adb shell am start -n "$ACT" >/dev/null
    sleep "${VMDROID_RUN_WAIT:-12}"
    OUT=$SHOT_DIR/shot-$((N+1))-$(date +%H%M%S).png
    adb exec-out screencap -p > "$OUT"
    log "截图: $OUT"
    log "前台: $(adb shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | tr -d '\r' | sed 's/^ *//')"
    if adb logcat -d 2>/dev/null | grep -qE "FATAL EXCEPTION|UnsatisfiedLinkError"; then
        echo "---- 崩溃/native 库日志 ----"
        adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|UnsatisfiedLinkError" -A15 | tail -30
        die "应用有崩溃"
    fi
    log "无崩溃 ✔"
}

cmd_push_image() {
    IMG=${1:?用法: ./local-test.sh push-image <img>}
    [ -f "$IMG" ] || die "找不到镜像: $IMG"
    device || cmd_up
    ID=$(basename "$IMG" .img)
    log "推送 $IMG -> /data/data/$PKG/files/images/$ID.img（大文件，耐心）"
    # 应用私有目录只能用 run-as 写；exec-in 让 stdin 直接进 guest，避免落临时文件
    adb exec-in run-as "$PKG" sh -c "mkdir -p files/images && cat > files/images/$ID.img" < "$IMG"
    adb shell run-as "$PKG" ls -la files/images/ | tail -5
    log "完成。在应用内导入/激活该镜像后即可启动 VM（也可 adb shell am start -a $PKG.action.START_VM）"
}

cmd_smoke() { cmd_up; cmd_build; cmd_install; cmd_run; }
cmd_log()   { adb logcat -d 2>/dev/null | grep -iE "vmdroid|qemu|ndk_translation|FATAL|UnsatisfiedLink" | tail -80; }
cmd_down()  { adb emu kill 2>/dev/null || pkill -f "[e]mulator -avd $AVD" || true; log "模拟器已关闭"; }

case ${1:-smoke} in
    up)         cmd_up;;
    build)      cmd_build;;
    install)    cmd_install;;
    run)        cmd_run;;
    push-image) shift; cmd_push_image "$@";;
    smoke)      cmd_smoke;;
    down)       cmd_down;;
    log)        cmd_log;;
    *)          grep -E '^#   \./local-test' "$0"; exit 2;;
esac
