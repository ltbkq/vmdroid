#!/bin/sh
# tools/pc-run.sh —— VMDroid `.img` 在 Linux PC 上一键启动（DESIGN §11.4 · R-16 / G9）
#
#   tools/pc-run.sh debian.img                      # 前台启动，console 落 <run-dir>/console.log
#   tools/pc-run.sh --dry-run debian.img            # 只打印完整 QEMU 命令行
#   tools/pc-run.sh --smoke --timeout 90 debian.img # 启动 + 等 Ready! + SSH 探活 + 退出
#
# 共享实现：tools/lib/imgboot.sh（Podroid-Debian/tools/pc-boot-smoke.sh source 同一份，
# 提取与启动逻辑单一来源，不复制两份 —— DESIGN §11.2 归属）。
# 依赖：qemu-system-aarch64(apt install qemu-system-arm) python3 e2fsprogs openssh-client
set -eu

# ---------------------------------------------------------------- 载入库
pc_self_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1090,SC1091
. "$pc_self_dir/lib/imgboot.sh"

# ---------------------------------------------------------------- 用法
pc_usage() {
  cat <<'EOF'
用法: tools/pc-run.sh [选项] <image.img>

启动方式（DESIGN §11.4）：
  （默认）          前台启动 QEMU，guest console 落 <run-dir>/console.log
  --dry-run         只打印完整 QEMU 命令行（不执行、不产生任何文件）
  --smoke           启动 → 等待 Ready!（默认 90s）→ SSH 探活 → 停机退出

选项：
  --timeout N       Ready! 超时秒数，默认 90（与 boot-test.sh BOOT_TIMEOUT 对齐）
  --run-dir DIR     运行目录（socket/console/storage/提取物），默认 ./vmdroid-run
  --storage FILE    数据盘路径，默认 <run-dir>/storage.img
  --storage-gb N    首次创建数据盘的大小（GiB，默认 4）
  -h, --help        本帮助

依赖：qemu-system-aarch64（sudo apt install qemu-system-arm）、python3、
      mkfs.ext4（e2fsprogs）、ssh（openssh-client）；可选 sshpass（密码探活）。

示例：
  tools/pc-run.sh debian.img
  tools/pc-run.sh --dry-run debian.img
  tools/pc-run.sh --smoke --timeout 90 --run-dir /tmp/vr debian.img
  ssh -o StrictHostKeyChecking=no -p 9922 ltbkq@127.0.0.1    # 密码 123
EOF
}

pc_usage_error() {
  printf 'pc-run: error: %s\n\n' "$1" >&2
  pc_usage >&2
  exit 2
}

# ---------------------------------------------------------------- 依赖检查
pc_dep_missing=0
pc_need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'pc-run: missing dependency: %-22s -> sudo apt install %s\n' "$1" "$2" >&2
    pc_dep_missing=1
  fi
}
pc_check_deps() {
  pc_need qemu-system-aarch64 qemu-system-arm
  pc_need python3 python3
  pc_need mkfs.ext4 e2fsprogs
  pc_need ssh openssh-client
  if [ "$pc_dep_missing" -ne 0 ]; then
    printf 'pc-run: 依赖缺失，先执行：sudo apt install qemu-system-arm python3 e2fsprogs openssh-client\n' >&2
    exit 1
  fi
}

# ---------------------------------------------------------------- 端口预检
# hostfwd 三端口由启动契约固定（DESIGN §11.4），被占用时 QEMU 必然启动失败
# （slirp 报 "Could not set up host forwarding rule"），这里提前给出可执行指引。
pc_preflight_ports() {
  pc_busy=$(python3 - <<'PC_PORT_PY'
import socket
busy = []
for p in (9922, 5900, 4713):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", p))
    except OSError as e:
        busy.append("%d (%s)" % (p, e.strerror))
    finally:
        s.close()
print(" ".join(busy))
PC_PORT_PY
)
  [ -n "$pc_busy" ] || return 0
  imgboot_log "preflight FAIL: 127.0.0.1 端口被占用 → $pc_busy"
  ss -ltnp 2>/dev/null | grep -E ':(9922|5900|4713) ' | sed 's/^/       | /' >&2 || true
  imgboot_log "指引: 这三个端口由启动契约固定，无法改端口；请释放后重试"
  imgboot_log "  常见原因: 上一次未退出的 qemu-system-aarch64（pgrep -af qemu-system-aarch64）"
  exit 1
}

# ---------------------------------------------------------------- SSH 探活
# 顺序：端口就绪(≤30s) → BatchMode 密钥登录 → sshpass 密码登录 → 端口探活降级
pc_wait_port() {
  python3 - "$1" <<'PC_PORT_PY'
import socket, sys, time
port = int(sys.argv[1])
deadline = time.time() + 30
while True:
    try:
        s = socket.create_connection(("127.0.0.1", port), 3)
        s.close()
        sys.exit(0)
    except OSError:
        if time.time() >= deadline:
            print("pc-run: port %d unreachable after 30s" % port, file=sys.stderr)
            sys.exit(1)
        time.sleep(1)
PC_PORT_PY
}

pc_ssh_probe() {
  if ! pc_wait_port 9922; then
    imgboot_log "ssh probe FAIL: 127.0.0.1:9922 在 30s 内不可达（dropbear 未起来）"
    return 1
  fi
  imgboot_log "ssh probe: port 9922 open, trying login as ltbkq"
  if ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
        -o BatchMode=yes -o ConnectTimeout=5 -p 9922 ltbkq@127.0.0.1 \
        'echo pc-run: ssh-ok' 2>/dev/null; then
    imgboot_log "ssh probe OK (publickey/agent)"
    return 0
  fi
  if command -v sshpass >/dev/null 2>&1; then
    if sshpass -p 123 ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
          -o LogLevel=ERROR -o PreferredAuthentications=password -o PubkeyAuthentication=no \
          -o ConnectTimeout=5 -p 9922 ltbkq@127.0.0.1 'echo pc-run: ssh-ok' 2>/dev/null; then
      imgboot_log "ssh probe OK (password via sshpass, user=ltbkq)"
      return 0
    fi
    imgboot_log "ssh probe FAIL: 9922 已开但密码登录被拒（账户/密码/镜像契约异常）"
    return 1
  fi
  imgboot_log "ssh probe DEGRADED: 未安装 sshpass，无法非交互输入密码 —— 已降级为端口探活"
  imgboot_log "  （127.0.0.1:9922 可达 = guest dropbear 已监听 22；完整登录断言需 sudo apt install sshpass）"
  imgboot_log "  手动验证：ssh -o StrictHostKeyChecking=no -p 9922 ltbkq@127.0.0.1  # 密码 123"
  return 0
}

# ---------------------------------------------------------------- 参数
pc_mode=run
pc_run_dir=${IMGBOOT_RUN_DIR:-./vmdroid-run}
pc_storage=
pc_gb=4
pc_timeout=90
pc_img=

while [ $# -gt 0 ]; do
  case $1 in
    --dry-run)    pc_mode=dry ;;
    --smoke)      pc_mode=smoke ;;
    --run-dir)    shift; [ $# -gt 0 ] || pc_usage_error "--run-dir 缺少取值"; pc_run_dir=$1 ;;
    --storage)    shift; [ $# -gt 0 ] || pc_usage_error "--storage 缺少取值"; pc_storage=$1 ;;
    --storage-gb) shift; [ $# -gt 0 ] || pc_usage_error "--storage-gb 缺少取值"; pc_gb=$1 ;;
    --timeout)    shift; [ $# -gt 0 ] || pc_usage_error "--timeout 缺少取值"; pc_timeout=$1 ;;
    -h|--help)    pc_usage; exit 0 ;;
    --)           shift; break ;;
    -*)           pc_usage_error "未知选项: $1" ;;
    *)            [ -z "$pc_img" ] || pc_usage_error "只允许一个 <image.img>: $1"; pc_img=$1 ;;
  esac
  shift
done
if [ $# -gt 0 ]; then
  [ -z "$pc_img" ] || pc_usage_error "参数过多（位置参数应只有一个 <image.img>）"
  pc_img=$1
  shift
  [ $# -eq 0 ] || pc_usage_error "参数过多: $1"
fi

[ -n "$pc_img" ] || pc_usage_error "缺少 <image.img>"
[ -f "$pc_img" ] || pc_usage_error "镜像不存在: $pc_img"
case $pc_timeout in ''|*[!0-9]*) pc_usage_error "--timeout 必须是非负整数: $pc_timeout" ;; esac
case $pc_gb in ''|*[!0-9]*) pc_usage_error "--storage-gb 必须是非负整数: $pc_gb" ;; esac

pc_storage=${pc_storage:-$pc_run_dir/storage.img}
export IMGBOOT_RUN_DIR="$pc_run_dir"
IMGBOOT_EXTRACT_DIR=${IMGBOOT_EXTRACT_DIR:-$pc_run_dir/extract}
export IMGBOOT_EXTRACT_DIR

pc_check_deps

# ---------------------------------------------------------------- --dry-run（零副作用）
if [ "$pc_mode" = dry ]; then
  imgboot_qemu_argv "$pc_img" "$pc_storage"
  exit 0
fi

# ---------------------------------------------------------------- 启动前准备
imgboot_log "pc-run: mode=$pc_mode image=$pc_img run_dir=$pc_run_dir"
pc_preflight_ports
imgboot_prepare_run_dir "$pc_run_dir"
imgboot_verify "$pc_img"
imgboot_extract "$pc_img" "$IMGBOOT_EXTRACT_DIR"
imgboot_storage_init "$pc_storage" "$pc_gb"

pc_argv=$(imgboot_qemu_argv "$pc_img" "$pc_storage")
pc_console=$pc_run_dir/console.log
imgboot_log "console log: $pc_console"
imgboot_log "端口（仅回环）: 9922->22(ssh ltbkq/123)  5900(vnc)  4713(pulse)"

# ---------------------------------------------------------------- 默认：前台运行
if [ "$pc_mode" = run ]; then
  imgboot_log "starting qemu-system-aarch64 (foreground; Ctrl-C 停止; tail -f $pc_console 看输出)"
  pc_rc=0
  # 注意：`sh -c "<argv>"` 必须**显式 exec** —— dash 对后台 `sh -c 'cmd'` 不会
  # 自动 exec（实测 comm 仍是 sh，qemu 是它的子进程），$! 就不是 qemu 本体，
  # 停机时 kill 不到真 qemu（曾泄漏一个实例并占满 9922/5900/4713 三个端口）。
  sh -c "exec $pc_argv" >"$pc_console" 2>&1 || pc_rc=$?
  imgboot_log "qemu exited with rc=$pc_rc"
  exit "$pc_rc"
fi

# ---------------------------------------------------------------- --smoke
pc_qpid=
# 等待进程真正消失（最多 <tenths> 个 0.25s）；僵尸视同已退出
pc_wait_gone() {
  pc_wg=0
  while [ "$pc_wg" -lt "$2" ]; do
    [ -e "/proc/$1" ] || return 0
    case $(sed -n 's/^State:[[:space:]]*\([A-Z]\).*/\1/p' "/proc/$1/status" 2>/dev/null) in
      Z) return 0 ;;
    esac
    sleep 0.25
    pc_wg=$((pc_wg + 1))
  done
  [ -e "/proc/$1" ] || return 0
  return 1
}

pc_cleanup() {
  if [ -n "${pc_qpid:-}" ] && kill -0 "$pc_qpid" 2>/dev/null; then
    imgboot_log "stopping qemu (pid $pc_qpid)"
    kill "$pc_qpid" 2>/dev/null || true
    if ! pc_wait_gone "$pc_qpid" 40; then
      imgboot_log "qemu 未响应 SIGTERM，补 SIGKILL (pid $pc_qpid)"
      kill -KILL "$pc_qpid" 2>/dev/null || true
      pc_wait_gone "$pc_qpid" 10 || true
    fi
    wait "$pc_qpid" 2>/dev/null || true
  fi
  # QEMU 正常退出会自行 unlink socket；异常退出（如 hostfwd 端口被占）会留下残留，
  # 这里统一清掉，避免下次启动 "Failed to bind socket"。
  rm -f -- "$pc_run_dir/terminal.sock" "$pc_run_dir/ctrl.sock" "$pc_run_dir/host.sock"
}
trap 'pc_cleanup' EXIT
trap 'pc_cleanup; exit 130' INT
trap 'pc_cleanup; exit 143' TERM

: >"$pc_console"
imgboot_log "starting qemu-system-aarch64 (background)"
sh -c "exec $pc_argv" >"$pc_console" 2>&1 &
pc_qpid=$!
imgboot_log "qemu started (pid $pc_qpid); waiting for Ready! (timeout ${pc_timeout}s)"

pc_rc=0
if ( imgboot_wait_ready "$pc_console" "$pc_timeout" "$pc_qpid" ); then
  imgboot_log "PASS: Ready! within ${pc_timeout}s —— 探活 SSH 127.0.0.1:9922"
  pc_ssh_probe || pc_rc=1
else
  pc_rc=1
  imgboot_log "FAIL: ${pc_timeout}s 内未出现 Ready! —— console.log 末尾 40 行："
  if [ -f "$pc_console" ]; then
    tail -n 40 "$pc_console" >&2 || true
  fi
fi
pc_cleanup
pc_qpid=
imgboot_log "smoke finished: rc=$pc_rc (console: $pc_console)"
exit "$pc_rc"
