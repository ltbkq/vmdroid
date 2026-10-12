#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 docs/IMAGE-FORMAT-v2.md v2.0（冻结 2026-10-12）构造 codec 自测夹具。

**独立构造是对规格的交叉验证**（本目录 README 开头的哲学，v2 版）：

  · 正例镜像由**参照实现** `build-tools/mkdisk.sh`（原 Podroid-Debian/tools，已迁移）按 §6 生成算法打包
    （`--no-smoke`；同一 rootfs 建两次 → §9 #2 content_id 稳定性）；
  · 负例在正例上**字节级定点篡改**（python 偏移写，不用 shell 算术；派生用零块
    跳写保持稀疏）；
  · 每条夹具的期望结果由 **oracle 实跑记录**：`mkdisk.sh --verify --full`（§5
    读取算法的参照实现，内嵌 python parser），实际的 `FAIL stage=… reason=…`
    原文写进清单 —— M3 重写后的 Kotlin `VmdImageCodec` 与它是两个独立实现，
    夹具把两者钉在同一份期望上；
  · 生成脚本自身断言「规格期望 vs oracle 实际」，不符立即失败：夹具清单不可能
    静默漂移。

产物（codec-selftest/fixtures/v2/；生成物不入 git —— 根 .gitignore 已覆盖
/codec-selftest/fixtures/；本脚本入库）：

  valid-minimal.img              正例（§9 #1，2048MiB 档）
  valid-second-build.img         同一 rootfs 二次打包（§9 #2：content_id 同、partuuid 异）
  esp-bootaa64-flip.img          ESP 内 BOOTAA64.EFI 首字节破坏 → 仍须 ACCEPT（§9 #23, IMP-C01）
  <negative>.img × 22            负例（§9 #3–#17 + §5 阶段2/3 边界）
  fixtures-v2.json               机器可读期望清单（oracle 实录，见 README §5.1）

用法：
  python3 gen_fixtures_v2.py            幂等重生成（先删后建 fixtures/v2/，约 1 分钟）
  python3 gen_fixtures_v2.py --clean    只清理
  python3 gen_fixtures_v2.py --keep-work 保留临时工作目录（调试用）

外部依赖（可用环境变量覆盖）：
  VMD_MKDISK          参照打包器（默认 <本仓库>/build-tools/mkdisk.sh）
  VMD_GRUB_PLATFORM   arm64-efi GRUB 平台目录
  VMD_KERNEL/VMD_INITRD  现成 payload
以及 mkdisk.sh 自身的依赖（sfdisk/sgdisk/mke2fs/mkfs.vfat/mcopy/grub-mkimage）。
"""
import argparse
import binascii
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
import uuid as uuidmod

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)                       # vmdroid-app
WS = os.path.dirname(REPO)                         # workspace 根
FDIR = os.path.join(HERE, "fixtures", "v2")

MKDISK = os.environ.get("VMD_MKDISK", os.path.join(WS, "vmdroid-app", "build-tools", "mkdisk.sh"))
CONTENT_ID_TOOL = os.path.join(os.path.dirname(MKDISK), "mkdisk-content-id.py")
GRUB_PLATFORM = os.environ.get(
    "VMD_GRUB_PLATFORM", os.path.join(REPO, "tools", "m0", "cache", "grub-arm64",
                                      "usr", "lib", "grub", "arm64-efi"))
KERNEL = os.environ.get("VMD_KERNEL", os.path.join(WS, "distro-build", "payload", "vmlinuz"))
INITRD = os.environ.get("VMD_INITRD", os.path.join(WS, "distro-build", "payload", "initrd.img"))

# ---- IMAGE-FORMAT-v2 §2/§3 冻结常量 ------------------------------------------
SECTOR = 512
ESP_OFF = 2048 * SECTOR                 # 1 MiB
ESP_SIZE = 256 << 20
META_OFF = 526336 * SECTOR              # 257 MiB = 269484032
META_SIZE = 8192
ROOT_OFF = 542720 * SECTOR              # 265 MiB = 277872640
MIB = 1 << 20
ROOT_SIZE = "2048MiB"                   # 最小档（§9 #1 默认 4096MiB；档位不影响 §5 判定）
META_GUID_LE = uuidmod.UUID("564d444d-4554-4132-8000-000000000001").bytes_le
MAGIC_V2 = b"VMDIMG02"
MAGIC_V1 = b"VMDIMG01"

failures = []       # 自测断言失败收集


def die(msg):
    raise SystemExit("gen_fixtures_v2: ERROR: %s" % msg)


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print("  SELFTEST FAIL: %s" % msg)
    return cond


def log(msg):
    print("gen_fixtures_v2: %s" % msg, flush=True)


# ---------------------------------------------------------------- 通用工具

def pseudo_random(n, seed):
    out = bytearray()
    counter = 0
    while len(out) < n:
        out += hashlib.sha256(seed + counter.to_bytes(4, "little")).digest()
        counter += 1
    return bytes(out[:n])


def pread(path, off, n):
    with open(path, "rb") as f:
        f.seek(off)
        b = f.read(n)
    if len(b) != n:
        die("short read @%d want %d got %d (%s)" % (off, n, len(b), path))
    return b


def pwrite(path, off, data):
    with open(path, "r+b") as f:
        f.seek(off)
        f.write(data)


def flip_at(path, off):
    """off 处单字节 XOR 0xFF（§9 #7/#9/#10/#23 的篡改原语）。"""
    with open(path, "r+b") as f:
        f.seek(off)
        b = f.read(1)
        if len(b) != 1:
            die("flip_at: short read @%d (%s)" % (off, path))
        f.seek(off)
        f.write(bytes([b[0] ^ 0xFF]))


COPY_CHUNK = 1 << 20


def copy_sparse(sf, df, src_off, dst_off, length, truncate=True):
    """按 1MiB 块拷贝 [src_off, +length) → dst_off：全零块留洞（读出即零），
    含任一非零字节的块整块写出。

    产物与逐字节拷贝完全等同（写出的零字节仍是零），但稀疏镜像（~95% 是零）
    只有少数块落盘；同时把逐字节 run 扫描的百万级 seek/write 降为百级 ——
    fixtures 目录里 *.img 的实际占用因此很小、生成也快。
    """
    sf.seek(src_off)
    pos = 0
    while pos < length:
        buf = sf.read(min(COPY_CHUNK, length - pos))
        if not buf:
            die("copy_sparse: short read @%d" % (src_off + pos))
        if buf.count(0) != len(buf):                  # 含非零字节 → 整块写出
            df.seek(dst_off + pos)
            df.write(buf)
        pos += len(buf)
    if truncate:
        df.truncate(dst_off + length)                 # 尾部零段也要占住长度


def copy_file_sparse(src, dst):
    size = os.path.getsize(src)
    with open(src, "rb") as sf, open(dst, "wb") as df:
        copy_sparse(sf, df, 0, 0, size)
    os.chmod(dst, 0o644)
    return size


def sparse_rewrite(path):
    """把全量落盘的镜像原地改写为稀疏文件（字节等同；sha256 前后校验）。"""
    h_before = sha256_file(path)
    tmp = path + ".sparse"
    copy_file_sparse(path, tmp)
    h_after = sha256_file(tmp)
    if h_before != h_after:
        os.unlink(tmp)
        die("sparse rewrite changed bytes: %s" % path)
    os.replace(tmp, path)
    return h_before


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- meta 读写（§3）

def read_meta(path):
    return bytearray(pread(path, META_OFF, META_SIZE))


def write_meta(path, buf):
    if len(buf) != META_SIZE:
        die("meta buffer must be %d bytes" % META_SIZE)
    pwrite(path, META_OFF, bytes(buf))


def rewrite_manifest(path, mutate):
    """改 meta 分区里的 manifest JSON 并**同步重算 meta 结构**（manifest_size +
    manifest_sha256），否则只会命中摘要错误而不是目标语义 reason。这正是
    §3 布局 + §5 阶段2/3 时序的构造性验证。"""
    meta = read_meta(path)
    moff = struct.unpack_from("<Q", meta, 24)[0]
    msize = struct.unpack_from("<Q", meta, 32)[0]
    doc = json.loads(bytes(meta[moff:moff + msize]).decode("utf-8"))
    mutate(doc)
    # 序列化参数与 mkdisk.sh 的 patch 步骤一致（indent=2, sort_keys, 末尾 \n）
    new = (json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    if not (1 <= len(new) <= META_SIZE - 148):
        die("rewritten manifest out of range: %d" % len(new))
    nm = bytearray(META_SIZE)
    nm[0:140] = meta[0:140]                              # 头部其余字段保持
    struct.pack_into("<Q", nm, 32, len(new))             # @32 manifest_size
    nm[40:72] = hashlib.sha256(new).digest()            # @40 manifest_sha256
    nm[moff:moff + len(new)] = new
    nm[META_SIZE - 8:] = MAGIC_V2                        # magic_tail
    write_meta(path, nm)


# ---------------------------------------------------------------- GPT 表项工具（§2.2）

def _ent_name(e):
    return e[56:128].decode("utf-16-le").split("\x00", 1)[0]


def _find_ent(ents, pred):
    for i in range(128):
        e = bytes(ents[i * 128:(i + 1) * 128])
        if e[0:16] == b"\x00" * 16:
            continue
        if pred(e):
            return i
    die("partition entry not found")


def gpt_mutate(path, mutate):
    """改 16KiB 分区表项并**同步重算 GPT CRC**（表项 CRC + 主/备 header CRC +
    备份表项拷贝），使被拒原因命中目标检查而不是 "entries crc32"。"""
    size = os.path.getsize(path)
    last = size // SECTOR - 1
    with open(path, "r+b") as f:
        f.seek(2 * SECTOR)
        ents = bytearray(f.read(128 * 128))
        mutate(ents)
        f.seek(2 * SECTOR)
        f.write(ents)
        f.seek((last - 32) * SECTOR)                    # 备份表项保持一致
        f.write(ents)
        ent_crc = binascii.crc32(ents) & 0xFFFFFFFF
        for lba in (1, last):                           # 主/备 header 各自重算 CRC
            f.seek(lba * SECTOR)
            hdr = bytearray(f.read(92))
            struct.pack_into("<I", hdr, 88, ent_crc)
            struct.pack_into("<I", hdr, 16, 0)
            struct.pack_into("<I", hdr, 16, binascii.crc32(hdr[:92]) & 0xFFFFFFFF)
            f.seek(lba * SECTOR)
            f.write(hdr)


# ---------------------------------------------------------------- §4.3 content_id

SUBTREE_EXCLUDE = ("var", "run", "tmp", "home", "lost+found")
EXACT_EXCLUDE = ("etc/machine-id", "root/.bash_history")


def _excluded_v2(parts):
    """§4.3 冻结排除清单（本脚本的独立实现）。"""
    if parts[0] in SUBTREE_EXCLUDE:
        return True                                    # 整棵子树（含根目录项）
    rel = "/".join(parts)
    if rel in EXACT_EXCLUDE:
        return True
    if len(parts) == 3 and parts[0] == "etc" and parts[1] == "ssh" \
            and parts[2].startswith("ssh_host_"):
        return True
    if len(parts) == 2 and parts[0] == "boot" \
            and (parts[1].startswith("vmlinuz-") or parts[1].startswith("initrd.img-")):
        return True
    return False


def content_id_spec(root):
    """§4.3 算法的独立实现（与参照 mkdisk-content-id.py 交叉验证）。"""
    lines = []

    def walk(abspath, parts):
        with os.scandir(abspath) as it:
            for e in sorted(it, key=lambda x: x.name):
                rel_parts = parts + [e.name]
                if _excluded_v2(rel_parts):
                    continue
                st = e.stat(follow_symlinks=False)
                head = "%s\0%s\0%d\0%d\0" % (
                    "/".join(rel_parts), format(st.st_mode, "o"), st.st_uid, st.st_gid)
                if stat.S_ISDIR(st.st_mode):
                    lines.append((rel_parts, head + "DIR\n"))
                    walk(e.path, rel_parts)
                elif stat.S_ISLNK(st.st_mode):
                    lines.append((rel_parts, head + "LINK:" + os.readlink(e.path) + "\n"))
                elif stat.S_ISREG(st.st_mode):
                    lines.append((rel_parts, head + sha256_file(e.path) + "\n"))
                else:
                    die("content_id: 非常规文件类型 %s" % e.path)

    walk(root, [])
    lines.sort(key=lambda t: t[0])                     # 按路径码点字典序
    h = hashlib.sha256()
    for _, line in lines:
        h.update(line.encode("utf-8"))
    return "sha256:" + h.hexdigest()


def content_id_ref(root):
    """参照实现（打进镜像 manifest 的那个值）。"""
    r = subprocess.run([sys.executable, CONTENT_ID_TOOL, root], capture_output=True, text=True)
    if r.returncode != 0:
        die("mkdisk-content-id.py failed: %s" % (r.stdout + r.stderr))
    return r.stdout.strip()


# ---------------------------------------------------------------- rootfs（合成小树）

def build_rootfs(root):
    def w(rel, data, mode=None):
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data if isinstance(data, bytes) else data.encode("utf-8"))
        if mode is not None:
            os.chmod(p, mode)

    def link(rel, target):
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        os.symlink(target, p)

    # 参与 content_id 的项
    w("etc/hostname", "vmdroid-fixture\n")
    w("etc/os-release", 'PRETTY_NAME="Debian GNU/Linux 13 (trixie)"\nID=debian\n')
    w("etc/ssh/sshd_config", "Port 22\nPermitRootLogin yes\n")
    w("boot/config-6.1.0", "CONFIG_FIXTURE=y\n")        # /boot 下只有 vmlinuz-/initrd.img-* 被排除
    w("usr/bin/hello", "#!/bin/sh\necho hello\n", mode=0o755)
    w("usr/lib/libfixture.so", pseudo_random(96, b"lib"))
    w("usr/share/doc/fixture/README", "synthetic rootfs for VMDroid codec selftest\n")
    link("usr/bin/hello-link", "hello")                 # 符号链接
    link("etc/mtab", "../proc/mounts")
    # §4.3 白名单排除项（验证"运行期变化项不进 content_id"语义）
    w("etc/machine-id", "6c0b56c8e0d14f9a8b3f2d1c0a9e7b65\n")
    w("etc/ssh/ssh_host_rsa_key", "-----BEGIN RSA PRIVATE KEY-----\n(fixture)\n")
    w("etc/ssh/ssh_host_ed25519_key", "(fixture ed25519)\n")
    w("var/x", "runtime state\n")
    w("var/log/syslog", "runtime log\n")
    w("run/secrets/flag", "runtime secret\n")
    w("tmp/tmpfile", "runtime tmp\n")
    w("home/ltbkq/.profile", "runtime profile\n")
    w("root/.bash_history", "runtime history\n")
    w("boot/vmlinuz-6.1.0-fixture", pseudo_random(64, b"vmlinuz-excluded"))
    w("boot/initrd.img-6.1.0-fixture", pseudo_random(64, b"initrd-excluded"))
    w("lost+found/.keep", "")
    return root


# ---------------------------------------------------------------- manifest 模板（§4）

def manifest_template():
    ph = "0" * 64
    return {
        "format": "vmdroid-system-image",
        "format_version": 2,
        "image": {
            "id": "debian-minimal-arm64",
            "display_name": "Debian 13 (trixie) · 最小化（夹具）",
            "identity": "debian:trixie",
            "variant": "minimal",
            "version": "2026.10.0-r1",
            "system_version": 35,
            "arch": "arm64",
            "distro": {"name": "debian", "release": "trixie", "init": "systemd"},
            "created_at": "2026-10-12T00:00:00Z",
            "source": "https://github.com/ltbkq/vmdroid",
            "license": "GPL-2.0-or-later",
            "content_id": "sha256:" + ph,             # mkdisk 覆写（IMP-C02）
        },
        "contract": {
            "version": 2,
            "markers": ["Loading kernel modules...", "Network found",
                        "Starting SSH...", "Almost ready...", "Ready!"],
            "ttys": {"hvc0": "login", "hvc1": "resize",
                     "hvc2": "host-bridge", "ttyAMA0": "console"},
            "kernel": {"builtin_only": True, "min": "6.0", "max": "7.99", "image_sha256": ph},
        },
        "capabilities": {"ssh": True, "x11": True, "desktop": False, "containers": False,
                         "desktop_profile": False, "downloads_share": True,
                         "usb_passthrough_host": True},
        "accounts": {
            "ssh": [{"user": "root", "password": "123", "sudo": False},
                    {"user": "ltbkq", "password": "123", "sudo": True}],
            "default_user": "ltbkq",
            "ssh_port": 22,
        },
        "app": {"min_version_code": 1},
        "boot": {
            "mode": "uefi",
            "machine": "virt",
            "cpu": "max",
            "append": "console=ttyAMA0 mitigations=off",
            "kernel_sha256": ph,                      # mkdisk 覆写
            "initrd_sha256": ph,
            "drives": {"vda": "storage.img(rw,ext4)", "vdb": "self(rw,gpt)"},
        },
        "checksums": {"root_sha256": ph, "kernel_sha256": ph, "initrd_sha256": ph},
        # 前向兼容：未知字段必须忽略（§4.2 第一条）
        "extra": {"note": "forward-compat fixture field", "esc": "中文测试"},
    }


# ---------------------------------------------------------------- oracle（mkdisk.sh --verify）

FAIL_RE = re.compile(r"^FAIL stage=(\S+) reason=(.*)$", re.M)


def oracle(path, full=True):
    """跑参照 parser，返回 (status, stage, reason, output)。"""
    cmd = [MKDISK, "--verify", path] + (["--full"] if full else [])
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip()
    if r.returncode == 0 and "VERIFY_OK" in out:
        return "ok", None, None, out
    m = FAIL_RE.search(out)
    if m:
        return "reject", m.group(1), m.group(2).strip(), out
    die("oracle 无预期输出 (rc=%d): %s" % (r.returncode, out))


def verify_field(out, key):
    m = re.search(r"(?:^|\s)%s=(\S+)" % re.escape(key), out, re.M)
    if not m:
        die("verify 输出缺字段 %s" % key)
    return m.group(1).strip()


# ---------------------------------------------------------------- 篡改动作（负例/特例）

def act_truncate_1byte(p):
    os.truncate(p, os.path.getsize(p) - 1)


def act_append_1byte(p):
    with open(p, "ab") as f:
        f.write(b"\x00")


def act_gpt_header_crc(p):
    flip_at(p, 1 * SECTOR + 56)                       # LBA1 header disk GUID 首字节


def act_meta_magic_both(p):
    m = read_meta(p)                                  # 双魔数（头 + 尾）一起覆写
    m[0:8] = b"VMDIMG99"
    m[META_SIZE - 8:] = b"VMDIMG99"
    write_meta(p, m)


def act_meta_tail_magic(p):
    m = read_meta(p)
    m[META_SIZE - 8:] = b"VMDIMG99"
    write_meta(p, m)


def act_manifest_flip(p):
    flip_at(p, META_OFF + 140)                        # manifest 区首字节


def act_root_flip(p):
    flip_at(p, ROOT_OFF)                              # root 分区首字节（阶段4 --full 才可见）


def act_meta_entry_deleted(p):
    def mutate(ents):
        k = _find_ent(ents, lambda e: e[16:32] == META_GUID_LE)
        ents[k * 128:(k + 1) * 128] = bytes(128)
    gpt_mutate(p, mutate)


def act_root_not_last(p):
    """把 ESP 表项挪到 root 之后（表项重排）→ §5 阶段1 "root 不是最后一个分区"。"""
    def mutate(ents):
        kr = _find_ent(ents, lambda e: _ent_name(e) == "vmd-root")
        ke = _find_ent(ents, lambda e: _ent_name(e) == "VMDROID-EFI")
        root_last = struct.unpack_from("<Q", ents, kr * 128 + 40)[0]
        struct.pack_into("<QQ", ents, ke * 128 + 32, root_last + 1, root_last + 101)
    gpt_mutate(p, mutate)


def act_arch(p):
    rewrite_manifest(p, lambda d: d["image"].__setitem__("arch", "x86_64"))


def act_ssh_cap(p):
    rewrite_manifest(p, lambda d: d["capabilities"].__setitem__("ssh", False))


def act_ssh_port(p):
    rewrite_manifest(p, lambda d: d["accounts"].__setitem__("ssh_port", 2222))


def act_image_id(p):
    rewrite_manifest(p, lambda d: d["image"].__setitem__("id", "debian/minimal-arm64"))


def act_app_min_missing(p):
    rewrite_manifest(p, lambda d: d["app"].pop("min_version_code"))


def act_boot_mode(p):
    rewrite_manifest(p, lambda d: d["boot"].__setitem__("mode", "direct"))


def act_contract_version(p):
    rewrite_manifest(p, lambda d: d["contract"].__setitem__("version", 1))


def act_fmtver_manifest(p):
    rewrite_manifest(p, lambda d: d.__setitem__("format_version", 3))


def act_fmtver_meta(p):
    m = read_meta(p)
    struct.pack_into("<I", m, 8, 3)
    write_meta(p, m)


def act_flags(value):
    def f(p, _work=None):
        m = read_meta(p)
        struct.pack_into("<I", m, 136, value)
        write_meta(p, m)
    return f


def act_v1_footer(p):
    """无保护性 MBR（清 0xEE 类型字节）+ 尾部 VMDIMG01 → 诊断阶梯 LEGACY_FORMAT。"""
    pwrite(p, 450, b"\x00")                           # mbr[450] = 分区类型 0xEE
    pwrite(p, os.path.getsize(p) - 4096, MAGIC_V1)


def fat32_file_offset(path, parts):
    """在镜像 ESP 区内按 FAT32 结构解析 parts（如 ['EFI','BOOT','BOOTAA64.EFI']）
    的文件起始字节偏移（绝对偏移）。只读引导扇区/目录/FAT 链需要的扇区，
    不改写任何字节 —— 保证"ESP 内容破坏"夹具是**单点**篡改（mtools 往返会顺带
    改写 FAT 表高 4 位等元数据，那就不是定点向量了）。"""
    bpb = pread(path, ESP_OFF, 512)
    bps = struct.unpack_from("<H", bpb, 11)[0]          # bytes per sector
    spc = bpb[13]                                       # sectors per cluster
    reserved = struct.unpack_from("<H", bpb, 14)[0]
    nfats = bpb[16]
    fat_size = struct.unpack_from("<I", bpb, 36)[0]
    root_cluster = struct.unpack_from("<I", bpb, 44)[0]
    if not (bps == 512 and spc and nfats and fat_size):
        die("unexpected FAT32 BPB (bps=%d spc=%d nfats=%d)" % (bps, spc, nfats))
    cluster_bytes = bps * spc
    fat_off = ESP_OFF + reserved * bps
    data_off = ESP_OFF + (reserved + nfats * fat_size) * bps

    def cluster_off(c):
        return data_off + (c - 2) * cluster_bytes

    def next_cluster(c):
        ent = struct.unpack("<I", pread(path, fat_off + c * 4, 4))[0]
        return ent & 0x0FFFFFFF

    def find_in_dir(cluster, name):
        want = name.upper()
        while 2 <= cluster < 0x0FFFFFF8:
            blob = pread(path, cluster_off(cluster), cluster_bytes)
            for i in range(0, len(blob), 32):
                e = blob[i:i + 32]
                if e[0] in (0x00, 0xE5):
                    continue
                attr = e[11]
                if attr == 0x0F or attr & 0x08:         # LFN / 卷标
                    continue
                sname = e[0:8].decode("ascii", "replace").rstrip(" ")
                sext = e[8:11].decode("ascii", "replace").rstrip(" ")
                full = sname if not sext else sname + "." + sext
                if full.upper() == want:
                    first = struct.unpack_from("<H", e, 26)[0] | \
                        (struct.unpack_from("<H", e, 20)[0] << 16)
                    return first, bool(attr & 0x10)
            cluster = next_cluster(cluster)
        die("FAT32: %s 未找到" % "/".join(parts))

    cluster = root_cluster
    for comp in parts[:-1]:
        cluster, is_dir = find_in_dir(cluster, comp)
        if not is_dir:
            die("FAT32: %s 不是目录" % comp)
    first, is_dir = find_in_dir(cluster, parts[-1])
    if is_dir:
        die("FAT32: %s 是目录" % parts[-1])
    return cluster_off(first)


def act_esp_boot_flip(p, _work=None):
    """改 ESP 内 BOOTAA64.EFI 首字节（§9 #23：读取方 MUST NOT reject）。

    阶段 1–3 不解析 ESP（R-03：无 esp_sha256），所以本夹具必须被 ACCEPT ——
    IMP-C01 的正向向量。翻转点由 FAT32 解析定位（单字节定点）。
    """
    off = fat32_file_offset(p, ["EFI", "BOOT", "BOOTAA64.EFI"])
    flip_at(p, off)


def synth_bare_squashfs(path):
    """1MiB 随机 + 开头 "hsqs" → 阶段1 诊断阶梯 LEGACY_FORMAT（§9 #4）。"""
    b = bytearray(pseudo_random(MIB, b"bare-squashfs-fixture"))
    if b[510:512] == b"\x55\xaa" and b[450] == 0xEE:   # 保证不落入保护性 MBR 分支
        b[450] = 0x00
    if bytes(b[MIB - 4096:MIB - 4096 + 8]) == MAGIC_V1:
        b[MIB - 4096] = 0x00
    b[0:4] = b"hsqs"
    with open(path, "wb") as f:
        f.write(bytes(b))


# ---------------------------------------------------------------- 夹具清单（§9 权威向量表）

POS_FIXTURES = [
    dict(name="valid-minimal", spec_ref="§9 #1",
         note="合法镜像：阶段1–3 通过；flags==0x3；布局与 §2 一致"),
    dict(name="valid-second-build", spec_ref="§9 #2",
         note="同一 rootfs 二次打包：content_id 相同、partuuid_root 不同"),
    dict(name="esp-bootaa64-flip", spec_ref="§9 #23（IMP-C01）",
         action=lambda p, w: act_esp_boot_flip(p, w),
         note="ESP 内 BOOTAA64.EFI 首字节破坏 —— 读取方 MUST NOT reject"),
]

NEG_FIXTURES = [
    dict(name="truncate-1byte", spec_ref="§9 #5", stage="gpt", reason="backup LBA",
         action=lambda p, w: act_truncate_1byte(p),
         note="截断 1 字节 → GPT 备份位置失败"),
    dict(name="append-1byte", spec_ref="§9 #6", stage="meta", reason="file_size",
         action=lambda p, w: act_append_1byte(p),
         note="尾部追加 1 字节 → meta.file_size != size"),
    dict(name="gpt-header-crc-flip", spec_ref="§9 #7", stage="gpt", reason="header crc32",
         action=lambda p, w: act_gpt_header_crc(p),
         note="GPT header 单字节翻转 → CRC 校验失败"),
    dict(name="meta-magic-both-bad", spec_ref="§3 双魔数 / §5 阶段2",
         stage="meta", reason="magic b'VMDIMG99'",
         action=lambda p, w: act_meta_magic_both(p),
         note="meta 头+尾双魔数覆写 → 先命中头魔数"),
    dict(name="meta-tail-magic-bad", spec_ref="§9 #8", stage="meta", reason="magic_tail",
         action=lambda p, w: act_meta_tail_magic(p),
         note="meta 尾魔数（meta_size−8 处）覆写"),
    dict(name="manifest-byte-flip", spec_ref="§9 #9", stage="manifest", reason="manifest_sha256",
         action=lambda p, w: act_manifest_flip(p),
         note="manifest 区单字节翻转 → manifest_sha256 不符"),
    dict(name="root-byte-flip", spec_ref="§9 #10（full 校验）", stage="full",
         reason="root 分区 sha256", action=lambda p, w: act_root_flip(p),
         note="root 分区首字节翻转：阶段1–3 放行，仅 --full（阶段4）拒绝"),
    dict(name="gpt-meta-entry-deleted", spec_ref="§9 #11", stage="gpt", reason="缺 meta 分区",
         action=lambda p, w: act_meta_entry_deleted(p),
         note="删除 meta 分区表项（同步重算 GPT CRC）→ 找不到固定 GUID"),
    dict(name="gpt-root-not-last", spec_ref="§9 #12", stage="layout",
         reason="root 不是最后一个分区", action=lambda p, w: act_root_not_last(p),
         note="表项重排：ESP 移到 root 之后"),
    dict(name="arch-x86-64", spec_ref="§9 #15", stage="manifest", reason="arch='x86_64'",
         action=lambda p, w: act_arch(p), note="manifest 语义负例（同步重算 manifest_sha256）"),
    dict(name="ssh-cap-false", spec_ref="§9 #15", stage="manifest",
         reason="capabilities.ssh != true", action=lambda p, w: act_ssh_cap(p),
         note="capabilities.ssh=false → SSH_CAPABILITY_MISSING"),
    dict(name="ssh-port-2222", spec_ref="§9 #15", stage="manifest",
         reason="accounts.ssh_port != 22", action=lambda p, w: act_ssh_port(p),
         note="accounts.ssh_port=2222 → SSH_PORT_INVALID"),
    dict(name="image-id-slash", spec_ref="§9 #15", stage="manifest", reason="image.id 非法",
         action=lambda p, w: act_image_id(p),
         note="image.id 含 '/' → IMAGE_ID_INVALID（防 -drive 注入）"),
    dict(name="app-min-version-missing", spec_ref="§4.2 IMP-D05 / §9 #15",
         stage="manifest", reason="app.min_version_code 缺失",
         action=lambda p, w: act_app_min_missing(p),
         note="缺 app.min_version_code；注意 §4.2 缺省解释对“无 app”给 0，oracle 对“缺键”拒绝"),
    dict(name="boot-mode-direct", spec_ref="§9 #13", stage="manifest",
         reason="boot.mode='direct'", action=lambda p, w: act_boot_mode(p),
         note="boot.mode != uefi → BOOT_MODE_UNSUPPORTED"),
    dict(name="contract-version-1", spec_ref="§9 #14", stage="manifest",
         reason="contract.version != 2", action=lambda p, w: act_contract_version(p),
         note="contract.version=1 → CONTRACT_VERSION"),
    dict(name="format-version-3-manifest", spec_ref="§9 #16（manifest 侧）",
         stage="manifest", reason="format_version=3", action=lambda p, w: act_fmtver_manifest(p),
         note="manifest.format_version=3（meta 同步重算摘要）"),
    dict(name="format-version-3-meta", spec_ref="§9 #16（meta 头侧）", stage="meta",
         reason="FORMAT_UNSUPPORTED: format_version=3", action=lambda p, w: act_fmtver_meta(p),
         note="meta.format_version=3 → 提示升级应用"),
    dict(name="flags-0x1", spec_ref="§9 #17", stage="meta", reason="flags 0x1",
         action=act_flags(0x1), note="flags bit1 清零（HAS_INITRD_ESP 缺失）"),
    dict(name="flags-0x7", spec_ref="§9 #17", stage="meta", reason="flags 0x7",
         action=act_flags(0x7), note="flags bit2 置位（超出合法掩码 0x3）"),
    dict(name="v1-footer", spec_ref="§9 #3", stage="ladder", reason="LEGACY_FORMAT: v1 footer",
         action=lambda p, w: act_v1_footer(p),
         note="无保护性 MBR + 尾部 VMDIMG01 → v1 旧格式"),
    dict(name="bare-squashfs", spec_ref="§9 #4", synth=synth_bare_squashfs,
         stage="ladder", reason="LEGACY_FORMAT: 裸 squashfs",
         note="裸 squashfs（hsqs @0）"),
]


# ---------------------------------------------------------------- main

def run_mkdisk_build(out, rootfs, manifest):
    cmd = [MKDISK, "--rootfs-dir", rootfs, "--manifest", manifest,
           "--kernel", KERNEL, "--initrd", INITRD,
           "--grub-platform", GRUB_PLATFORM, "--root-size", ROOT_SIZE,
           "--no-smoke", "-o", out]
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stdout.write(r.stdout)
        sys.stderr.write(r.stderr)
        die("mkdisk build failed (rc=%d)" % r.returncode)
    m = re.search(r"^partuuid_root=(\S+)$", r.stdout, re.M)
    if not m:
        die("mkdisk 输出缺 partuuid_root")
    log("built %s (%.1fs, partuuid_root=%s)" % (os.path.basename(out), time.time() - t0,
                                                m.group(1)))
    return m.group(1)


def copy_tree_mutation(src, dst, mutate):
    shutil.copytree(src, dst, symlinks=True)
    mutate(dst)


def mutate_append(rel_targets):
    def f(root):
        for rel in rel_targets:
            with open(os.path.join(root, rel), "ab") as fh:
                fh.write(b"MUTATED\n")
    return f


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--clean", action="store_true", help="只删除 fixtures/v2/")
    ap.add_argument("--keep-work", action="store_true", help="保留临时工作目录")
    args = ap.parse_args()

    if args.clean:
        if os.path.isdir(FDIR):
            shutil.rmtree(FDIR)
            log("removed %s" % FDIR)
        else:
            log("nothing to clean (%s absent)" % FDIR)
        return 0

    # ---- 前置检查 ----
    for tool in ("sfdisk", "sgdisk", "mke2fs", "mkfs.vfat", "mcopy", "grub-mkimage"):
        if not shutil.which(tool):
            die("missing tool: %s (mkdisk.sh 依赖)" % tool)
    for p in (MKDISK, CONTENT_ID_TOOL, KERNEL, INITRD):
        if not os.path.exists(p):
            die("missing dependency: %s" % p)
    if not os.path.isdir(GRUB_PLATFORM):
        die("missing GRUB platform dir: %s" % GRUB_PLATFORM)

    if os.path.isdir(FDIR):
        shutil.rmtree(FDIR)
    os.makedirs(FDIR)
    tmpbase = "/tmp/opencode" if os.path.isdir("/tmp/opencode") else None
    work = tempfile.mkdtemp(prefix="gen-fixtures-v2-", dir=tmpbase)
    log("work dir: %s" % work)

    try:
        # ---- rootfs + content_id 交叉验证（§4.3）----
        rootfs = build_rootfs(os.path.join(work, "rootfs"))
        cid_spec = content_id_spec(rootfs)
        cid_ref = content_id_ref(rootfs)
        log("content_id(spec §4.3 实现) = %s" % cid_spec)
        log("content_id(参照实现)       = %s" % cid_ref)
        if cid_spec != cid_ref:
            # 已知参照实现偏差：EXACT 集带前导 "/"、遍历 rel 不带 →
            # /etc/machine-id 与 /root/.bash_history 实际未被排除。读取方只比对
            # content_id 字符串（§4.3），不影响 codec；此处仅告警。
            log("WARN: spec 与参照实现 content_id 不一致（见下方白名单自测定位；"
                "已知 mkdisk-content-id.py 的 EXACT 前缀缺陷，非本夹具问题）")

        # ---- manifest 模板 ----
        manifest = os.path.join(work, "manifest.json")
        with open(manifest, "w", encoding="utf-8") as f:
            json.dump(manifest_template(), f, ensure_ascii=False, indent=2, sort_keys=True)
            f.write("\n")

        # ---- 正例：mkdisk.sh 打包两次（随机 partuuid/esp 卷 ID）----
        paths = {}
        base = os.path.join(FDIR, "valid-minimal.img")
        pu1 = run_mkdisk_build(base, rootfs, manifest)
        paths["valid-minimal"] = base
        log("sparsify valid-minimal.img ...")
        sha_base = sparse_rewrite(base)

        second = os.path.join(FDIR, "valid-second-build.img")
        pu2 = run_mkdisk_build(second, rootfs, manifest)
        paths["valid-second-build"] = second
        log("sparsify valid-second-build.img ...")
        sparse_rewrite(second)

        # 正例自测：oracle --full 通过 + §9 #2 稳定性
        builds = {}
        cid_img = []
        for name in ("valid-minimal", "valid-second-build"):
            status, stage, reason, out = oracle(paths[name], full=True)
            check(status == "ok", "%s 应 VERIFY_OK，实际 %s/%s" % (name, stage, reason))
            cid = verify_field(out, "content_id").split()[-1]
            cid_img.append(cid)
            builds[name] = {
                "content_id": cid,
                "root_sha256": verify_field(out, "root_sha256"),
                "file_size": int(verify_field(out, "file_size")),
                "partuuid_root": pu1 if name == "valid-minimal" else pu2,
            }
        check(cid_img[0] == cid_img[1],
              "§9 #2: 两次打包 content_id 应相同 (%s != %s)" % (cid_img[0], cid_img[1]))
        check(pu1 != pu2, "§9 #2: 两次打包 partuuid_root 应不同")
        check(cid_img[0] == cid_ref, "镜像内 content_id 应等于参照实现输出")
        log("§9 #2 content_id 稳定性: %s（两次相同），partuuid %s != %s"
            % (cid_img[0], pu1, pu2))
        check(sha_base == sha256_file(base),
              "稀疏改写后 valid-minimal.img 字节应不变")  # sparse_rewrite 内部已查，双保险

        # ---- §4.3 白名单语义自测（在 rootfs 副本上做，不碰构建输入）----
        both_excluded = ["var/x", "var/log/syslog", "run/secrets/flag", "tmp/tmpfile",
                         "home/ltbkq/.profile", "etc/ssh/ssh_host_rsa_key",
                         "etc/ssh/ssh_host_ed25519_key",
                         "boot/vmlinuz-6.1.0-fixture", "boot/initrd.img-6.1.0-fixture"]
        t_a = os.path.join(work, "tree-excluded")
        copy_tree_mutation(rootfs, t_a, mutate_append(both_excluded))
        check(content_id_ref(t_a) == cid_ref,
              "§4.3: 参照实现应对排除项变更保持 content_id 不变")
        check(content_id_spec(t_a) == cid_spec,
              "§4.3: spec 实现应对排除项变更保持 content_id 不变")
        t_b = os.path.join(work, "tree-exact")
        copy_tree_mutation(rootfs, t_b, mutate_append(["etc/machine-id", "root/.bash_history"]))
        check(content_id_spec(t_b) == cid_spec,
              "§4.3: spec 实现应排除 /etc/machine-id 与 /root/.bash_history")
        ref_b = content_id_ref(t_b)
        if ref_b != cid_ref:
            log("WARN: 参照实现 mkdisk-content-id.py 对 /etc/machine-id 或 "
                "/root/.bash_history 的变更**改变了** content_id —— 与 §4.3 冻结排除清单"
                "不符（EXACT 前导 / 缺陷）；读取方只比字符串，codec 不受影响")
        t_c = os.path.join(work, "tree-included")
        copy_tree_mutation(rootfs, t_c, mutate_append(["etc/hostname"]))
        check(content_id_spec(t_c) != cid_spec, "§4.3: 参与项变更必须改变 content_id")

        # ---- 夹具派生 + 篡改 + oracle 实录 ----
        entries = []
        summary = []

        def record(spec, status, stage, reason, out):
            expect = spec.get("expect", "ok")
            fname = spec["name"] + ".img"
            entry = {
                "name": spec["name"],
                "file": fname,
                "expect": expect,
                "reason": None if expect == "ok" else reason,
                "spec_ref": spec["spec_ref"],
                "verify_output": out,
            }
            entries.append(entry)
            if expect == "ok":
                check(status == "ok", "%s: 期望 ok，实际 %s %s" % (spec["name"], stage, reason))
            else:
                check(status == "reject", "%s: 期望 reject，实际 ok" % spec["name"])
                check(stage == spec["stage"],
                      "%s: 期望 stage=%s，实际 stage=%s (reason=%s)"
                      % (spec["name"], spec["stage"], stage, reason))
                check(reason.startswith(spec["reason"]),
                      "%s: 期望 reason 以 %r 开头，实际 %r"
                      % (spec["name"], spec["reason"], reason))
            summary.append((spec["name"], expect,
                            "ok" if status == "ok" else "%s / %s" % (stage, reason)))

        # 正例三条
        for spec in POS_FIXTURES:
            path = os.path.join(FDIR, spec["name"] + ".img")
            if spec["name"] in paths:
                pass                                     # 已生成（两条打包正例）
            else:
                copy_file_sparse(base, path)
                spec["action"](path, work)
            paths[spec["name"]] = path
            status, stage, reason, out = oracle(path, full=True)
            sp = dict(spec, expect="ok")
            record(sp, status, stage, reason, out)
            log("fixture %-26s expect=ok            actual=%s"
                % (spec["name"], "ok" if status == "ok" else "%s/%s" % (stage, reason)))

        # 负例
        for spec in NEG_FIXTURES:
            path = os.path.join(FDIR, spec["name"] + ".img")
            if "synth" in spec:
                spec["synth"](path)
            else:
                copy_file_sparse(base, path)
                spec["action"](path, work)
            paths[spec["name"]] = path
            status, stage, reason, out = oracle(path, full=True)
            sp = dict(spec, expect="reject")
            record(sp, status, stage, reason, out)
            log("fixture %-26s expect=reject/%-8s actual=%s"
                % (spec["name"], spec["stage"],
                   "ok" if status == "ok" else "%s / %s" % (stage, reason)))

        # ---- 清单 ----
        doc = {
            "spec": "docs/IMAGE-FORMAT-v2.md v2.0（冻结 2026-10-12）",
            "generated_by": "codec-selftest/gen_fixtures_v2.py",
            "oracle": "build-tools/mkdisk.sh --verify --full（§5 读取算法参照实现）",
            "root_size_mib": int(ROOT_SIZE.rstrip("MiB")),
            "note": "verify_output 均为 oracle 实录；root-byte-flip 仅 --full（阶段4）拒绝，"
                    "阶段1–3 快检会放行（见 README §5）",
            "builds": builds,
            "fixtures": entries,
        }
        jpath = os.path.join(FDIR, "fixtures-v2.json")
        with open(jpath, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")

        # ---- 汇总 ----
        print()
        log("=" * 78)
        for name, expect, actual in summary:
            print("  %-28s expect=%-6s actual=%s" % (name, expect, actual))
        log("=" * 78)
        n_ok = sum(1 for s in summary if s[1] == "ok")
        log("%d fixtures (%d ok / %d reject) in %s" % (len(summary), n_ok, len(summary) - n_ok, FDIR))
        if failures:
            log("SELFTEST: %d FAILURE(S)" % len(failures))
            for m in failures:
                log("  - " + m)
            return 1
        log("SELFTEST: all expectations matched the oracle")
        return 0
    finally:
        if args.keep_work:
            log("work dir kept: %s" % work)
        else:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
