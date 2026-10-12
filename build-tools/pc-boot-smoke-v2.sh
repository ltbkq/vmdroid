#!/usr/bin/env bash
#
# tools/pc-boot-smoke-v2.sh — VMDroid **v2 完整磁盘镜像**的 PC 端引导冒烟器
#
# Spec: IMAGE-FORMAT-v2.md §2（双盘拓扑）· §6 步骤 9（冒烟要求）
#       docs/spikes/M0-runbook.md §P0-2（六轮实测：最优配置 = `-bios QEMU_EFI.fd`
#       + ESP 存未压缩 Image + GRUB 模块含 gzio；PC/TCG 内核接管 ~10.3s、
#       initramfs(podroid-init) ~16.5s、到 Ready! 预计 60-90s）
#
# 与 v1 tools/pc-boot-smoke.sh 的关系（**刻意不复用其逻辑**）：
#   * v2 是**完整 GPT 磁盘镜像**：不解析 v1 footer、不提取 kernel/initrd、
#     不用 `-kernel/-initrd` 直启 —— 必须走 UEFI→GRUB→内核→initramfs 真实链条；
#   * 用**真实双盘拓扑**启动（vda = 临时 sparse storage，vdb = <img>）——
#     这是对 IMAGE-FORMAT-v2 §2 布局本身的鲁棒性验收（M1 实测教训：
#     烘焙 prefix (hd0,gpt1) 单盘能跑、双盘掉 rescue shell）；
#   * 不依赖 vmdroid-app 的 imgboot.sh 共享库（v2 自包含，v1 的共享库归 v1 用）；
#   * 串口采集/超时杀 qemu 的 runner 借鉴 tools/m0/p0-2-uefi-chain.sh 的 stamp.py
#     （子进程 + select 轮询 + 超时 kill），**刻意不用 `timeout` 包 qemu**，
#     以区分「超时」(exit 1) 与「qemu 异常退出」(exit 3)。
#
# usage: tools/pc-boot-smoke-v2.sh [--stage bootchain|ready] [--timeout N] <img>
# env  : QEMU_EFI   UEFI 固件路径（默认 /usr/share/qemu-efi-aarch64/QEMU_EFI.fd）
#        TMPDIR     临时目录根（默认 /tmp）
#
# stage:
#   ready      串口必须出现 "Ready!"（v2 guest 就绪契约；M2 之后才可能通过）
#   bootchain  串口出现 podroid-init 即通过 —— initramfs 已接管，证明
#              UEFI→GRUB→内核→initramfs 链通（M2 前的过渡模式）；
#              当前 v1 initrd 会**预期地**在 "FATAL: squashfs (/dev/vdb)" 失败，
#              该失败不算 bootchain 失败（marker 出现在 FATAL 之前）
#
# exit: 0 通过 · 1 超时未见目标标记 · 2 用法/环境错误 · 3 qemu 意外崩溃
#
set -euo pipefail

# 输出前缀与 v1 pc-boot-smoke.sh 保持一致（`pc-boot-smoke: ...`）
LOG_TAG="pc-boot-smoke"
log()  { echo "$LOG_TAG: $*"; }
warn() { echo "$LOG_TAG: warn: $*" >&2; }
# die = 用法/环境错误（缺工具、缺固件、img 不存在、参数非法）→ exit 2
die()  { echo "$LOG_TAG: ERROR: $*" >&2; exit 2; }

usage() {
    cat <<'EOF'
usage: tools/pc-boot-smoke-v2.sh [--stage bootchain|ready] [--timeout N] <img>

  boots <img> with the REAL dual-disk topology (no -kernel/-initrd):

    qemu-system-aarch64 -M virt -cpu max -smp 2 -m 2048
      -bios $QEMU_EFI
      -drive if=virtio,format=raw,file=<temp sparse storage>   (vda)
      -drive if=virtio,format=raw,file=<img>                   (vdb)
      -display none -serial stdio -monitor none

  and waits, within the timeout (default 120 s), for the stage marker:

    --stage ready      serial contains "Ready!"      (v2 guest ready contract)
    --stage bootchain  serial contains podroid-init  (initramfs took over:
                       UEFI -> GRUB -> kernel -> initramfs chain OK;
                       a later "FATAL: squashfs" from the v1 initrd is expected)

env: QEMU_EFI=/usr/share/qemu-efi-aarch64/QEMU_EFI.fd, TMPDIR=/tmp

exit: 0 pass · 1 timeout (marker not seen) · 2 usage/environment · 3 qemu crashed
EOF
}

# ---- defaults ---------------------------------------------------------------
STAGE="ready"
TIMEOUT=120
IMG=""

# ------------------------------------------------------------------- options
while [ $# -gt 0 ]; do
    case "$1" in
        --stage)   [ $# -ge 2 ] || die "--stage needs a value (bootchain|ready)"; STAGE=$2; shift 2 ;;
        --timeout) [ $# -ge 2 ] || die "--timeout needs a value (seconds)";      TIMEOUT=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        --)        shift; break ;;
        -*)        echo "$LOG_TAG: unknown option: $1" >&2; usage >&2; exit 2 ;;
        *)         [ -z "$IMG" ] || { usage >&2; die "unexpected argument: $1"; }; IMG=$1; shift ;;
    esac
done
if [ -z "$IMG" ] && [ $# -gt 0 ]; then IMG=$1; shift; fi
[ $# -eq 0 ] || { usage >&2; die "unexpected argument: $1"; }

# -------------------------------------------------------- 用法/环境检查 (exit 2)
case "$STAGE" in
    ready|bootchain) ;;
    *) die "--stage must be bootchain|ready (got: $STAGE)" ;;
esac
case "$TIMEOUT" in
    ''|*[!0-9]*) die "--timeout must be a positive integer, seconds (got: $TIMEOUT)" ;;
esac
[ "$TIMEOUT" -gt 0 ] || die "--timeout must be > 0 (got: $TIMEOUT)"
[ -n "$IMG" ] || { usage >&2; exit 2; }
[ -f "$IMG" ] || die "no such image: $IMG"
[ -r "$IMG" ] || die "cannot read image: $IMG"
# -drive ...file=<path> 用逗号分隔字段：路径含逗号会破坏 qemu 参数解析
case "$IMG" in *,*) die "image path must not contain ',': $IMG" ;; esac

command -v qemu-system-aarch64 >/dev/null 2>&1 \
    || die "qemu-system-aarch64 not installed (apt install qemu-system-arm)"
command -v python3 >/dev/null 2>&1 \
    || die "python3 required (串口打点 runner)"
FW=${QEMU_EFI:-/usr/share/qemu-efi-aarch64/QEMU_EFI.fd}
if [ ! -f "$FW" ] || [ ! -r "$FW" ]; then
    die "UEFI firmware missing/unreadable: $FW (install qemu-efi-aarch64, or set QEMU_EFI=)"
fi

# ------------------------------------------------------------------- work dir
# 临时 sparse storage 盘 + 带时间戳串口日志都放这里；跑完（success 或 fail）
# storage 必删；失败时保留串口日志供诊断。
WORK=""
RUNNER_PID=""
STORAGE=""
SERIAL_LOG=""
QEMU_ERR=""
RESULT=""

cleanup() {
    rc=$?
    # 1) 不留僵尸：先 TERM runner（它的 Stop 分支会杀掉自己拉起的 qemu 并 wait 收尸），
    #    等它退干净（最多 5s，覆盖 python 侧 terminate→kill 的 3s 上限）再 KILL 兜底，最后 wait。
    if [ -n "$RUNNER_PID" ] && kill -0 "$RUNNER_PID" 2>/dev/null; then
        kill -TERM "$RUNNER_PID" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$RUNNER_PID" 2>/dev/null || break
            sleep 0.5
        done
        kill -KILL "$RUNNER_PID" 2>/dev/null || true
        wait "$RUNNER_PID" 2>/dev/null || true
    fi
    RUNNER_PID=""
    # 2) 临时 sparse storage 盘：无论成败都删除（要求：跑完删除）
    if [ -n "$WORK" ]; then rm -f "$STORAGE"; fi
    # 3) 成功清场；失败保留串口日志（fail_report 已打印尾部并给出路径）
    if [ "$rc" -eq 0 ] && [ -n "$WORK" ]; then rm -rf "$WORK"; fi
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

WORK=$(mktemp -d "${TMPDIR:-/tmp}/pc-boot-smoke-v2.XXXXXX") \
    || die "mktemp failed under TMPDIR=${TMPDIR:-/tmp}"
STORAGE="$WORK/storage.img"
SERIAL_LOG="$WORK/serial.log"
QEMU_ERR="$WORK/qemu.stderr.log"
RESULT="$WORK/result.txt"

log "image: $IMG ($(stat -c%s "$IMG") bytes)"
IMG_MTIME_BEFORE=$(stat -c %Y "$IMG" 2>/dev/null || echo "")
log "stage=$STAGE timeout=${TIMEOUT}s firmware=$FW"
if [ "$STAGE" = bootchain ]; then
    warn "stage=bootchain 是 M2 前的过渡判定（只认 initramfs 接管，不认 Ready!）"
fi

# ------------------------------------------------- 1. 双盘拓扑 + 固件启动
# vda = 临时 storage（256MiB sparse，guest 首启会 mkfs 它）；vdb = 被测镜像。
# 这就是 IMAGE-FORMAT-v2 §2 的真实拓扑：initramfs 按 /dev/vda=storage、
# /dev/vdb=system 找盘，盘序/缺盘都会暴露问题（M1 教训）。
log "storage: truncate 256MiB sparse -> $STORAGE"
truncate -s 256M "$STORAGE" || die "truncate failed: $STORAGE"

QEMU_ARGS=(
    -M virt -cpu max -smp 2 -m 2048
    -bios "$FW"
    -drive "if=virtio,format=raw,file=$STORAGE"
    -drive "if=virtio,format=raw,file=$IMG"
    -display none -serial stdio -monitor none
)
ARGV_STR=$(printf '%q ' qemu-system-aarch64 "${QEMU_ARGS[@]}")
log "qemu argv: $ARGV_STR"

# --------------------------------------------------- 2. 串口打点 runner (python)
# 借鉴 p0-2-uefi-chain.sh 的 stamp.py：子进程拉起 qemu、select 轮询 stdout、
# 逐行加时间戳；在滚动字节窗口上匹配 marker —— 命中即杀 qemu。
# 退出码：0 marker 命中 · 1 超时杀 qemu · 3 qemu 先于 marker 退出 · 4 runner 内部错
RUNNER="$WORK/stamp.py"
cat > "$RUNNER" <<'PY'
import os, re, select, signal, subprocess, sys, time

# argv: <serial.log> <qemu.stderr> <result.txt> <timeout_s> <marker_regex> <qemu> <args...>
log_path, err_path, res_path, timeout_s, pattern = sys.argv[1:6]
cmd = sys.argv[6:]
if not cmd:
    sys.exit(4)
try:
    timeout = float(timeout_s)
except ValueError:
    sys.exit(4)
rx = re.compile(pattern.encode("utf-8"))

t0 = time.time()
logf = open(log_path, "wb")
errf = open(err_path, "wb")

def stamp(raw):
    """带时间戳写一行串口输出（沿用 p0-2 的 [%8.3fs] 格式）"""
    logf.write(("[%8.3fs] " % (time.time() - t0)).encode("ascii") + raw + b"\n")
    logf.flush()

def note(msg):
    stamp(("== " + msg + " ==").encode("utf-8"))

class Stop(Exception):
    pass

def on_term(signum, frame):
    # bash 的 trap 会 TERM 本 runner —— 必须走 cleanup 杀 qemu，不能被信号直接带走
    raise Stop()

signal.signal(signal.SIGTERM, on_term)
signal.signal(signal.SIGINT, on_term)

p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=errf, bufsize=0)

def reap():
    """杀掉并回收 qemu（绝不留僵尸）；返回 qemu 退出码"""
    if p.poll() is None:
        p.terminate()                      # SIGTERM：qemu 优雅退出
        try:
            p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
    else:
        p.wait()
    return p.returncode

outcome, mark_ts, qemu_rc = "timeout", None, None
buf, scan = b"", b""

try:
    while True:
        left = timeout - (time.time() - t0)
        if left <= 0:
            note("TIMEOUT %.0fs reached, qemu killed" % timeout)
            break
        r, _, _ = select.select([p.stdout], [], [], min(0.5, left))
        if not r:
            if p.poll() is not None:       # 无输出且已退出 → 崩溃
                outcome, qemu_rc = "crash", p.returncode
                break
            continue
        data = os.read(p.stdout.fileno(), 65536)
        if not data:                       # EOF：qemu 关掉了 stdout → 已退出
            outcome, qemu_rc = "crash", p.poll()
            break
        scan = (scan + data)[-8192:]       # 滚动窗口：marker 跨 read/跨行也能命中
        # 逐行写时间戳日志（串口行结束符 \r\n，\r 与 \n 都算断行）
        buf += data
        while True:
            idxs = [i for i in (buf.find(b"\n"), buf.find(b"\r")) if i >= 0]
            if not idxs:
                break
            i = min(idxs)
            line, sep, buf = buf[:i], buf[i:i + 1], buf[i + 1:]
            if sep == b"\r" and buf[:1] == b"\n":
                buf = buf[1:]
            if line:
                stamp(line)
        if rx.search(scan):                # 命中目标标记 → 立即杀 qemu 判通过
            mark_ts = time.time() - t0
            outcome = "pass"
            break
except Stop:
    note("interrupted, qemu killed")
    if buf:
        stamp(buf)
    reap()
    sys.exit(130)

if buf:                                    # 收尾：最后一行可能没有换行
    stamp(buf)
rc = reap()                                # 先杀/回收 qemu，再取总运行时长
run_ts = time.time() - t0
if outcome == "pass":
    note("marker %r seen at %.3fs, qemu killed (rc=%d)" % (pattern, mark_ts, rc))
elif outcome == "crash":
    note("qemu exited before marker (rc=%s) at %.3fs" % (rc, run_ts))

with open(res_path, "w") as f:
    f.write("outcome=%s\n" % outcome)
    if mark_ts is not None:
        f.write("mark_ts=%.3f\n" % mark_ts)
    f.write("run_ts=%.3f\n" % run_ts)
    if outcome == "crash":
        f.write("qemu_rc=%s\n" % (qemu_rc if qemu_rc is not None else -1))

sys.exit({"pass": 0, "timeout": 1, "crash": 3}.get(outcome, 4))
PY

# ------------------------------------------------------------------- 3. 启动
case "$STAGE" in
    ready)     MARKER='Ready!' ;;
    # 宽松匹配 `[podroid-init]`：容忍括号/前缀格式差异，同时不会误中 GRUB/内核输出
    bootchain) MARKER='podroid-init' ;;
esac

log "boot (timeout ${TIMEOUT}s), serial -> $SERIAL_LOG"
set +e
# stdin 给 /dev/null：不让 qemu 的 stdio chardev 接管/切换本终端的 raw 模式
python3 "$RUNNER" "$SERIAL_LOG" "$QEMU_ERR" "$RESULT" "$TIMEOUT" "$MARKER" \
    qemu-system-aarch64 "${QEMU_ARGS[@]}" < /dev/null &
RUNNER_PID=$!
wait "$RUNNER_PID"
RC=$?
set -e
RUNNER_PID=""

# ---------------------------------------------------- 4. 结果判定（0/1/2/3）
# 镜像体检：guest 若以 rw 挂载过被测镜像（root journal 回放等），盘上文件就和
# mkdisk 在 smoke **之前**打印的 image_sha256 不一致了 —— 只 warn，不判失败。
IMG_MTIME_AFTER=$(stat -c %Y "$IMG" 2>/dev/null || echo "")
if [ -n "$IMG_MTIME_BEFORE" ] && [ "$IMG_MTIME_AFTER" != "$IMG_MTIME_BEFORE" ]; then
    warn "guest 改写了 $IMG（mtime 变化）—— mkdisk 的 image_sha256 已失效，需要重算"
fi

res_get() { sed -n "s/^$1=//p" "$RESULT" 2>/dev/null | head -1 || true; }

fail_report() {   # 失败诊断：串口日志尾部 30 行（+ qemu stderr 尾部）
    if [ -f "$SERIAL_LOG" ]; then
        echo "---- serial log tail (30) ----" >&2
        tail -30 "$SERIAL_LOG" >&2 || true
        echo "---- end of serial log tail ----" >&2
    fi
    if [ -s "$QEMU_ERR" ]; then
        echo "---- qemu stderr tail (10) ----" >&2
        tail -10 "$QEMU_ERR" >&2 || true
        echo "---- end of qemu stderr tail ----" >&2
    fi
    log "serial log kept: $SERIAL_LOG"
}

if [ "$RC" -eq 0 ] && [ -f "$RESULT" ]; then
    MARK_TS=$(res_get mark_ts)
    RUN_TS=$(res_get run_ts)
    log "PASS: stage=$STAGE marker '$MARKER' at ${MARK_TS}s, qemu total ${RUN_TS}s (timeout ${TIMEOUT}s)"
    # bootchain 的预期后话（不算失败）：v1 initrd 在 initramfs 内 mount squashfs 必然 FATAL
    if [ "$STAGE" = bootchain ] && grep -q "FATAL" "$SERIAL_LOG" 2>/dev/null; then
        log "note: initramfs 之后按预期 FATAL（v1 initrd，无 squashfs —— 不影响 bootchain 判定）"
    fi
    exit 0
fi

case "$RC" in
    1)
        fail_report
        echo "$LOG_TAG: ERROR: no marker '$MARKER' within ${TIMEOUT}s (stage=$STAGE)" >&2
        exit 1 ;;
    3)
        QRC=$(res_get qemu_rc)
        fail_report
        echo "$LOG_TAG: ERROR: qemu exited unexpectedly (rc=${QRC:-?}) before marker '$MARKER' (stage=$STAGE)" >&2
        exit 3 ;;
    130)
        fail_report
        echo "$LOG_TAG: ERROR: interrupted" >&2
        exit 130 ;;
    *)
        fail_report
        die "runner failed (rc=$RC) — 串口 runner 内部错误或无法启动" ;;
esac
