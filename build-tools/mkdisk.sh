#!/usr/bin/env bash
#
# tools/mkdisk.sh — pack / verify a VMDroid **v2 full-disk** system image (.img)
#
# Spec (draft, pending freeze): IMAGE-FORMAT-v2.md §2 (layout) §3 (meta) §4 (manifest)
#                               §6 (generation algorithm) · DESIGN-disk-image-v2.md §8.1
#
#   build : tools/mkdisk.sh --rootfs-dir <dir> --manifest <json> \
#                           --kernel <Image> --initrd <img> \
#                           [--grub-efi <BOOTAA64.EFI> | --grub-platform <arm64-efi dir>] \
#                           [--root-size 2048MiB] [--zstd] [--smoke|--no-smoke] \
#                           -o <out.img>
#   verify: tools/mkdisk.sh --verify <out.img> [--full]
#
# Layout written (IMAGE-FORMAT-v2 §2.1, sector=512, 1MiB aligned):
#   LBA0 protective MBR · LBA1 GPT header · LBA2..33 entries · LBA34..2047 gap
#   p1 ESP    [2048, 526335]      FAT32 256MiB  (/EFI/BOOT/BOOTAA64.EFI /grub /vmlinuz
#                                                  /initrd.img /vmdroid/manifest.json)
#   p2 meta   [526336, 542719]    raw    8MiB    (VMDIMG02 + manifest + root_sha256)
#   p3 root   [542720, ...]       ext4   S        ← **必须是最后一个分区**（扩容不搬数据）
#   tail 1MiB + backup GPT;  file_size = 266MiB + S
#
# python3 is a documented dependency (same as v1 mkimg.sh): all binary packing,
# hashing and read-back checking live in the embedded programs below, and the
# verifier is invoked twice (once for --verify, once as the build self-check),
# so build and verify share exactly one parser (IMAGE-FORMAT-v2 §6 step 6/7).
#
set -euo pipefail

TOOLS_DIR=$(cd "$(dirname "$0")" && pwd)
SCRIPT_NAME=$(basename "$0")

die() { echo "$SCRIPT_NAME: ERROR: $*" >&2; exit 1; }
warn() { echo "$SCRIPT_NAME: warn: $*" >&2; }
log()  { echo "$SCRIPT_NAME: $*"; }

# ---- IMAGE-FORMAT-v2 §2 constants (frozen) ----------------------------------
SECTOR=512
ESP_START_LBA=2048           # 1 MiB
ESP_SECTORS=$((256 * 2048))   # 256 MiB
META_START_LBA=$((ESP_START_LBA + ESP_SECTORS))     # 526336 (257 MiB)
META_SECTORS=$((8 * 2048))                           # 8 MiB
ROOT_START_LBA=$((META_START_LBA + META_SECTORS))   # 542720 (265 MiB)
META_GUID="564d444d-4554-4132-8000-000000000001"    # fixed, ASCII "VMDMETA2"
META_SIZE=8192
META_OFF=$((META_START_LBA * SECTOR))
ESP_OFF=$((ESP_START_LBA * SECTOR))
ROOT_OFF=$((ROOT_START_LBA * SECTOR))
EFI_PART_TYPE="C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
LINUX_FS_TYPE="0FC63DAF-8483-4772-8E79-3D69D8477DE4"
# §2.3 frozen GRUB module list (gzio: initrd gzip cpio 解压必需, P0-2 F1b)
GRUB_MODULES="fat part_gpt normal boot linux gzio search search_fs_file search_fs_uuid configfile echo test"

usage() {
  cat <<EOF
usage:
  $SCRIPT_NAME --rootfs-dir DIR --manifest FILE --kernel FILE --initrd FILE
                [--grub-efi FILE | --grub-platform DIR]
                [--root-size 2048MiB] [--zstd] [--smoke|--no-smoke] -o OUT
  $SCRIPT_NAME --verify OUT [--full]

  --root-size   档位 [2048|4096|8192|16384|32768]MiB（默认 4096MiB）
  --zstd        额外产出 OUT.zst（zstd -19，catalog 分发容器）
  --full        verify 时重算 root 分区 sha256（慢，≈ 读 S 字节）
  --smoke/--no-smoke  引导冒烟（需 M2 的 tools/pc-boot-smoke-v2.sh；缺则 warn/skip, IMP-C03）
EOF
  exit 2
}

# ---- args --------------------------------------------------------------------
MODE=""; ROOTFS_DIR=""; MANIFEST=""; KERNEL=""; INITRD=""
GRUB_EFI=""; GRUB_PLATFORM=""; ROOT_SIZE="4096MiB"; OUT=""
ZSTD=0; SMOKE="auto"; FULL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --rootfs-dir)   ROOTFS_DIR=${2:?}; shift 2;;
    --manifest)     MANIFEST=${2:?}; shift 2;;
    --kernel)       KERNEL=${2:?}; shift 2;;
    --initrd)       INITRD=${2:?}; shift 2;;
    --grub-efi)     GRUB_EFI=${2:?}; shift 2;;
    --grub-platform) GRUB_PLATFORM=${2:?}; shift 2;;
    --root-size)    ROOT_SIZE=${2:?}; shift 2;;
    --zstd)         ZSTD=1; shift;;
    --smoke)        SMOKE=1; shift;;
    --no-smoke)     SMOKE=0; shift;;
    --verify)       MODE=verify
                    if [ $# -ge 2 ] && [ "${2#-}" = "$2" ]; then OUT=$2; shift 2; else shift; fi;;
    --full)         FULL=1; shift;;
    -o)             MODE=${MODE:-build}; OUT=${2:?}; shift 2;;
    -*)             usage;;
    *)              if [ "$MODE" = verify ] && [ -z "$OUT" ]; then OUT=$1; shift; else usage; fi;;
  esac
done
[ -n "$MODE" ] || usage
command -v python3 >/dev/null || die "python3 required"

# =========================================================================
# verify —— 单一解析器（IMAGE-FORMAT-v2 §5 读取算法；build 自检复用）
# =========================================================================
verify_img() {
  local img=$1 full=$2
  python3 - "$img" "$full" <<'PY'
import binascii, hashlib, json, os, re, struct, sys, uuid as uuidmod

img, full = sys.argv[1], sys.argv[2] == "1"
META_UUID = uuidmod.UUID("564d444d-4554-4132-8000-000000000001")

def fail(stage, msg):
    print("FAIL stage=%s reason=%s" % (stage, msg))
    sys.exit(1)

def rd(f, off, n):
    f.seek(off); b = f.read(n)
    if len(b) != n: fail("io", "short read @%d want %d got %d" % (off, n, len(b)))
    return b

size = os.path.getsize(img)
if size < 64 * 1024: fail("ladder", "too small (%d)" % size)
f = open(img, "rb")

# ---- stage 1: protective MBR + GPT -----------------------------------------
mbr = rd(f, 0, 512)
if mbr[510:512] != b"\x55\xaa" or mbr[450] != 0xEE:
    # 诊断阶梯（IMP-D02 v2 版）：先判 v1 家族
    if size >= 4096:
        f.seek(size - 4096); tail = f.read(8)
        if tail == b"VMDIMG01":
            fail("ladder", "LEGACY_FORMAT: v1 footer (VMDIMG01) — 旧格式，请重新获取镜像")
    f.seek(0)
    if f.read(4) == b"hsqs":
        fail("ladder", "LEGACY_FORMAT: 裸 squashfs (hsqs) — 旧格式，请重新获取镜像")
    fail("ladder", "NOT_AN_IMAGE: 无保护性 MBR")

hdr = rd(f, 512, 92)
if hdr[0:8] != b"EFI PART": fail("gpt", "header signature")
(hsize, hcrc) = struct.unpack_from("<II", hdr, 12)
if hsize != 92: fail("gpt", "header size %d" % hsize)
if binascii.crc32(hdr[:16] + b"\x00\x00\x00\x00" + hdr[20:92]) & 0xFFFFFFFF != hcrc:
    fail("gpt", "header crc32")
cur_lba, backup_lba, first_usable, last_usable = struct.unpack_from("<QQQQ", hdr, 24)
disk_guid = hdr[56:72]
ent_lba = struct.unpack_from("<Q", hdr, 72)[0]
n_ent, ent_sz = struct.unpack_from("<II", hdr, 80)
ent_crc = struct.unpack_from("<I", hdr, 88)[0]
last_lba = size // 512 - 1
if cur_lba != 1: fail("gpt", "current LBA %d" % cur_lba)
if backup_lba != last_lba: fail("gpt", "backup LBA %d != %d (截断/追加?)" % (backup_lba, last_lba))
if first_usable != 34 or last_usable != last_lba - 33: fail("gpt", "usable range")
if n_ent != 128 or ent_sz != 128: fail("gpt", "entries geometry %d/%d" % (n_ent, ent_sz))
ents = rd(f, ent_lba * 512, n_ent * ent_sz)
if binascii.crc32(ents) & 0xFFFFFFFF != ent_crc: fail("gpt", "entries crc32")
# 备份表项也要在
bents = rd(f, (last_lba - 32) * 512, n_ent * ent_sz)
if bents != ents: fail("gpt", "backup entries differ")

parts = []
for i in range(n_ent):
    e = ents[i*128:(i+1)*128]
    tguid = e[0:16]
    if tguid == b"\x00" * 16: continue
    uguid = uuidmod.UUID(bytes_le=e[16:32])   # GPT 表项 GUID 是 mixed-endian
    first, last = struct.unpack_from("<QQ", e, 32)
    name = e[56:128].decode("utf-16-le").split("\x00", 1)[0]
    parts.append(dict(idx=i+1, type=tguid, uuid=uguid, first=first, last=last, name=name))
parts.sort(key=lambda p: p["first"])
for a, b in zip(parts, parts[1:]):
    if b["first"] <= a["last"]: fail("gpt", "分区重叠/乱序 %d,%d" % (a["idx"], b["idx"]))
for p in parts:
    if p["first"] < 34 or p["last"] > last_lba - 33: fail("gpt", "分区越界 #%d" % p["idx"])

meta = next((p for p in parts if p["uuid"] == META_UUID), None)
if meta is None: fail("gpt", "缺 meta 分区（固定 GUID 564D444D-…）")
root_l = [p for p in parts if p["name"] == "vmd-root"]
if len(root_l) != 1: fail("gpt", "vmd-root 分区数=%d" % len(root_l))
root = root_l[0]
esp_l = [p for p in parts if p["name"] == "VMDROID-EFI"]
if len(esp_l) != 1: fail("gpt", "缺/多 ESP 分区（name=VMDROID-EFI）")
esp = esp_l[0]
if root is not parts[-1]: fail("layout", "root 不是最后一个分区（扩容约束 R-01）")

# ---- stage 2: meta 分区 ------------------------------------------------------
moff = meta["first"] * 512
mh = rd(f, moff, 140)
if mh[0:8] != b"VMDIMG02": fail("meta", "magic %r" % mh[0:8])
fmt_ver, meta_size = struct.unpack_from("<II", mh, 8)
file_size = struct.unpack_from("<Q", mh, 16)[0]
m_man_off, m_man_size = struct.unpack_from("<QQ", mh, 24)
m_man_sha = mh[40:72]
root_off, root_size = struct.unpack_from("<QQ", mh, 72)
root_sha = mh[88:120]
esp_off, esp_size = struct.unpack_from("<QQ", mh, 120)
flags = struct.unpack_from("<I", mh, 136)[0]
if meta_size < 148 or meta_size > (meta["last"]-meta["first"]+1)*512:
    fail("meta", "meta_size %d" % meta_size)
tail = rd(f, moff + meta_size - 8, 8)
if tail != b"VMDIMG02": fail("meta", "magic_tail")
if fmt_ver > 2: fail("meta", "FORMAT_UNSUPPORTED: format_version=%d" % fmt_ver)
if file_size != size: fail("meta", "file_size %d != actual %d (截断/追加)" % (file_size, size))
if flags != 0x3: fail("meta", "flags 0x%x (合法掩码 0x3 且 v2.0 必须全置)" % flags)
if not (1 <= m_man_size <= meta_size - 148): fail("meta", "manifest_size %d" % m_man_size)
if m_man_off < 140 or m_man_off + m_man_size > meta_size - 8: fail("meta", "manifest 越界")
if root_off != root["first"] * 512: fail("layout", "root_offset 与分区表不符")
if root_size != (root["last"] - root["first"] + 1) * 512: fail("layout", "root_size 与分区表不符")
if esp_off != esp["first"] * 512 or esp_size != (esp["last"] - esp["first"] + 1) * 512:
    fail("layout", "esp range 与分区表不符")

# ---- stage 3: manifest --------------------------------------------------------
man = rd(f, moff + m_man_off, m_man_size)
if hashlib.sha256(man).digest() != m_man_sha:
    fail("manifest", "manifest_sha256 不符")
try:
    doc = json.loads(man.decode("utf-8"))
except Exception as e:
    fail("manifest", "json: %s" % e)
if doc.get("format") != "vmdroid-system-image": fail("manifest", "format")
if doc.get("format_version") != 2: fail("manifest", "format_version=%r" % doc.get("format_version"))
img_sec = doc.get("image") or {}
req = ["id","display_name","identity","variant","version","system_version","arch","content_id"]
miss = [k for k in req if k not in img_sec]
if miss: fail("manifest", "image 缺必需字段 %s" % miss)
if not re.match(r"^[a-z0-9][a-z0-9._-]{0,63}$", str(img_sec.get("id",""))):
    fail("manifest", "image.id 非法（防 -drive 注入）")
if img_sec.get("arch") != "arm64": fail("manifest", "arch=%r" % img_sec.get("arch"))
if not (doc.get("contract") or {}).get("version") == 2:
    fail("manifest", "contract.version != 2")
if not (doc.get("capabilities") or {}).get("ssh") is True:
    fail("manifest", "capabilities.ssh != true")
acct = doc.get("accounts") or {}
if acct.get("ssh_port", 22) != 22: fail("manifest", "accounts.ssh_port != 22")
if (doc.get("app") or {}).get("min_version_code") is None:
    fail("manifest", "app.min_version_code 缺失")
boot = doc.get("boot") or {}
if boot.get("mode") != "uefi": fail("manifest", "boot.mode=%r (v2.0 必须 uefi)" % boot.get("mode"))
disk = doc.get("disk") or {}
pu = str(disk.get("root", {}).get("partuuid", "")).lower().replace("-", "")
if pu != root["uuid"].hex:
    fail("manifest", "disk.root.partuuid 与 GPT 表项不符 (%s != %s)" % (pu, root["uuid"].hex))
csum = doc.get("checksums") or {}
if csum.get("root_sha256") != root_sha.hex():
    fail("manifest", "checksums.root_sha256 != meta.root_sha256")
if csum.get("kernel_sha256") != boot.get("kernel_sha256"):
    fail("manifest", "boot.kernel_sha256 != checksums.kernel_sha256")

# ---- stage 4: full 校验 --------------------------------------------------------
root_bytes = 0
if full:
    h = hashlib.sha256()
    f.seek(root_off)
    left = root_size
    while left:
        chunk = f.read(min(8 << 20, left))
        if not chunk: fail("full", "root 区读短"); break
        h.update(chunk); left -= len(chunk); root_bytes += len(chunk)
    if h.hexdigest() != root_sha.hex():
        fail("full", "root 分区 sha256 不符")

print("VERIFY_OK")
print("file_size=%d" % size)
print("partitions=%s" % " ".join("#%d:%s@%d-%d" % (p["idx"], p["name"], p["first"], p["last"]) for p in parts))
print("root_offset=%d root_size=%d" % (root_off, root_size))
print("root_sha256=%s" % root_sha.hex())
print("manifest_size=%d content_id=%s" % (m_man_size, img_sec.get("content_id")))
if full: print("root_read=%d" % root_bytes)
PY
}

# =========================================================================
# build
# =========================================================================
cmd_build() {
  [ -n "$ROOTFS_DIR" ] && [ -d "$ROOTFS_DIR" ] || die "--rootfs-dir missing"
  [ -n "$MANIFEST" ] && [ -f "$MANIFEST" ] || die "--manifest missing"
  [ -n "$KERNEL" ] && [ -f "$KERNEL" ] || die "--kernel missing"
  [ -n "$INITRD" ] && [ -f "$INITRD" ] || die "--initrd missing"
  [ -n "$OUT" ] || die "-o OUT missing"
  case "$ROOT_SIZE" in
    2048MiB|4096MiB|8192MiB|16384MiB|32768MiB) ;;
    *) die "--root-size 必须是档位 [2048|4096|8192|16384|32768]MiB（got $ROOT_SIZE）";;
  esac
  local S_MIB=${ROOT_SIZE%MiB}
  local S_BYTES=$((S_MIB * 1024 * 1024))
  local FILE_SIZE=$((266 * 1024 * 1024 + S_BYTES))

  for b in sfdisk sgdisk mke2fs mkfs.vfat mcopy dd sha256sum zstd; do
    command -v "$b" >/dev/null || die "missing tool: $b"
  done

  local stage grub_out
  stage=$(mktemp -d "${TMPDIR:-/tmp}/mkdisk.XXXXXX")
  trap "rm -rf '$stage'" EXIT
  mkdir -p "$stage/esp/EFI/BOOT" "$stage/esp/grub" "$stage/esp/vmdroid"
  grub_out="$stage/esp/EFI/BOOT/BOOTAA64.EFI"

  # ---- root partition image -------------------------------------------------
  log "building root ext4 ($ROOT_SIZE) from $ROOTFS_DIR"
  local rootimg="$stage/rootfs.img"
  truncate -s "$S_BYTES" "$rootimg"
  mke2fs -q -F -t ext4 -b 4096 -L vmdroid-root -d "$ROOTFS_DIR" "$rootimg" >/dev/null \
    || die "mke2fs -d failed"
  # 用量断言（§2.5: 用量 ≤ 0.8S）：读 ext4 超级块 free 计数（1024+0x0C u32, 1024+0x18 log bs）
  python3 - "$rootimg" "$S_BYTES" <<'PY' || die "rootfs usage assert failed (用量 > 80% of S, 升档 --root-size)"
import struct, sys
p, total = sys.argv[1], int(sys.argv[2])
sb = open(p, "rb").read(1024 + 0x40)[1024:]
free_blocks = struct.unpack_from("<I", sb, 0x0C)[0]
log_bs = struct.unpack_from("<I", sb, 0x18)[0]
free = free_blocks << (10 + log_bs)
used = total - free
ratio = used / total
print("  usage: used=%d (%.1f%% of %d)" % (used, ratio * 100, total))
sys.exit(0 if ratio <= 0.80 else 1)
PY

  # ---- digests / ESP 内容预置 ------------------------------------------------
  # R6 修订（2026-10-12）：`checksums.kernel_sha256` = **ESP /vmlinuz 实际字节**的摘要
  # （即展开后的 Image）。原语义对 `--kernel` 入参取哈希：入参为 gzip 时 ESP 存展开件 →
  # AVF 回退侧校验必然失败（D 线 codec 冒烟暴露的真缺陷）。入参哈希只作构建期自用。
  # §2.3 M-a：/vmlinuz 存**未压缩** Image（gzip 入参先展开，省 GRUB 端 ~2s）
  if [ "$(od -An -tx1 -N2 "$KERNEL" | tr -d ' \n')" = "1f8b" ]; then
    gunzip -c "$KERNEL" > "$stage/esp/vmlinuz"
  else
    cp "$KERNEL" "$stage/esp/vmlinuz"
  fi
  cp "$INITRD" "$stage/esp/initrd.img"
  local root_sha kernel_sha initrd_sha content_id
  root_sha=$(sha256sum "$rootimg" | cut -d' ' -f1)
  kernel_sha=$(sha256sum "$stage/esp/vmlinuz" | cut -d' ' -f1)
  initrd_sha=$(sha256sum "$stage/esp/initrd.img" | cut -d' ' -f1)
  content_id=$(python3 "$TOOLS_DIR/mkdisk-content-id.py" "$ROOTFS_DIR") \
    || die "content_id failed"

  # ---- root partuuid（manifest 与 GPT 必须一致）------------------------------
  local root_partuuid
  root_partuuid=$(python3 -c 'import uuid;print(uuid.uuid4())')
  # esp_uuid：FAT 卷 ID（mkfs.vfat -i 固定），grub.cfg 用 search --fs-uuid 定位 ESP
  local esp_uuid
  esp_uuid=$(python3 -c 'import uuid;print(uuid.uuid4().hex[:8].upper())')

  # ---- manifest patch（派生字段覆写, IMP-C02 v2 版）--------------------------
  local patched="$stage/manifest.json"
  python3 - "$MANIFEST" "$patched" "$content_id" "$root_partuuid" "$FILE_SIZE" \
            "$root_sha" "$kernel_sha" "$initrd_sha" "$S_BYTES" "$esp_uuid" <<'PY' \
    || die "manifest patch/required-field check failed (IMP-D05 v2)"
import json, sys
(src, dst, content_id, pu, file_size, root_sha, kernel_sha, initrd_sha,
 s_bytes, esp_uuid) = sys.argv[1:11]
doc = json.load(open(src, encoding="utf-8"))
# IMP-D05 v2 生成侧必需字段
req = {
  "image": ["id","display_name","identity","variant","version","system_version","arch"],
  "contract": ["version"], "capabilities": ["ssh"],
  "accounts": ["ssh_port"], "app": ["min_version_code"], "boot": ["mode"],
}
for sect, keys in req.items():
    node = doc.get(sect)
    if not isinstance(node, dict):
        raise SystemExit("manifest: missing section %r" % sect)
    for k in keys:
        if k not in node:
            raise SystemExit("manifest: missing %s.%s" % (sect, k))
doc.setdefault("format", "vmdroid-system-image")
doc["format_version"] = 2
doc["image"]["content_id"] = content_id
doc["disk"] = {
    "table": "gpt", "sector_size": 512, "file_size": file_size,
    "esp":  {"part": 1, "fstype": "fat32", "first_lba": 2048,  "last_lba": 526335,
             "size": 268435456, "fat_uuid": esp_uuid.lower()},
    "meta": {"part": 2, "first_lba": 526336, "last_lba": 542719, "size": 8388608},
    "root": {"part": 3, "fstype": "ext4", "partuuid": pu,
             "first_lba": 542720, "size": s_bytes},
    "kernel_path": "/vmlinuz", "initrd_path": "/initrd.img",
}
doc.setdefault("checksums", {})
doc["checksums"].update(root_sha256=root_sha, kernel_sha256=kernel_sha, initrd_sha256=initrd_sha)
doc.setdefault("boot", {})
doc["boot"]["kernel_sha256"] = kernel_sha
doc["boot"]["initrd_sha256"] = initrd_sha
json.dump(doc, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=2, sort_keys=True)
open(dst, "ab").write(b"\n")
PY
  local man_sha
  man_sha=$(sha256sum "$patched" | cut -d' ' -f1)
  local man_size
  man_size=$(stat -c%s "$patched")
  [ "$man_size" -le $((META_SIZE - 148)) ] || die "manifest too big: $man_size > $((META_SIZE - 148))"

  # ---- grub.cfg / ESP files --------------------------------------------------
  # grub.cfg（M1 打包器教训：① 无 menuentry —— timeout=0 单内核直接顶层执行，最稳；
  # ② search --fs-uuid 的 uuid 必须是 FAT 卷 ID 的 GRUB 形式 "XXXX-XXXX"（带横线，
  #   mkfs.vfat -i 的裸 hex 会让 search 报 "no such device"，只剩 prefix 兜底 ——
  #   多盘（vda 存储 + vdb 镜像）时 prefix (hd0,gpt1) 会指错盘！）
  # ③ **cfg 必须用 grub-mkimage -c 嵌入镜像**：烘焙 prefix (hd0,gpt1) 在双盘拓扑下
  #   指向 vda（无 GPT/ESP）→ GRUB 连外部 cfg 都读不到、直接落 rescue shell（实测）。
  #   嵌入式 cfg 自带 search --fs-uuid，先定位 ESP 再执行，与盘序无关。
  local esp_uuid_grub="${esp_uuid:0:4}-${esp_uuid:4}"
  local grub_cfg_body
  # R8（2026-10-12）：user.cfg 运行时参数链 —— test -f && configfile 执行 ESP 内定长
  # 4KiB 的 /grub/user.cfg（应用停机期写入 `set podroid_params="..."`），linux 行以
  # $podroid_params 收尾（未定义展开为空）。user.cfg 缺失/失败 → 默认参数照常启动
  # （不承载启动命令，区别于完整外部 cfg；见 IMAGE-FORMAT-v2 §2.4 R8）。
  grub_cfg_body="search --fs-uuid $esp_uuid_grub --set=root
set timeout=0
echo \"VMDroid: booting (kernel from ESP)\"
source /grub/user.cfg
linux  /vmlinuz console=ttyAMA0 mitigations=off vmd.root_partuuid=$root_partuuid \$podroid_params
initrd /initrd.img
boot"
  printf '%s\n' "$grub_cfg_body" > "$stage/esp/grub/grub.cfg"
  printf '%s\n' "$grub_cfg_body" > "$stage/embed.cfg"
  # R8 预置 user.cfg：定长 4096 字节，首行魔数 #VMDU2，其余 \n 填充（禁 NUL）。
  { printf '#VMDU2\n'; head -c 4089 /dev/zero | tr '\0' '\n'; } > "$stage/esp/grub/user.cfg"
  [ "$(stat -c%s "$stage/esp/grub/user.cfg")" = 4096 ] || die "user.cfg must be exactly 4096 bytes"

  # ---- BOOTAA64.EFI（cfg 嵌入镜像，自包含；见上方教训③）----------------------
  if [ -n "$GRUB_EFI" ]; then
    warn "--grub-efi 预构建 EFI 无法嵌入 cfg（依赖 ESP /grub/grub.cfg + (hd0,gpt1) prefix）—— 多盘拓扑不鲁棒，仅供调试"
    cp "$GRUB_EFI" "$grub_out"
  else
    local plat=${GRUB_PLATFORM:-${GRUB_ARM64_DIR:-/usr/lib/grub/arm64-efi}}
    [ -d "$plat" ] || die "arm64-efi GRUB platform dir not found ($plat); pass --grub-platform or --grub-efi"
    command -v grub-mkimage >/dev/null || die "grub-mkimage missing (grub2-common)"
    grub-mkimage -O arm64-efi -d "$plat" -o "$grub_out" -p '(hd0,gpt1)/grub' \
      -c "$stage/embed.cfg" $GRUB_MODULES || die "grub-mkimage failed"
  fi
  [ -s "$grub_out" ] || die "BOOTAA64.EFI empty"
  # §2.3 M-a 注：/vmlinuz 与 /initrd.img 已在 digests 段预置（R6：kernel_sha256 对应
  # ESP 实际字节），此处不再重复拷贝。
  cp "$patched" "$stage/esp/vmdroid/manifest.json"
  # ESP 五项文件齐备性（§2.3 生成侧断言）
  for _f in EFI/BOOT/BOOTAA64.EFI grub/grub.cfg vmlinuz initrd.img vmdroid/manifest.json; do
    [ -s "$stage/esp/$_f" ] || die "ESP missing: $_f"
  done

  # ---- image shell + GPT -----------------------------------------------------
  log "layout: file_size=$((FILE_SIZE / 1024 / 1024))MiB (S=$ROOT_SIZE)"
  rm -f "$OUT"; truncate -s "$FILE_SIZE" "$OUT"
  sfdisk -q "$OUT" >/dev/null <<EOF
label: gpt
unit: sectors
first-lba: 34
start=$ESP_START_LBA,  size=$ESP_SECTORS, type=$EFI_PART_TYPE, name="VMDROID-EFI"
start=$META_START_LBA, size=$META_SECTORS, type=$LINUX_FS_TYPE, name="vmd-meta"
start=$ROOT_START_LBA, size=$((S_MIB * 2048)), type=$LINUX_FS_TYPE, name="vmd-root"
EOF
  # 注意：sgdisk -q/--quick 对 GUID 修改会**跳过写回**（gptfdisk 教训，实测于 2.7.0）：
  # 必须不用 -q，并把它的"kernel still using"提示重定向掉。
  sgdisk -u "2:$META_GUID" "$OUT" >/dev/null 2>&1 || die "sgdisk set meta partuuid failed"
  sgdisk -u "3:$root_partuuid" "$OUT" >/dev/null 2>&1 || die "sgdisk set root partuuid failed"

  # ---- payloads ---------------------------------------------------------------
  local esp_img="$stage/esp.img"
  mkfs.vfat -C -F 32 -i "$esp_uuid" "$esp_img" $((256 * 1024)) >/dev/null
  mcopy -i "$esp_img" -s "$stage/esp/EFI" "$stage/esp/grub" \
        "$stage/esp/vmlinuz" "$stage/esp/initrd.img" "$stage/esp/vmdroid" ::/
  dd if="$esp_img"  of="$OUT" bs=1M seek=1   conv=notrunc status=none
  dd if="$rootimg" of="$OUT" bs=1M seek=265 conv=notrunc status=none

  # ---- meta 分区（VMDIMG02）----------------------------------------------------
  python3 - "$OUT" "$patched" "$man_sha" "$root_sha" "$FILE_SIZE" <<'PY' \
    || die "meta pack failed"
import hashlib, os, struct, sys
out, man_path, man_sha, root_sha = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
file_size = int(sys.argv[5])
man = open(man_path, "rb").read()
META_OFF, META_SIZE = 526336 * 512, 8192
ROOT_OFF, ROOT_SIZE = 542720 * 512, None  # size from argv? recompute below
# root size = file_size - 266MiB
ROOT_SIZE = file_size - 266 * 1024 * 1024
buf = bytearray(META_SIZE)
buf[0:8]   = b"VMDIMG02"
struct.pack_into("<I", buf, 8, 2)             # format_version
struct.pack_into("<I", buf, 12, META_SIZE)    # meta_size
struct.pack_into("<Q", buf, 16, file_size)
struct.pack_into("<Q", buf, 24, 140)          # manifest_offset (rel)
struct.pack_into("<Q", buf, 32, len(man))
buf[40:72] = bytes.fromhex(man_sha)
struct.pack_into("<Q", buf, 72, ROOT_OFF)
struct.pack_into("<Q", buf, 80, ROOT_SIZE)
buf[88:120] = bytes.fromhex(root_sha)
struct.pack_into("<Q", buf, 120, 2048 * 512)          # esp_offset (abs)
struct.pack_into("<Q", buf, 128, 256 * 1024 * 1024)   # esp_size
struct.pack_into("<I", buf, 136, 0x3)                 # flags: HAS_KERNEL_ESP|HAS_INITRD_ESP
buf[140:140 + len(man)] = man
buf[META_SIZE - 8:META_SIZE] = b"VMDIMG02"            # magic_tail
with open(out, "r+b") as f:
    f.seek(META_OFF); f.write(buf); os.fsync(f.fileno())
print("  meta: manifest_offset=140 manifest_size=%d root_offset=%d root_size=%d"
      % (len(man), ROOT_OFF, ROOT_SIZE))
PY

  # ---- 自检（§6 步骤 6：与 build 共用的 parser 回读）--------------------------
  log "self-check (IMAGE-FORMAT-v2 §6 step 7 — same parser reads it back)"
  verify_img "$OUT" 1 >/dev/null || die "self-check failed"
  verify_img "$OUT" 0 | sed 's/^/  /'

  # ---- 分发容器 ---------------------------------------------------------------
  local image_sha image_size zst_sha zst_size
  image_size=$(stat -c%s "$OUT")
  image_sha=$(python3 -c "import hashlib,sys
h=hashlib.sha256()
with open(sys.argv[1],'rb') as f:
    for b in iter(lambda: f.read(8<<20), b''): h.update(b)
print(h.hexdigest())" "$OUT")
  if [ "$ZSTD" = 1 ]; then
    log "zstd -19 -> $OUT.zst"
    zstd -19 -T0 -f -q "$OUT" -o "$OUT.zst"
    zst_size=$(stat -c%s "$OUT.zst")
    zst_sha=$(sha256sum "$OUT.zst" | cut -d' ' -f1)
  fi
  echo "== mkdisk build: $OUT"
  echo "  root_sha256=$root_sha"
  echo "  content_id=$content_id"
  echo "  kernel_sha256=$kernel_sha"
  echo "  initrd_sha256=$initrd_sha"
  echo "  manifest_sha256=$man_sha"
  echo "file_size=$image_size"
  echo "image_sha256=$image_sha"
  [ "$ZSTD" = 1 ] && { echo "zst_size=$zst_size"; echo "zst_sha256=$zst_sha"; }
  echo "partuuid_root=$root_partuuid"

  # ---- smoke（IMP-C03 语义：缺工具 warn/skip）---------------------------------
  local run_smoke=$SMOKE
  local smoke="$TOOLS_DIR/pc-boot-smoke-v2.sh"
  if [ "$SMOKE" = auto ]; then
    run_smoke=0
    if [ -x "$smoke" ] && command -v qemu-system-aarch64 >/dev/null 2>&1; then
      run_smoke=1
    else
      warn "boot smoke skipped (需要 M2 的 tools/pc-boot-smoke-v2.sh；缺工具按 IMP-C03 warn)"
    fi
  fi
  if [ "$run_smoke" = 1 ]; then
    [ -x "$smoke" ] || { [ "$SMOKE" = 1 ] && die "--smoke given but $smoke missing"; }
    # MKDISK_SMOKE_ARGS 透传给冒烟器（如 M2 前用 "--stage bootchain"：
    # smoke-v2 默认 stage=ready，v2 guest 未交付前必失败且要等满超时）
    local smoke_args=()
    if [ -n "${MKDISK_SMOKE_ARGS:-}" ]; then read -r -a smoke_args <<<"$MKDISK_SMOKE_ARGS"; fi
    "$smoke" ${smoke_args[@]+"${smoke_args[@]}"} "$OUT" \
      || { [ "$SMOKE" = 1 ] && exit 3 || warn "smoke failed"; }
  fi
  return 0
}

# =========================================================================
if [ "$MODE" = verify ]; then
  [ -f "$OUT" ] || die "no such file: $OUT"
  verify_img "$OUT" "$FULL"
else
  cmd_build
fi
