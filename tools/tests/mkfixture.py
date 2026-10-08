#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mkfixture.py —— 构造最小可校验的 VMDroid `.img` 测试夹具（仅供 tools/selftest.sh）。

按 IMAGE-FORMAT §2/§3 生成：rootfs[0,R) → 1MiB 对齐 kernel → 1MiB 对齐 initrd
→ 1MiB 对齐 manifest → 4KiB 对齐 footer(4096B)。footer 偏移按 R3 冻结表写入。

用法:
  mkfixture.py -o out.img [--rootfs debian-rootfs.squashfs] [--kernel K] [--initrd I]
               [--manifest manifest.json]

默认 kernel/initrd 为**假 payload**（仅结构合法，不可启动），rootfs 取
`--rootfs` squashfs 的前 `superblock.bytes_used` 字节（尾部 4096 对齐填充字节丢弃）。
"""

import argparse
import hashlib
import json
import os
import struct
import sys

MAGIC = b"VMDIMG01"
FOOTER = 4096
ALIGN_1M = 1024 * 1024
ARM64_MAGIC = 0x644D5241  # "ARM\x64"，arm64 Image 头 offset 0x38


def align_up(n, a):
    return (n + a - 1) // a * a


def fake_kernel(size=ALIGN_1M):
    """结构合法但不可启动的 arm64 Image 头 + 填充。"""
    buf = bytearray(size)
    struct.pack_into("<I", buf, 0, 0)              # code0
    struct.pack_into("<I", buf, 4, 0)              # code1
    struct.pack_into("<Q", buf, 8, 0x80000)        # text_offset
    struct.pack_into("<Q", buf, 16, size)          # image_size
    struct.pack_into("<Q", buf, 24, 0)             # flags
    struct.pack_into("<I", buf, 56, ARM64_MAGIC)   # magic "ARM\x64"
    note = b"VMDROID-FAKE-KERNEL-FOR-SELFTEST-ONLY-NOT-BOOTABLE"
    buf[64:64 + len(note)] = note
    return bytes(buf)


def fake_initrd(size=64 * 1024):
    """gzip 头 + 确定性伪随机数据（不可作为真实 initramfs）。"""
    buf = bytearray(b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x03")
    x = 0x12345678
    while len(buf) < size:
        x = (1103515245 * x + 12345) & 0xFFFFFFFF
        buf += struct.pack("<I", x)
    buf = buf[:size]
    buf[10:10 + 43] = b"VMDROID-FAKE-INITRD-SELFTEST-NOT-VALID-GZIP"
    return bytes(buf)


def squashfs_bytes_used(path):
    with open(path, "rb") as f:
        sb = f.read(48)
    if len(sb) < 48 or sb[0:4] != b"hsqs":
        raise SystemExit("mkfixture: %s 不是 squashfs（缺 hsqs superblock）" % path)
    return struct.unpack_from("<Q", sb, 40)[0]


def build_manifest(rootfs_sha, kernel_sha, initrd_sha):
    manifest = {
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
            "created_at": "2026-10-08T00:00:00Z",
            "source": "https://github.com/ltbkq/Podroid-Debian",
            "license": "GPL-2.0-or-later",
        },
        "contract": {
            "version": 1,
            "markers": ["Loading kernel modules...", "Network found",
                        "Starting SSH...", "Almost ready...", "Ready!"],
            "ttys": {"hvc0": "login", "hvc1": "resize",
                     "hvc2": "host-bridge", "ttyAMA0": "console"},
            "kernel": {"builtin_only": True, "min": "6.0", "max": "7.99",
                       "image_sha256": kernel_sha},
        },
        "capabilities": {
            "ssh": True, "x11": True, "desktop": False, "containers": False,
            "desktop_profile": False, "downloads_share": True,
            "usb_passthrough_host": True,
        },
        "accounts": {
            "ssh": [{"user": "root", "password": "123", "sudo": False},
                    {"user": "ltbkq", "password": "123", "sudo": True}],
            "default_user": "ltbkq",
            "ssh_port": 22,
        },
        "app": {"min_version_code": 1},
        "boot": {
            "machine": "virt",
            "cpu": "max",
            "append": "console=ttyAMA0 mitigations=off",
            "kernel_sha256": kernel_sha,
            "initrd_sha256": initrd_sha,
            "drives": {"vda": "storage.img(rw,ext4)", "vdb": "<self>(ro,squashfs)"},
        },
        "checksums": {"rootfs_sha256": rootfs_sha},
    }
    return (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def write_padding(out, cur, target):
    pad = target - cur
    if pad > 0:
        out.write(b"\x00" * pad)
    return target


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--rootfs", help="squashfs 源（取前 bytes_used 字节）；缺省生成 1MiB 假 rootfs")
    ap.add_argument("--kernel", help="arm64 Image 源；缺省生成 1MiB 假 kernel")
    ap.add_argument("--initrd", help="initrd 源；缺省生成 64KiB 假 initrd")
    ap.add_argument("--manifest", help="manifest JSON 源；缺省生成规格样例")
    ap.add_argument("--print-offsets", action="store_true", default=True)
    a = ap.parse_args()

    # --- 读取源数据（rootfs 只取 bytes_used） ---
    if a.rootfs:
        with open(a.rootfs, "rb") as f:
            rootfs = f.read(squashfs_bytes_used(a.rootfs))
    else:
        rootfs = b"hsqs" + b"\x00" * (ALIGN_1M - 4)
    kernel = open(a.kernel, "rb").read() if a.kernel else fake_kernel()
    initrd = open(a.initrd, "rb").read() if a.initrd else fake_initrd()

    if len(rootfs) == 0:
        raise SystemExit("mkfixture: rootfs 为空")
    if len(kernel) < ALIGN_1M:
        raise SystemExit("mkfixture: kernel_size < 1MiB（IMAGE-FORMAT §2 约束）")
    if len(initrd) < 4096:
        raise SystemExit("mkfixture: initrd_size < 4096（IMAGE-FORMAT §2 约束）")

    rootfs_sha = hashlib.sha256(rootfs).hexdigest()
    kernel_sha = hashlib.sha256(kernel).hexdigest()
    initrd_sha = hashlib.sha256(initrd).hexdigest()
    manifest = (open(a.manifest, "rb").read() if a.manifest
                else build_manifest(rootfs_sha, kernel_sha, initrd_sha))
    if not (1 <= len(manifest) <= 65536):
        raise SystemExit("mkfixture: manifest_size=%d 超出 [1,65536]" % len(manifest))
    manifest_sha = hashlib.sha256(manifest).hexdigest()

    # --- 布局 ---
    rootfs_off = 0
    kernel_off = align_up(len(rootfs), ALIGN_1M)
    initrd_off = align_up(kernel_off + len(kernel), ALIGN_1M)
    manifest_off = align_up(initrd_off + len(initrd), ALIGN_1M)
    footer_off = align_up(manifest_off + len(manifest), FOOTER)
    file_size = footer_off + FOOTER

    tmp = a.out + ".partial"
    with open(tmp, "wb") as out:
        out.write(rootfs)
        cur = write_padding(out, len(rootfs), kernel_off)
        out.write(kernel)
        cur = write_padding(out, kernel_off + len(kernel), initrd_off)
        out.write(initrd)
        cur = write_padding(out, initrd_off + len(initrd), manifest_off)
        out.write(manifest)
        cur = write_padding(out, manifest_off + len(manifest), footer_off)

        # --- footer（IMAGE-FORMAT §3，全小端，R3 冻结偏移） ---
        ft = bytearray(FOOTER)
        ft[0:8] = MAGIC
        struct.pack_into("<I", ft, 8, 1)              # format_version
        struct.pack_into("<I", ft, 12, FOOTER)        # footer_size
        struct.pack_into("<Q", ft, 16, file_size)     # file_size
        struct.pack_into("<Q", ft, 24, 0)             # rootfs_offset
        struct.pack_into("<Q", ft, 32, len(rootfs))   # rootfs_size
        ft[40:72] = bytes.fromhex(rootfs_sha)         # rootfs_sha256
        struct.pack_into("<Q", ft, 72, manifest_off)
        struct.pack_into("<Q", ft, 80, len(manifest))
        ft[88:120] = bytes.fromhex(manifest_sha)
        struct.pack_into("<I", ft, 120, 0x3)          # flags = HAS_KERNEL|HAS_INITRD
        struct.pack_into("<Q", ft, 124, kernel_off)
        struct.pack_into("<Q", ft, 132, len(kernel))
        ft[140:172] = bytes.fromhex(kernel_sha)
        struct.pack_into("<Q", ft, 172, initrd_off)
        struct.pack_into("<Q", ft, 180, len(initrd))
        ft[188:220] = bytes.fromhex(initrd_sha)
        ft[4088:4096] = MAGIC
        out.write(bytes(ft))
        out.flush()
        os.fsync(out.fileno())

    os.replace(tmp, a.out)

    # --- 汇总（selftest 解析用） ---
    print("fixture=%s" % a.out)
    print("file_size=%d" % file_size)
    print("flags=0x3")
    print("rootfs_offset=%d rootfs_size=%d rootfs_sha256=%s" % (rootfs_off, len(rootfs), rootfs_sha))
    print("kernel_offset=%d kernel_size=%d kernel_sha256=%s" % (kernel_off, len(kernel), kernel_sha))
    print("initrd_offset=%d initrd_size=%d initrd_sha256=%s" % (initrd_off, len(initrd), initrd_sha))
    print("manifest_offset=%d manifest_size=%d manifest_sha256=%s"
          % (manifest_off, len(manifest), manifest_sha))
    print("payload_source=kernel:%s initrd:%s rootfs:%s"
          % (a.kernel or "FAKE", a.initrd or "FAKE", a.rootfs or "FAKE"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
