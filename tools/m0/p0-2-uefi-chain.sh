#!/usr/bin/env bash
# M0 spike P0-2 —— v2 引导链实测（设计文档 DESIGN-disk-image-v2.md §11 P0-2）
#
# 目的：验证 "AAVMF UEFI -> GRUB(ESP) -> arm64 kernel" 链条在 QEMU virt 上可通，
#       并量化相对 v1 "-kernel/-initrd 直启" 的启动时延增量（目标 ≤ +3s 到内核接管）。
#
# 范围/偏差（刻意，见 docs/spikes/M0-runbook.md）：
#   * 不做 meta 分区内容（codec 不参与本 spike）
#   * grub.cfg 加了 echo 标记行以便打点（正式格式模板见 IMAGE-FORMAT-v2 §2.4）
#   * 用现成 v1 initrd：它会在 initramfs 内 mount squashfs 失败而 FATAL —— 这不影响
#     P0-2 的测量目标（到内核接管为止），反而顺带验证 initramfs 确实被加载。
#
# 输出：$WORK/*.ts.log（带时间戳串口日志）、$WORK/p0-2-results.md（结果表）
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
WORK=${M0_WORK:-/tmp/opencode/m0-p02}
CACHE="$HERE/cache"
PAYLOAD=${M0_PAYLOAD:-/media/ltbkq/mydata/distro-build/payload}
KERNEL_SRC=${M0_KERNEL:-$PAYLOAD/vmlinuz}      # 可覆盖：默认 v1 payload（无 EFI stub），
                                                # 用 M0_KERNEL=<带 stub 的 vmlinuz> 复测全链
INITRD_SRC=${M0_INITRD:-$PAYLOAD/initrd.img}
AAVMF_CODE=${AAVMF_CODE:-/usr/share/AAVMF/AAVMF_CODE.fd}
AAVMF_VARS_SRC=${AAVMF_VARS_SRC:-/usr/share/AAVMF/AAVMF_VARS.fd}
BOOT_TIMEOUT=${M0_TIMEOUT:-120}
S_MIB=${M0_ROOT_MIB:-2048}          # 根分区档位（spike 用 2 GiB）

mkdir -p "$WORK" "$CACHE"
log() { printf '[p0-2] %s\n' "$*"; }

# ── 0. 前置检查 ────────────────────────────────────────────────────────────
for b in qemu-system-aarch64 grub-mkimage mkfs.vfat mcopy sfdisk sgdisk dd zstd dpkg-deb curl python3; do
  command -v "$b" >/dev/null || { echo "MISSING: $b" >&2; exit 3; }
done
[ -f "$AAVMF_CODE" ] || { echo "MISSING: $AAVMF_CODE (qemu-efi-aarch64)" >&2; exit 3; }
[ -f "$KERNEL_SRC" ] && [ -f "$INITRD_SRC" ] || { echo "MISSING kernel/initrd: $KERNEL_SRC / $INITRD_SRC" >&2; exit 3; }

# ── 1. 取得 arm64-efi GRUB 模块（本机未装 grub-efi-arm64-bin；免 root 解 .deb） ──
GRUB_A64="$CACHE/grub-arm64/usr/lib/grub/arm64-efi"
if [ ! -d "$GRUB_A64" ]; then
  log "fetching grub-efi-arm64-bin (no root needed: dpkg-deb -x)"
  # 注意：2.12 起 arm64 的 GRUB 二进制来自源包 `grub2-unsigned`（不是 `grub2`），
  # 且 arm64 包在 ports 归档。两处都试，取最高版本。
  DEB=""
  for base in \
      "http://archive.ubuntu.com/ubuntu/pool/main/g/grub2-unsigned" \
      "http://archive.ubuntu.com/ubuntu/pool/main/g/grub2" \
      "http://ports.ubuntu.com/ubuntu-ports/pool/main/g/grub2-unsigned" \
      "http://ports.ubuntu.com/ubuntu-ports/pool/main/g/grub2"; do
    DEB=$(curl -s --max-time 30 "$base/" | grep -o 'grub-efi-arm64-bin_[^"]*arm64\.deb' | sort -V | tail -1 || true)
    if [ -n "$DEB" ]; then log "  -> $base/$DEB"; break; fi
  done
  [ -n "$DEB" ] || { echo "cannot locate grub-efi-arm64-bin .deb" >&2; exit 3; }
  url=""
  for base in \
      "http://archive.ubuntu.com/ubuntu/pool/main/g/grub2-unsigned" \
      "http://archive.ubuntu.com/ubuntu/pool/main/g/grub2" \
      "http://ports.ubuntu.com/ubuntu-ports/pool/main/g/grub2-unsigned" \
      "http://ports.ubuntu.com/ubuntu-ports/pool/main/g/grub2"; do
    code=$(curl -s -o "$CACHE/$DEB" -w '%{http_code}' --max-time 60 "$base/$DEB" || true)
    [ "$code" = "200" ] && { url="$base/$DEB"; break; }
  done
  [ -n "$url" ] || { echo "cannot download $DEB" >&2; exit 3; }
  rm -rf "$CACHE/grub-arm64"
  mkdir -p "$CACHE/grub-arm64"
  dpkg-deb -x "$CACHE/$DEB" "$CACHE/grub-arm64"
  [ -d "$GRUB_A64" ] || { echo "extracted .deb has no arm64-efi platform dir" >&2; exit 3; }
fi
ls "$GRUB_A64" | head -3 | sed 's/^/[p0-2]   grub module: /'

# ── 2. 构建最小 v2 布局磁盘（IMAGE-FORMAT-v2 §2，偏差见文件头） ─────────────
IMG="$WORK/spike.img"
ESP_IMG="$WORK/esp.img"
ROOT_IMG="$WORK/rootfs.img"
root_partuuid="4f9c2b7e-1a3d-4e8f-9b21-6c5d8e0f1a23"   # spike 固定值

log "building disk: file_size=$((266+S_MIB))MiB (S=${S_MIB}MiB)"
rm -f "$IMG" "$ESP_IMG" "$ROOT_IMG"
truncate -s "$((266+S_MIB))MiB" "$IMG"
sfdisk -q "$IMG" >/dev/null <<EOF
label: gpt
unit: sectors
first-lba: 34
start=2048,   size=524288,          type=C12A7328-F81F-11D2-BA4B-00A0C93EC93B, name="VMDROID-EFI"
start=526336, size=16384,           type=0FC63DAF-8483-4772-8E79-3D69D8477DE4, name="vmd-meta"
start=542720, size=$((S_MIB*2048)), type=0FC63DAF-8483-4772-8E79-3D69D8477DE4, name="vmd-root"
EOF
sgdisk -q -u 3:"$root_partuuid" "$IMG"   # root 唯一 GUID = grub.cfg 引用值

# ESP：FAT32 + GRUB + kernel/initrd
log "populating ESP (FAT32 256MiB)"
rm -rf "$WORK/esp"; mkdir -p "$WORK/esp/EFI/BOOT" "$WORK/esp/grub" "$WORK/esp/vmdroid"
grub-mkimage -O arm64-efi -d "$GRUB_A64" \
  -o "$WORK/esp/EFI/BOOT/BOOTAA64.EFI" -p '(hd0,gpt1)/grub' \
  fat part_gpt normal boot linux gzio search search_fs_file search_fs_uuid configfile echo test
# 模块选择教训（P0-2 F1b）：① `gzio` 必打 —— vmlinuz 是 gzip(Image.gz)，缺了它 GRUB 把
# 压缩流当裸 Image 解析，报 "plain image kernel not supported"（误导性报错）；
# ② arm64 模块版本必须与宿主 grub-mkimage 版本一致（2.12 + 2.14 模块 = 未爆的隐患）。
cat > "$WORK/esp/grub/grub.cfg" <<EOF
set timeout=0
set default=0
echo "[grub] VMDroid spike: loading kernel"
linux  /vmlinuz console=ttyAMA0 mitigations=off vmd.root_partuuid=$root_partuuid
echo "[grub] loading initrd"
initrd /initrd.img
boot
EOF
# ESP 内核形态：默认 gzip(Image.gz)；M0_RAW_IMAGE=1 时存未压缩 Image（缓解手段 M-a，
# 免去 GRUB 端 gzio inflate —— P0-2 实测该解压占 ~5.4s 链路中的大头）
if [ "${M0_RAW_IMAGE:-0}" = "1" ]; then
  log "ESP kernel = UNCOMPRESSED Image (mitigation M-a)"
  gunzip -c "$KERNEL_SRC" > "$WORK/esp/vmlinuz"
else
  cp "$KERNEL_SRC" "$WORK/esp/vmlinuz"
fi
cp "$INITRD_SRC" "$WORK/esp/initrd.img"
mkfs.vfat -C -F 32 "$ESP_IMG" $((256*1024)) >/dev/null
mcopy -i "$ESP_IMG" -s "$WORK/esp/EFI" "$WORK/esp/grub" "$WORK/esp/vmlinuz" "$WORK/esp/initrd.img" ::/
dd if="$ESP_IMG" of="$IMG" bs=1M seek=1 conv=notrunc status=none

# root：空 ext4（GRUB 不读它；本 spike 不测 codec/根内容）
log "building empty ext4 root (${S_MIB}MiB)"
truncate -s "${S_MIB}MiB" "$ROOT_IMG"
mke2fs -F -q -t ext4 -b 4096 -L vmdroid-root "$ROOT_IMG"
dd if="$ROOT_IMG" of="$IMG" bs=1M seek=265 conv=notrunc status=none

log "partition table:"
sfdisk -l "$IMG" | sed 's/^/[p0-2]   /'

# ── 3. 时间戳串口采集器（自行拉起 qemu，带超时，无管道杀进程问题） ──────────
cat > "$WORK/stamp.py" <<'PY'
import select, subprocess, sys, time
timeout = float(sys.argv[1]); cmd = sys.argv[2:]
t0 = time.time()
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
buf = b""
alive = True
while alive and time.time() - t0 < timeout:
    r, _, _ = select.select([p.stdout], [], [], 0.5)
    if not r:
        continue
    ch = p.stdout.read(1)
    if not ch:
        alive = False
        break
    if ch in (b"\n", b"\r"):
        if buf:
            print("[%8.3fs] %s" % (time.time() - t0, buf.decode("utf-8", "replace")), flush=True)
            buf = b""
    else:
        buf += ch
if buf:
    print("[%8.3fs] %s" % (time.time() - t0, buf.decode("utf-8", "replace")), flush=True)
if p.poll() is None:
    print("[%8.3fs] == TIMEOUT %.0fs, qemu killed ==" % (time.time() - t0, timeout), flush=True)
    p.kill()
p.wait()
PY

run_case() {   # $1=name  $2..=qemu args
  local name=$1; shift
  local logf="$WORK/$name.ts.log"
  log "boot case '$name' (timeout ${BOOT_TIMEOUT}s)"
  cp -f "$AAVMF_VARS_SRC" "$WORK/vars-$name.fd"
  python3 "$WORK/stamp.py" "$BOOT_TIMEOUT" qemu-system-aarch64 "$@" \
    -display none -serial stdio -monitor none > "$logf" 2>"$WORK/$name.err" || true
}

MARK='Linux version|Booting Linux|\[grub\]|EDK2|UEFI|BdsDxe|podroid-init|FATAL'
first_ts() {  # $1=log $2=ERE  -> 首个匹配行的时间戳（无则 "n/a"）
  [ -f "$1" ] || { echo n/a; return; }
  grep -E "$2" "$1" | head -1 | sed -E 's/^\[ *([0-9.]+)s\].*/\1/' || echo n/a
}

COMMON=(-M virt -cpu max -smp 2 -m 2048)

# case A: v2 引导链（UEFI + GRUB + ESP kernel/initrd + 磁盘）
# 固件路径可切：M0_FW=pflash（默认，AAVMF_CODE/VARS 双 pflash）| M0_FW=bios（QEMU_EFI.fd
# 单文件 `-bios`，无变量写入）—— 同时服务 P0-5（固件资产体积）A/B
if [ "${M0_FW:-pflash}" = "bios" ]; then
  log "firmware path = -bios QEMU_EFI.fd (single file)"
  FW_ARGS=(-bios /usr/share/qemu-efi-aarch64/QEMU_EFI.fd)
else
  FW_ARGS=(-drive "if=pflash,format=raw,unit=0,file=$AAVMF_CODE,readonly=on"
           -drive "if=pflash,format=raw,unit=1,file=$WORK/vars-uefi.fd")
fi
run_case uefi "${COMMON[@]}" "${FW_ARGS[@]}" \
  -drive if=virtio,format=raw,file="$IMG"

# case B: v1 基线（直启，无固件、无磁盘）
run_case direct "${COMMON[@]}" \
  -kernel "$KERNEL_SRC" -initrd "$INITRD_SRC" \
  -append "console=ttyAMA0 mitigations=off"

# ── 4. 结果表 ──────────────────────────────────────────────────────────────
t_uefi_grub=$(first_ts "$WORK/uefi.ts.log" '\[grub\]')
t_uefi_krn=$(first_ts "$WORK/uefi.ts.log" 'Booting Linux on physical|Linux version')
t_dir_krn=$(first_ts "$WORK/direct.ts.log" 'Booting Linux on physical|Linux version')
t_uefi_init=$(first_ts "$WORK/uefi.ts.log" 'podroid-init|FATAL')
t_dir_init=$(first_ts "$WORK/direct.ts.log" 'podroid-init|FATAL')

fmt() { if [ "$1" = "n/a" ] || [ -z "$1" ]; then echo "n/a"; else echo "${1}s"; fi; }
{
  echo "# P0-2 结果（$(date -Is)，$(qemu-system-aarch64 --version | head -1)）"
  echo
  echo "kernel = \`$KERNEL_SRC\`"
  echo
  echo "| 里程碑 | UEFI+GRUB 链 | v1 直启基线 | 差值 |"
  echo "|---|---|---|---|"
  echo "| GRUB 开始加载内核 | $(fmt "$t_uefi_grub") | —（无 GRUB） | — |"
  echo "| 内核接管（Booting Linux/Linux version） | $(fmt "$t_uefi_krn") | $(fmt "$t_dir_krn") | $( [ "$t_uefi_krn" != n/a ] && [ "$t_dir_krn" != n/a ] && awk -v a="$t_uefi_krn" -v b="$t_dir_krn" 'BEGIN{printf "%+.3fs", a-b}' || echo n/a ) |"
  echo "| initramfs 执行（podroid-init/FATAL） | $(fmt "$t_uefi_init") | $(fmt "$t_dir_init") | $( [ "$t_uefi_init" != n/a ] && [ "$t_dir_init" != n/a ] && awk -v a="$t_uefi_init" -v b="$t_dir_init" 'BEGIN{printf "%+.3fs", a-b}' || echo n/a ) |"
  echo
  echo "判定目标：内核接管时延增量 ≤ +3s（设计文档 §11 P0-2）。"
  echo
  echo "日志：\`$WORK/uefi.ts.log\`、\`$WORK/direct.ts.log\`"
} | tee "$WORK/p0-2-results.md"

log "done. results -> $WORK/p0-2-results.md"
