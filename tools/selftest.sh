#!/bin/sh
# tools/selftest.sh —— 工位 C（PC 启动工具）自测：库接口 / footer 校验 / 提取 /
#                       QEMU 参数核对 / storage / Ready! 等待
#
#   sh tools/selftest.sh
#
# 会在 ${IMGBOOT_TEST_DIR:-/tmp/opencode/vmdroid-imgboot-selftest} 下重建夹具
# （~400MB，rootfs 取真实 squashfs 的 bytes_used 字节）。
set -u

ST_SELF=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
ST_DIR=${IMGBOOT_TEST_DIR:-/tmp/opencode/vmdroid-imgboot-selftest}
ST_SQFS=${IMGBOOT_SQFS:-/media/ltbkq/mydata/Podroid-Debian/out/debian-rootfs.squashfs}

case $ST_DIR in
  /tmp/*|"${TMPDIR:-/tmp}"/*) : ;;
  *) printf 'selftest: 拒绝清空非临时目录: %s\n' "$ST_DIR" >&2; exit 2 ;;
esac

ST_LOG=$ST_DIR/last.log
ST_STEP="init"
st_pass=0
st_fail=0

trap '_st_exit' EXIT
# shellcheck disable=SC2317  # 经 trap 间接调用
_st_exit() {
  _rc=$?
  if [ "$_rc" -ne 0 ]; then
    printf '\n[selftest] FAILED @ %s (rc=%s)\n' "$ST_STEP" "$_rc" >&2
  fi
}

# 载入共享库（pc-run.sh / pc-boot-smoke.sh 同样 source 这一份）
# shellcheck disable=SC1090,SC1091
. "$ST_SELF/lib/imgboot.sh"

st_step() { ST_STEP=$1; printf '\n=== [%s]\n' "$1"; }
st_ok() { st_pass=$((st_pass + 1)); printf '  ok   %s\n' "$1"; }
st_bad() { st_fail=$((st_fail + 1)); printf '  FAIL %s\n' "$1"; }
st_dump() { sed 's/^/       | /' "$ST_LOG"; }

# 运行命令串（子 shell —— imgboot 的失败路径是 exit 1），成功即 ok
st_check() {
  if ( eval "$2" ) >"$ST_LOG" 2>&1; then
    st_ok "$1"
  else
    st_bad "$1"; st_dump
  fi
}

# 期望失败；可选第三个参数 = 诊断串必须命中
st_expect_fail() {
  if ( eval "$2" ) >"$ST_LOG" 2>&1; then
    st_bad "$1 —— 期望拒绝却成功了"; st_dump
  elif [ -n "${3:-}" ] && ! grep -q -- "$3" "$ST_LOG"; then
    st_bad "$1 —— 失败但缺诊断串『$3』"; st_dump
  else
    st_ok "$1 —— $(tail -n 1 "$ST_LOG")"
  fi
}

# 翻转一个字节（XOR 1），再次调用即还原
st_flip() {
  python3 - "$1" "$2" <<'ST_FLIP_PY'
import sys
path, off = sys.argv[1], int(sys.argv[2])
with open(path, "r+b") as f:
    f.seek(off)
    b = f.read(1)
    if len(b) != 1:
        sys.exit("flip: eof at %d" % off)
    f.seek(off)
    f.write(bytes([b[0] ^ 1]))
ST_FLIP_PY
}

# 写入一个 u32（小端），用于 flags 用例
st_poke32() {
  python3 - "$1" "$2" "$3" <<'ST_POKE_PY'
import struct, sys
path, off, val = sys.argv[1], int(sys.argv[2]), int(sys.argv[3], 0)
with open(path, "r+b") as f:
    f.seek(off)
    f.write(struct.pack("<I", val))
ST_POKE_PY
}

# 从 imgboot_verify 的 stdout 取某个 footer 字段行
st_field() { sed -n "s/^ *$1 *: //p" "$ST_LOG" | head -n 1; }

# ================================================================ 1. 静态检查
st_step "1/10 语法检查 bash -n + shellcheck"
if bash -n "$ST_SELF/pc-run.sh" "$ST_SELF/lib/imgboot.sh" "$ST_SELF/selftest.sh"; then
  st_ok "bash -n 通过（pc-run.sh / lib/imgboot.sh / selftest.sh）"
else
  st_bad "bash -n 失败"
fi
if command -v shellcheck >/dev/null 2>&1; then
  if shellcheck "$ST_SELF/pc-run.sh" "$ST_SELF/lib/imgboot.sh" "$ST_SELF/selftest.sh" \
      >"$ST_LOG" 2>&1; then
    st_ok "shellcheck 无 issue（rc=0）"
  else
    st_bad "shellcheck 有 issue"; st_dump
  fi
else
  st_bad "shellcheck 未安装"
fi

# ================================================================ 2. 构造夹具
st_step "2/10 构造 fixture .img"
rm -rf -- "$ST_DIR"
mkdir -p "$ST_DIR"
ST_FIX=$ST_DIR/fixture.img
if [ -f "$ST_SQFS" ]; then
  if python3 "$ST_SELF/tests/mkfixture.py" -o "$ST_FIX" --rootfs "$ST_SQFS" \
      >"$ST_DIR/fixture.meta"; then
    st_ok "fixture 由真实 rootfs 构成: $ST_SQFS"
  else
    st_bad "mkfixture 失败"
  fi
else
  printf 'note: %s 不存在，改用 1MiB 假 rootfs\n' "$ST_SQFS"
  if python3 "$ST_SELF/tests/mkfixture.py" -o "$ST_FIX" >"$ST_DIR/fixture.meta"; then
    st_ok "fixture 由假 rootfs 构成"
  else
    st_bad "mkfixture 失败"
  fi
fi
sed 's/^/       | /' "$ST_DIR/fixture.meta"
ST_FIX_META=$ST_DIR/fixture.meta

# ================================================================ 3. imgboot_verify 通过
st_step "3/10 imgboot_verify 通过（stdout 打印关键字段）"
st_check "imgboot_verify $ST_FIX" "imgboot_verify '$ST_FIX'"
cat "$ST_LOG"
ST_K_SHA=$(st_field kernel | sed -n 's/.* sha256=\([0-9a-f]*\)$/\1/p')
ST_I_SHA=$(st_field initrd | sed -n 's/.* sha256=\([0-9a-f]*\)$/\1/p')
ST_K_OFF=$(st_field kernel | sed -n 's/.*offset=\([0-9]*\) .*/\1/p')
ST_M_OFF=$(st_field manifest | sed -n 's/.*offset=\([0-9]*\) .*/\1/p')
ST_SIZE=$(st_field file_size)
for _v in "$ST_K_SHA" "$ST_I_SHA" "$ST_K_OFF" "$ST_M_OFF" "$ST_SIZE"; do
  [ -n "$_v" ] || st_bad "verify 输出缺字段（解析失败）"
done

# ================================================================ 4. 损坏必须拒绝
st_step "4/10 损坏镜像必须拒绝（IMAGE-FORMAT §8 向量）"
ST_BAD=$ST_DIR/bad.img
cp -- "$ST_FIX" "$ST_BAD"
ST_BSIZE=$(stat -c %s -- "$ST_BAD")
ST_FOFF=$((ST_BSIZE - 4096))

st_flip "$ST_BAD" "$ST_FOFF"
st_expect_fail "footer magic 单字节翻转 → 拒绝" "imgboot_verify '$ST_BAD'" "magic"
st_flip "$ST_BAD" "$ST_FOFF"
st_check "还原后重新通过" "imgboot_verify '$ST_BAD'"

st_flip "$ST_BAD" "$ST_M_OFF"
st_expect_fail "manifest 单字节翻转 → 拒绝" "imgboot_verify '$ST_BAD'" "manifest_sha256"
st_flip "$ST_BAD" "$ST_M_OFF"
st_check "还原后重新通过" "imgboot_verify '$ST_BAD'"

# flags@120（u32，合法掩码 0x3）三分支
st_poke32 "$ST_BAD" $((ST_FOFF + 120)) 0x7
st_expect_fail "flags=0x7（保留位 bit2 置位）→ 拒绝" "imgboot_verify '$ST_BAD'" "unknown flags"
st_poke32 "$ST_BAD" $((ST_FOFF + 120)) 0x3
st_check "flags 还原 0x3 → 通过" "imgboot_verify '$ST_BAD'"
st_poke32 "$ST_BAD" $((ST_FOFF + 120)) 0x1
st_expect_fail "flags=0x1（bit1 丢失但 initrd 段存在）→ 拒绝" \
  "imgboot_verify '$ST_BAD'" "flags.bit1 未置位"
st_poke32 "$ST_BAD" $((ST_FOFF + 120)) 0x3
st_check "flags 再次还原 0x3 → 通过" "imgboot_verify '$ST_BAD'"

st_flip "$ST_BAD" "$ST_K_OFF"
st_expect_fail "kernel 单字节翻转 → extract 拒绝" \
  "imgboot_extract '$ST_BAD' '$ST_DIR/bad-extract'" "sha256"
st_flip "$ST_BAD" "$ST_K_OFF"

if [ -f "$ST_SQFS" ]; then
  st_expect_fail "裸 squashfs（无 footer）→ 拒绝并提示 mkimg" \
    "imgboot_verify '$ST_SQFS'" "mkimg"
fi

truncate -s $((ST_BSIZE - 1)) "$ST_BAD"
st_expect_fail "文件截断 1 字节 → 拒绝" "imgboot_verify '$ST_BAD'" "file_size"

# ================================================================ 5. imgboot_extract + 独立复核
st_step "5/10 imgboot_extract + sha256sum 独立复核"
ST_EXT=$ST_DIR/extract
st_check "imgboot_extract" "imgboot_extract '$ST_FIX' '$ST_EXT'"
cat "$ST_LOG"
for _f in vmlinuz initrd.img; do
  if [ -f "$ST_EXT/$_f" ]; then st_ok "产物存在: $ST_EXT/$_f"; else st_bad "缺产物: $_f"; fi
done
ST_K_ACT=$(sha256sum "$ST_EXT/vmlinuz" | cut -d' ' -f1)
ST_I_ACT=$(sha256sum "$ST_EXT/initrd.img" | cut -d' ' -f1)
ST_K_FIX=$(sed -n 's/^kernel_offset=[0-9]* kernel_size=[0-9]* kernel_sha256=\([0-9a-f]*\)$/\1/p' "$ST_FIX_META")
ST_I_FIX=$(sed -n 's/^initrd_offset=[0-9]* initrd_size=[0-9]* initrd_sha256=\([0-9a-f]*\)$/\1/p' "$ST_FIX_META")
ST_K_SIZE=$(sed -n 's/^kernel_offset=[0-9]* kernel_size=\([0-9]*\) .*/\1/p' "$ST_FIX_META")
if [ "$ST_K_ACT" = "$ST_K_FIX" ] && [ "$ST_K_ACT" = "$ST_K_SHA" ]; then
  st_ok "vmlinuz sha256 三方一致 (sha256sum == mkfixture == footer)"
else
  st_bad "vmlinuz sha256 不一致: sha256sum=$ST_K_ACT fixture=$ST_K_FIX footer=$ST_K_SHA"
fi
if [ "$ST_I_ACT" = "$ST_I_FIX" ] && [ "$ST_I_ACT" = "$ST_I_SHA" ]; then
  st_ok "initrd.img sha256 三方一致"
else
  st_bad "initrd.img sha256 不一致: sha256sum=$ST_I_ACT fixture=$ST_I_FIX footer=$ST_I_SHA"
fi
ST_K_ACT_SIZE=$(stat -c %s -- "$ST_EXT/vmlinuz")
if [ "$ST_K_ACT_SIZE" = "$ST_K_SIZE" ]; then
  st_ok "vmlinuz 长度与 footer 一致 ($ST_K_ACT_SIZE B)"
else
  st_bad "vmlinuz 长度不一致: $ST_K_ACT_SIZE != $ST_K_SIZE"
fi
printf '       | sha256sum vmlinuz  = %s\n' "$ST_K_ACT"
printf '       | sha256sum initrd   = %s\n' "$ST_I_ACT"
if [ -f "$ST_EXT/vmlinuz" ]; then
  printf '       | file(1): %s\n' "$(file -b "$ST_EXT/vmlinuz" 2>/dev/null || echo n/a)"
fi

# ================================================================ 6. dry-run argv 核对
st_step "6/10 pc-run.sh --dry-run 逐项核对 QEMU 参数（DESIGN §11.4 清单）"
ST_RUN=$ST_DIR/run
ST_DRY=$ST_DIR/argv.txt
if IMGBOOT_EXTRACT_DIR="$ST_EXT" "$ST_SELF/pc-run.sh" --dry-run --run-dir "$ST_RUN" \
    "$ST_FIX" >"$ST_DRY" 2>"$ST_DIR/argv.err"; then
  st_ok "dry-run 退出 0"
else
  st_bad "dry-run 失败"; sed 's/^/       | /' "$ST_DIR/argv.err"
fi
printf '       | %s\n' "$(cat "$ST_DRY")"
if [ -e "$ST_RUN" ]; then
  st_bad "dry-run 不应产生副作用（$ST_RUN 被创建）"
else
  st_ok "dry-run 零副作用（未创建 $ST_RUN）"
fi
[ -s "$ST_DIR/argv.err" ] && printf '       | stderr: %s\n' "$(cat "$ST_DIR/argv.err")"

st_argv_has() {
  if grep -Fq -- "$1" "$ST_DRY"; then
    st_ok "argv 含: $1"
  else
    st_bad "argv 缺: $1"
  fi
}
while IFS= read -r _tok; do
  [ -n "$_tok" ] || continue
  st_argv_has "$_tok"
done <<ST_ARGV_EOF
qemu-system-aarch64 '-M' 'virt,gic-version=3'
'-cpu' 'max'
'-accel' 'tcg,thread=multi'
'-display' 'none'
'-serial' 'mon:stdio'
'-smp' '4'
'-m' '4096'
'-kernel' '$ST_EXT/vmlinuz'
'-initrd' '$ST_EXT/initrd.img'
'-append' 'console=ttyAMA0 mitigations=off androidip=10.0.2.15 podroid.x11.dpi=96'
'-drive' 'file=$ST_RUN/storage.img,if=none,id=drive1,format=raw,discard=unmap,detect-zeroes=unmap'
'-device' 'virtio-blk-pci,drive=drive1'
'-drive' 'file=$ST_FIX,if=none,id=drive2,format=raw,readonly=on'
'-device' 'virtio-blk-pci,drive=drive2'
'-netdev' 'user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:9922-:22,hostfwd=tcp:127.0.0.1:5900-:5900,hostfwd=tcp:127.0.0.1:4713-:4713'
'-device' 'virtio-net-pci,netdev=net0'
'-device' 'virtio-serial-pci'
'-chardev' 'socket,id=ch0,path=$ST_RUN/terminal.sock,server=on,wait=off'
'-device' 'virtconsole,chardev=ch0'
'-chardev' 'socket,id=ch1,path=$ST_RUN/ctrl.sock,server=on,wait=off'
'-device' 'virtconsole,chardev=ch1'
'-chardev' 'socket,id=ch2,path=$ST_RUN/host.sock,server=on,wait=off'
'-device' 'virtconsole,chardev=ch2'
ST_ARGV_EOF

# ---- CLI 行为 ----
if "$ST_SELF/pc-run.sh" --help >/dev/null 2>&1; then
  st_ok "--help 退出 0"
else
  st_bad "--help 非 0"
fi
if "$ST_SELF/pc-run.sh" >/dev/null 2>&1; then
  st_bad "缺镜像参数应退出非 0"
else
  st_ok "缺镜像参数 → 退出非 0（用法提示）"
fi
if "$ST_SELF/pc-run.sh" --no-such-flag "$ST_FIX" >/dev/null 2>&1; then
  st_bad "未知选项应退出非 0"
else
  st_ok "未知选项 → 退出非 0"
fi

# ---- socket 路径长度限制（sun_path ≤ 107） ----
ST_LONG=$ST_RUN/$(python3 -c 'print("d" * 96)')
st_expect_fail "chardev socket 路径 >107 字节 → 拒绝并给指引" \
  "IMGBOOT_RUN_DIR='$ST_LONG' imgboot_qemu_argv '$ST_FIX' '$ST_DIR/storage.img'" "超长"
st_expect_fail "路径含逗号（QEMU 选项截断）→ 拒绝" \
  "imgboot_qemu_argv '$ST_FIX' '$ST_RUN/has,comma.img'" "逗号"

# ================================================================ 7. storage
st_step "7/10 imgboot_storage_init → ext4"
ST_STO=$ST_DIR/storage.img
st_check "imgboot_storage_init ($ST_STO, 4G)" "imgboot_storage_init '$ST_STO' 4"
if dumpe2fs -h "$ST_STO" >"$ST_DIR/dumpe2fs.txt" 2>&1; then
  grep -E '^(Filesystem features|Filesystem OS type|Block size|Filesystem size)' \
    "$ST_DIR/dumpe2fs.txt" | sed 's/^/       | /'
  if grep -q '^Filesystem features:' "$ST_DIR/dumpe2fs.txt" \
      && grep '^Filesystem features:' "$ST_DIR/dumpe2fs.txt" | grep -qw extent; then
    st_ok "dumpe2fs 确认为 ext4（含 extent 等 ext4 特性）"
  else
    st_bad "dumpe2fs 未见 ext4 特征"; st_dump
  fi
else
  st_bad "dumpe2fs 读取失败"; st_dump
fi
st_check "再次调用 → 幂等复用" "imgboot_storage_init '$ST_STO' 4"
if grep -q 'reusing existing storage' "$ST_LOG"; then
  st_ok "已存在时跳过 mkfs（reusing）"
else
  st_bad "已存在时未复用"; st_dump
fi
st_expect_fail "非法容量 → 拒绝" "imgboot_storage_init '$ST_DIR/x.img' abc" "非法容量"

# ================================================================ 8. Ready! 等待
st_step "8/10 imgboot_wait_ready"
ST_CON=$ST_DIR/console-wait.log
printf 'Loading kernel modules...\nNetwork found\nStarting SSH...\nReady!\n' >"$ST_CON"
st_check "命中 Ready! → 退出 0" "imgboot_wait_ready '$ST_CON' 10"
ST_EMPTY=$ST_DIR/console-empty.log
: >"$ST_EMPTY"
st_expect_fail "2s 内无 Ready! → 退出 1" "imgboot_wait_ready '$ST_EMPTY' 2" "超时"
printf 'Ready!\n' >"$ST_CON"
st_check "带 <pid> 参数（活进程）" "imgboot_wait_ready '$ST_CON' 10 $$"
st_expect_fail "带 <pid> 参数（进程已死）→ 退出 1" \
  "imgboot_wait_ready '$ST_EMPTY' 10 99999999" "已先退出"

# ================================================================ 9. smoke 管线
st_step "9/10 pc-run.sh --smoke 全管线（假内核 → 预期超时，且无 qemu 残留）"
ST_SMOKE=$ST_DIR/smoke
if "$ST_SELF/pc-run.sh" --smoke --timeout 3 --run-dir "$ST_SMOKE" "$ST_FIX" \
    >"$ST_DIR/smoke.out" 2>&1; then
  st_bad "假内核 smoke 理应失败（未达 Ready!）却退出 0"
  sed 's/^/       | /' "$ST_DIR/smoke.out"
else
  st_ok "假内核 smoke → 退出非 0（预期超时）"
fi
grep -E 'verifying footer|extracting kernel|creating storage|qemu started|waiting for|stopping qemu|smoke finished' \
  "$ST_DIR/smoke.out" | sed 's/^/       | /'
if pgrep -f 'qemu-system-aarch64 -M' >/dev/null 2>&1; then
  st_bad "smoke 结束后仍有 qemu 残留（pid 指向中间 shell 的回收 bug）"
else
  st_ok "smoke 结束后无 qemu 残留（后台 pid 即 qemu 本体）"
fi
if ls "$ST_SMOKE"/*.sock >/dev/null 2>&1; then
  st_bad "smoke 结束后残留 chardev socket"
else
  st_ok "smoke 结束后 chardev socket 已由 QEMU 自动清理"
fi

# ---- 端口占用预检（9922 被占 → 启动前拦截，不启 qemu）----
python3 -c 'import socket, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", 9922))
s.listen(1)
time.sleep(30)' &
ST_DUMMY=$!
sleep 1
if "$ST_SELF/pc-run.sh" --smoke --timeout 3 --run-dir "$ST_DIR/smoke2" "$ST_FIX" \
    >"$ST_DIR/smoke2.out" 2>&1; then
  st_bad "端口 9922 被占时 smoke 理应失败"
else
  if grep -q 'preflight FAIL' "$ST_DIR/smoke2.out"; then
    st_ok "端口占用 → 启动前拦截并给指引（不浪费 30s 起 qemu）"
    grep -E 'preflight FAIL|指引' "$ST_DIR/smoke2.out" | sed 's/^/       | /'
  else
    st_bad "端口占用未见 preflight 诊断"; sed 's/^/       | /' "$ST_DIR/smoke2.out"
  fi
fi
if pgrep -f 'qemu-system-aarch64 -M' >/dev/null 2>&1; then
  st_bad "端口预检失败却仍启动了 qemu"
else
  st_ok "端口预检失败时未启动 qemu"
fi
kill "$ST_DUMMY" 2>/dev/null || true
wait "$ST_DUMMY" 2>/dev/null || true

# ================================================================ 10. 真机冒烟可用性
st_step "10/10 真实 kernel/initrd 可用性 → 是否可跑 --smoke"
ST_K_REAL=/media/ltbkq/mydata/Podroid-Debian/out/vmlinuz-virt
ST_I_REAL=/media/ltbkq/mydata/Podroid-Debian/out/initrd.img
if [ -f "$ST_K_REAL" ] && [ -f "$ST_I_REAL" ]; then
  st_ok "发现真实固件: $ST_K_REAL / $ST_I_REAL"
  printf '       | 下一步（需真实镜像）：tools/pc-run.sh --smoke --timeout 90 <img>\n'
else
  printf '  SKIP 未发现真实内核（%s）→ fixture 的 kernel/initrd 为假 payload，不可启动\n' "$ST_K_REAL"
  printf '       | 结论：缺真实内核，真机冒烟待 R-16 M0 spike\n'
fi
printf '       | qemu: %s\n' "$(qemu-system-aarch64 --version 2>/dev/null | head -n 1 || echo '缺失')"

# ================================================================ 汇总
ST_STEP="summary"
printf '\n================ selftest summary ================\n'
printf '  pass: %s\n  fail: %s\n' "$st_pass" "$st_fail"
printf '  fixture : %s\n  run dir : %s\n' "$ST_FIX" "$ST_DIR"
if [ "$st_fail" -eq 0 ]; then
  printf '  RESULT  : OK\n'
  exit 0
fi
printf '  RESULT  : FAILED\n'
exit 1
