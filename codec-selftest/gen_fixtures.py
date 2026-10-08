#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 docs/IMAGE-FORMAT.md v1.0 (format_version=1) 独立构造 codec 测试 fixture。

独立构造本身就是对规格可实现性的交叉验证（与 Podroid-Debian 侧 mkimg.sh 互为对照）。
产出（全部位于 codec-selftest/fixtures/）：

  *.img                      —— 正/负向镜像（稀疏文件，物理占用小）
  *.sha256                   —— 若干整镜像的 python hashlib sha256（与 Kotlin sha256File 交叉验证）
  sources/rootfs.src|kernel.src|initrd.src|manifest-*.json (+ .sha256)
                               —— 段源文件，供 extractPayload 逐字节比对

布局遵循 §2：rootfs@0 → 1MiB 对齐 kernel → 1MiB 对齐 initrd → 1MiB 对齐 manifest
→ 4KiB 对齐 footer（footer 4096B，偏移表见 §3，R3 冻结：manifest@72 / kernel@124 / initrd@172）。

用法：python3 gen_fixtures.py   （幂等，重复生成覆盖）
"""
import copy
import hashlib
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FDIR = os.path.join(HERE, "fixtures")
SDIR = os.path.join(FDIR, "sources")

MAGIC = b"VMDIMG01"
FOOTER = 4096
MIB = 1 << 20
KIB = 1 << 10
ESC_MARKER = "@@U4E2D@@"  # dumps() 时替换成真实 \u4e2d 转义，覆盖 Kotlin \uXXXX 解析路径


def align_up(x, a):
    return (x + a - 1) // a * a


def sha(b):
    return hashlib.sha256(b).digest()


def sha_hex(b):
    return hashlib.sha256(b).hexdigest()


def write_sparse(fh, data):
    """全零块不落盘（稀疏），非零连续段一次写出。"""
    i, n = 0, len(data)
    while i < n:
        j = i
        while j < n and data[j] == 0:
            j += 1
        if j >= n:
            break
        k = j
        while k < n and data[k] != 0:
            k += 1
        fh.seek(j)
        fh.write(data[j:k])
        i = k


def wfile(path, data, final_len=None):
    with open(path, "wb") as f:
        write_sparse(f, data)
        f.truncate(len(data) if final_len is None else final_len)
    return path


def wsha(path, data):
    with open(path + ".sha256", "w", encoding="utf-8") as f:
        f.write(sha_hex(data) + "  " + os.path.basename(path) + "\n")


# ---------------------------------------------------------------- 段内容

def make_rootfs(size=64 * KIB):
    """最小伪 squashfs：superblock 魔数 hsqs + bytes_used，其余确定性填充（翻转可检出）。"""
    b = bytearray(size)
    b[0:4] = b"hsqs"
    struct.pack_into("<I", b, 4, 8)          # inodes
    struct.pack_into("<I", b, 12, 4096)      # block_size
    struct.pack_into("<H", b, 20, 1)         # compression = gzip
    struct.pack_into("<H", b, 28, 4)         # s_major
    struct.pack_into("<Q", b, 40, size)      # bytes_used = R（§6 mkimg 亦取此字段）
    for i in range(128, size):
        b[i] = (i * 31 + 7) & 0xFF
    return bytes(b)


def make_kernel(size=MIB):
    """arm64 Image payload：magic "ARM\\x64" @0x38，其余零（稀疏友好）。§2 要求 ≥1MiB。"""
    b = bytearray(size)
    b[0x38:0x3C] = b"ARM\x64"
    struct.pack_into("<I", b, 0x3C, 0x0000_0000)  # flags (unused by codec)
    return bytes(b)


def make_initrd(size=8 * KIB):
    """initrd payload：§2 要求 ≥4096。"""
    return bytes((i * 131 + 17) & 0xFF for i in range(size))


# ---------------------------------------------------------------- manifest（§4）

def manifest_dict(rootfs, kernel=None, initrd=None):
    ksha = sha_hex(kernel) if kernel else "0" * 64
    isha = sha_hex(initrd) if initrd else "0" * 64
    return {
        "format": "vmdroid-system-image",
        "format_version": 1,
        "image": {
            "id": "debian-minimal-arm64",
            "display_name": "Debian 13 (trixie) · 最小化",
            "identity": "debian:trixie",
            "variant": "minimal",
            "version": "2026.10.0-r1",
            "system_version": 34,
            "arch": "arm64",
            "distro": {"name": "debian", "release": "trixie", "init": "systemd"},
            "created_at": "2026-10-07T00:00:00Z",
            "source": "https://github.com/ltbkq/Podroid-Debian",
            "license": "GPL-2.0-or-later",
        },
        "contract": {
            "version": 1,
            "markers": [
                "Loading kernel modules...", "Network found",
                "Starting SSH...", "Almost ready...", "Ready!",
            ],
            "ttys": {
                "hvc0": "login", "hvc1": "resize",
                "hvc2": "host-bridge", "ttyAMA0": "console",
            },
            "kernel": {"builtin_only": True, "min": "6.0", "max": "7.99", "image_sha256": ksha},
        },
        "capabilities": {
            "ssh": True, "x11": True, "desktop": False, "containers": False,
            "desktop_profile": False, "downloads_share": True, "usb_passthrough_host": True,
        },
        "accounts": {
            "ssh": [
                {"user": "root", "password": "123", "sudo": False},
                {"user": "ltbkq", "password": "123", "sudo": True},
            ],
            "default_user": "ltbkq",
            "ssh_port": 22,
        },
        "app": {"min_version_code": 1},
        "boot": {
            "machine": "virt",
            "cpu": "max",
            "append": "console=ttyAMA0 mitigations=off",
            "kernel_sha256": ksha,
            "initrd_sha256": isha,
            "drives": {"vda": "storage.img(rw,ext4)", "vdb": "<self>(ro,squashfs)"},
        },
        "checksums": {"rootfs_sha256": sha_hex(rootfs)},
        # 前向兼容：未知字段必须被忽略（§4 字段规则第一条），且覆盖 \uXXXX 转义解析
        "extra_top": {"nested": [1, 2, {"x": "y"}], "note": "forward-compat", "esc": ESC_MARKER},
    }


def dumps(m):
    text = json.dumps(m, ensure_ascii=False, indent=2)
    return text.replace(ESC_MARKER, "\\u4e2d").encode("utf-8")


# ---------------------------------------------------------------- 镜像构造（§2/§3）

def build_image(rootfs, manifest, kernel=None, initrd=None, patch=None, corrupt=None):
    """按 §2 布局构造完整 .img。

    patch(footer_bytearray)：写盘前改 footer 字段（构造负向样本）。
    corrupt：'manifest'|'kernel'|'initrd' —— 在 footer 落盘后翻转该段一字节
             （footer sha 保持原值 → 制造 sha 不匹配样本）。
    返回 (image_bytes, footer_bytes)。
    """
    R, M = len(rootfs), len(manifest)
    K = len(kernel) if kernel else 0
    I = len(initrd) if initrd else 0
    ko = align_up(R, MIB) if kernel else 0
    io = align_up(ko + K, MIB) if initrd else 0
    last = (io + I) if initrd else ((ko + K) if kernel else R)
    mo = align_up(last, MIB)
    fo = align_up(mo + M, 4096)
    fs = fo + FOOTER

    img = bytearray(fs)
    img[0:R] = rootfs
    if kernel:
        img[ko:ko + K] = kernel
    if initrd:
        img[io:io + I] = initrd
    img[mo:mo + M] = manifest

    flags = (1 if kernel else 0) | (2 if initrd else 0)
    fb = bytearray(FOOTER)
    fb[0:8] = MAGIC                                  # @0 magic
    struct.pack_into("<I", fb, 8, 1)                 # @8  format_version = 1
    struct.pack_into("<I", fb, 12, FOOTER)           # @12 footer_size = 4096
    struct.pack_into("<Q", fb, 16, fs)               # @16 file_size
    struct.pack_into("<Q", fb, 24, 0)                # @24 rootfs_offset = 0
    struct.pack_into("<Q", fb, 32, R)                # @32 rootfs_size = R
    fb[40:72] = sha(rootfs)                          # @40 rootfs_sha256
    struct.pack_into("<Q", fb, 72, mo)               # @72 manifest_offset（R3: seed 移除后重编号）
    struct.pack_into("<Q", fb, 80, M)                # @80 manifest_size
    fb[88:120] = sha(manifest)                       # @88 manifest_sha256
    struct.pack_into("<I", fb, 120, flags)           # @120 flags（bit0 kernel | bit1 initrd）
    struct.pack_into("<Q", fb, 124, ko)              # @124 kernel_offset
    struct.pack_into("<Q", fb, 132, K)               # @132 kernel_size
    fb[140:172] = sha(kernel) if kernel else bytes(32)
    struct.pack_into("<Q", fb, 172, io)              # @172 initrd_offset
    struct.pack_into("<Q", fb, 180, I)               # @180 initrd_size
    fb[188:220] = sha(initrd) if initrd else bytes(32)
    # 220..4087 reserved 全零（bytearray 默认零）
    fb[4088:4096] = MAGIC                            # @4088 magic_tail

    if patch:
        patch(fb, fs, {"rootfs_off": 0, "rootfs_size": R, "manifest_off": mo, "manifest_size": M,
                       "kernel_off": ko, "kernel_size": K, "initrd_off": io, "initrd_size": I})
    img[fo:fo + FOOTER] = fb

    if corrupt == "manifest":
        img[mo + 9] ^= 0xFF
    elif corrupt == "kernel":
        img[ko + 0x38] ^= 0xFF
    elif corrupt == "initrd":
        img[io + 4] ^= 0xFF
    return bytes(img), bytes(fb)


def build_footer_only(size=FOOTER * 2, patch=None):
    """仅 4096B 垫底 + footer 的极小文件（8192 = §3 规则 1 下限），
    用于只触发 format_version/footer_size/flags 这类前置检查、不触达段规则的负向样本。"""
    fb = bytearray(FOOTER)
    fb[0:8] = MAGIC
    struct.pack_into("<I", fb, 8, 1)
    struct.pack_into("<I", fb, 12, FOOTER)
    struct.pack_into("<Q", fb, 16, size)
    fb[4088:4096] = MAGIC
    if patch:
        patch(fb, size, {})
    return bytes(size - FOOTER) + bytes(fb)


# ---------------------------------------------------------------- main

def main():
    os.makedirs(SDIR, exist_ok=True)
    rootfs = make_rootfs()
    kernel = make_kernel()
    initrd = make_initrd()
    mf_full = dumps(manifest_dict(rootfs, kernel, initrd))
    mf_min = dumps(manifest_dict(rootfs))

    # ---- sources（供 extractPayload 逐字节比对） ----
    for name, data in [
        ("rootfs.src", rootfs), ("kernel.src", kernel), ("initrd.src", initrd),
        ("manifest-full.json", mf_full), ("manifest-min.json", mf_min),
    ]:
        wfile(os.path.join(SDIR, name), data)
        wsha(os.path.join(SDIR, name), data)

    images = {}

    def put(name, data, with_sha=False, final_len=None):
        path = os.path.join(FDIR, name)
        if final_len is None:
            wfile(path, data)
        else:
            wfile(path, data, final_len=final_len)
            data = data[:final_len]
        if with_sha:
            wsha(path, data)
        images[name] = path

    # ================= 正向样本 =================
    valid_min, fb_min = build_image(rootfs, mf_min)
    put("valid-min.img", valid_min, with_sha=True)
    put("valid-full.img", build_image(rootfs, mf_full, kernel, initrd)[0], with_sha=True)

    # 字段缺省（§4 缺省解释）：仅必需字段
    minimal = {
        "format": "vmdroid-system-image",
        "format_version": 1,
        "image": {"id": "debian-minimal-arm64", "arch": "arm64"},
    }
    put("minimal-manifest.img", build_image(rootfs, dumps(minimal))[0], with_sha=True)

    # accounts 块存在但无 ssh_port → 缺省 22（§4）
    no_port = manifest_dict(rootfs)
    del no_port["accounts"]["ssh_port"]
    put("no-ssh-port.img", build_image(rootfs, dumps(no_port))[0], with_sha=True)

    # ================= 负向样本 =================

    # -- NOT_AN_IMAGE --
    junk = bytes((i * 131 + 17) & 0xFF for i in range(8192))
    put("random-junk.img", junk)                                  # magic 不符、@0 非 hsqs
    put("zip-as-img.img", b"PK\x03\x04" + junk[:5996])            # §8: .img 扩展名但内容是 zip
    put("bare-squashfs.img", make_rootfs(8192))                   # §8: 裸 squashfs → 提示 mkimg 封装
    put("too-small.img", junk[:100])                              # size < 4096
    fw = bytearray(valid_min)
    fw[len(valid_min) - FOOTER:] = junk[:FOOTER]                  # §8: 尾部 4KiB 覆写
    put("footer-overwritten.img", bytes(fw))
    put("truncate-1byte.img", valid_min, final_len=len(valid_min) - 1)  # §8: 截断 1 字节

    # -- TRUNCATED --
    def patch_file_size_bigger(fb, fs, _ctx):
        struct.pack_into("<Q", fb, 16, fs + 4096)
    put("file-size-mismatch.img", build_image(rootfs, mf_min, patch=patch_file_size_bigger)[0])

    # 拼接：valid-min + 再写一份 footer → magic 仍匹配但 footer.file_size != 实际大小
    put("spliced-footer.img", valid_min + fb_min)

    # §3 规则 1：magic 匹配但 file_size < 8192 → 拒绝
    put("tiny-magic.img", build_footer_only(size=6000))

    # -- FORMAT_UNSUPPORTED --
    def patch_fmt2(fb, _fs, _ctx):
        struct.pack_into("<I", fb, 8, 2)
    put("format-version-2.img", build_footer_only(patch=patch_fmt2))

    def patch_fsz(fb, _fs, _ctx):
        struct.pack_into("<I", fb, 12, 4097)
    put("footer-size-bad.img", build_footer_only(patch=patch_fsz))

    def patch_flags4(fb, _fs, _ctx):
        struct.pack_into("<I", fb, 120, 0x4)     # 未知保留位
    put("unknown-flags.img", build_footer_only(patch=patch_flags4))

    # -- CORRUPT：flags ↔ 段不一致 --
    def patch_flag_kern(fb, _fs, _ctx):
        struct.pack_into("<I", fb, 120, 0x1)     # HAS_KERNEL 但 kernel_size == 0
    put("flags-kernel-mismatch.img", build_image(rootfs, mf_min, patch=patch_flag_kern)[0])

    def patch_flag_initrd(fb, _fs, _ctx):
        struct.pack_into("<I", fb, 120, 0x2)     # HAS_INITRD 但 initrd_size == 0
    put("flags-initrd-mismatch.img", build_image(rootfs, mf_min, patch=patch_flag_initrd)[0])

    # -- CORRUPT：偏移/边界/溢出/乱序 --
    def patch_rootfs_off(fb, _fs, _ctx):
        struct.pack_into("<Q", fb, 24, 4096)     # rootfs_offset 必须为 0
    put("rootfs-offset-nonzero.img", build_image(rootfs, mf_min, patch=patch_rootfs_off)[0])

    def patch_manifest_oor(fb, _fs, _ctx):
        struct.pack_into("<Q", fb, 72, 0x00FF_FFFF_FFFF_FF00)  # manifest 越界（不溢出 i64）
    put("manifest-out-of-range.img", build_image(rootfs, mf_min, patch=patch_manifest_oor)[0])

    def patch_manifest_overlap(fb, _fs, _ctx):
        struct.pack_into("<Q", fb, 72, 0)        # manifest 压回 rootfs 区 → 段序/重叠违规
    put("manifest-overlap.img", build_image(rootfs, mf_min, patch=patch_manifest_overlap)[0])

    def patch_kernel_overflow(fb, _fs, _ctx):
        # 溢出安全样本：offset > MAX - size（两者均 < 2^63，直接相加会溢出 i64/u64）
        struct.pack_into("<I", fb, 120, 0x1)
        struct.pack_into("<Q", fb, 124, 0x7FFF_FFFF_FFFF_FFC0)
        struct.pack_into("<Q", fb, 132, 0x100)
    put("kernel-overflow.img", build_image(rootfs, mf_min, patch=patch_kernel_overflow)[0])

    # -- MANIFEST_INVALID --
    bad_fmt = manifest_dict(rootfs)
    bad_fmt["format"] = "vmdroid-system-v2"
    put("manifest-format-wrong.img", build_image(rootfs, dumps(bad_fmt))[0])

    put("bad-manifest-json.img",
        build_image(rootfs, b'{"format": "vmdroid-system-image", "image": {broken')[0])

    # -- ARCH_MISMATCH --
    bad_arch = manifest_dict(rootfs)
    bad_arch["image"]["arch"] = "amd64"
    put("arch-amd64.img", build_image(rootfs, dumps(bad_arch))[0])

    # -- IMAGE_ID_INVALID --
    bad_id = manifest_dict(rootfs)
    bad_id["image"]["id"] = "Bad/Id!"           # 大写 + 分隔符 → 不匹配 ^[a-z0-9][a-z0-9._-]{0,63}$
    put("bad-image-id.img", build_image(rootfs, dumps(bad_id))[0])

    # -- SSH_CAPABILITY_MISSING --
    cap_off = manifest_dict(rootfs)
    cap_off["capabilities"]["ssh"] = False
    put("ssh-cap-false.img", build_image(rootfs, dumps(cap_off))[0])

    cap_missing = manifest_dict(rootfs)
    del cap_missing["capabilities"]["ssh"]      # 块存在但缺键 → fail-closed
    put("ssh-cap-missing-key.img", build_image(rootfs, dumps(cap_missing))[0])

    # -- SSH_PORT_INVALID --
    port_bad = manifest_dict(rootfs)
    port_bad["accounts"]["ssh_port"] = 2222
    put("ssh-port-2222.img", build_image(rootfs, dumps(port_bad))[0])

    port_str = manifest_dict(rootfs)
    port_str["accounts"]["ssh_port"] = "2222"   # 类型非整数 → fail-closed
    put("ssh-port-bad-type.img", build_image(rootfs, dumps(port_str))[0])

    # -- APP_TOO_OLD --
    old_app = manifest_dict(rootfs)
    old_app["app"]["min_version_code"] = 999999
    put("app-too-old.img", build_image(rootfs, dumps(old_app))[0], with_sha=True)

    # -- PAYLOAD_CORRUPT --
    put("manifest-flip.img", build_image(rootfs, mf_min, corrupt="manifest")[0])
    put("kernel-flip.img", build_image(rootfs, mf_full, kernel, initrd, corrupt="kernel")[0])
    put("initrd-flip.img", build_image(rootfs, mf_full, kernel, initrd, corrupt="initrd")[0])

    # 清单
    names = sorted(images)
    with open(os.path.join(FDIR, "MANIFEST.txt"), "w", encoding="utf-8") as f:
        f.write("# generated by gen_fixtures.py — do not edit by hand\n")
        for n in names:
            f.write("%s  %d\n" % (n, os.path.getsize(images[n])))
    print("generated %d images + %d sources in %s" % (len(names), len(os.listdir(SDIR)), FDIR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
