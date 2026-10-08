#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 docs/IMAGE-FORMAT.md v1.0 (format_version=1) 独立构造 systemimage-selftest fixture。

与 codec-selftest/ 的 fixture 相互独立（不同工位目录，互不引用）；本目录额外需要
**多镜像组合**（不同 image_id / identity / contract / distro.init）来覆盖
SystemImageStore.activate 的 §5.2 五种 decision 与 BootGuard §7.4 的逐条触发样本。

产出（全部位于 systemimage-selftest/fixtures/，幂等，重复生成覆盖）：

  正向（可正常 install/activate）：
    debian-minimal.img    id=debian-minimal-arm64   identity=debian:trixie  contract=1 init=systemd rootfs=A
    debian-desktop.img    id=debian-desktop-arm64   identity=debian:trixie  contract=1 init=systemd rootfs=B  -> decision=upgrade
    alpine-minimal.img    id=alpine-minimal-arm64   identity=alpine:3.24    contract=1 init=openrc  rootfs=C  -> decision=identity
    debian-contract2.img  id=debian-contract2-arm64 identity=debian:trixie  contract=2 init=systemd rootfs=D  -> decision=contract
    debian-openrc.img     id=debian-openrc-arm64    identity=debian:trixie  contract=1 init=openrc  rootfs=E  -> decision=init
  负向（BootGuard §7.4 触发样本，需手工落位 + meta）：
    bad-arch.img     arch != arm64                 -> ARCH_MISMATCH
    bad-id.img       image.id = "Bad/Id!"          -> IMAGE_ID_INVALID
    ssh-off.img      capabilities.ssh = false      -> SSH_CAPABILITY_MISSING
    bad-port.img     accounts.ssh_port = 2222      -> SSH_PORT_INVALID
    app-old.img      app.min_version_code = 999999 -> APP_TOO_OLD
    format2.img      footer format_version = 2     -> FORMAT_UNSUPPORTED
    junk.img         无 footer magic               -> NOT_AN_IMAGE

用法：python3 gen_fixtures.py
"""
import hashlib
import json
import os
import random
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FDIR = os.path.join(HERE, "fixtures")

MAGIC = b"VMDIMG01"
FOOTER = 4096
MIB = 1 << 20
KIB = 1 << 10


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


# ---------------------------------------------------------------- 段内容

def make_rootfs(seed, size=64 * KIB):
    """确定性伪 squashfs：superblock 魔数 hsqs，其余按 seed 填充（不同镜像 rootfs 不同）。"""
    b = bytearray(size)
    b[0:4] = b"hsqs"
    struct.pack_into("<I", b, 4, 8)          # inodes
    struct.pack_into("<I", b, 12, 4096)      # block_size
    struct.pack_into("<H", b, 20, 1)         # compression = gzip
    struct.pack_into("<H", b, 28, 4)         # s_major
    struct.pack_into("<Q", b, 40, size)      # bytes_used
    rnd = random.Random(seed)
    for i in range(128, size):
        b[i] = rnd.randrange(256)
    return bytes(b)


# ---------------------------------------------------------------- manifest（§4）

def manifest_dict(image_id, identity, distro_name, distro_release, distro_init,
                  contract_version, rootfs, arch="arm64", ssh=True, ssh_port=22,
                  min_version_code=1):
    return {
        "format": "vmdroid-system-image",
        "format_version": 1,
        "image": {
            "id": image_id,
            "display_name": "%s %s" % (distro_name, distro_release),
            "identity": identity,
            "variant": "minimal",
            "version": "2026.10.0-r1",
            "system_version": 34,
            "arch": arch,
            "distro": {"name": distro_name, "release": distro_release, "init": distro_init},
            "created_at": "2026-10-07T00:00:00Z",
            "source": "https://github.com/ltbkq/Podroid-Debian",
            "license": "GPL-2.0-or-later",
        },
        "contract": {
            "version": contract_version,
            "markers": [
                "Loading kernel modules...", "Network found",
                "Starting SSH...", "Almost ready...", "Ready!",
            ],
            "ttys": {"hvc0": "login", "hvc1": "resize", "hvc2": "host-bridge", "ttyAMA0": "console"},
            "kernel": {"builtin_only": True, "min": "6.0", "max": "7.99", "image_sha256": "0" * 64},
        },
        "capabilities": {
            "ssh": ssh, "x11": True, "desktop": False, "containers": False,
            "desktop_profile": False, "downloads_share": True, "usb_passthrough_host": True,
        },
        "accounts": {
            "ssh": [
                {"user": "root", "password": "123", "sudo": False},
                {"user": "ltbkq", "password": "123", "sudo": True},
            ],
            "default_user": "ltbkq",
            "ssh_port": ssh_port,
        },
        "app": {"min_version_code": min_version_code},
        "boot": {
            "machine": "virt",
            "cpu": "max",
            "append": "console=ttyAMA0 mitigations=off",
            "kernel_sha256": "0" * 64,
            "initrd_sha256": "0" * 64,
            "drives": {"vda": "storage.img(rw,ext4)", "vdb": "<self>(ro,squashfs)"},
        },
        "checksums": {"rootfs_sha256": sha_hex(rootfs)},
    }


def dumps(m):
    return json.dumps(m, ensure_ascii=False, indent=2).encode("utf-8")


# ---------------------------------------------------------------- 镜像构造（§2/§3）

def build_image(rootfs, manifest):
    """按 §2 布局构造完整 .img：rootfs@0 → (1MiB 对齐) manifest → (4KiB 对齐) footer。"""
    R, M = len(rootfs), len(manifest)
    mo = align_up(R, MIB)
    fo = align_up(mo + M, 4096)
    fs = fo + FOOTER

    img = bytearray(fs)
    img[0:R] = rootfs
    img[mo:mo + M] = manifest

    fb = bytearray(FOOTER)
    fb[0:8] = MAGIC                              # @0 magic
    struct.pack_into("<I", fb, 8, 1)             # @8  format_version = 1
    struct.pack_into("<I", fb, 12, FOOTER)       # @12 footer_size = 4096
    struct.pack_into("<Q", fb, 16, fs)           # @16 file_size
    struct.pack_into("<Q", fb, 24, 0)            # @24 rootfs_offset = 0
    struct.pack_into("<Q", fb, 32, R)            # @32 rootfs_size
    fb[40:72] = sha(rootfs)                      # @40 rootfs_sha256
    struct.pack_into("<Q", fb, 72, mo)           # @72 manifest_offset
    struct.pack_into("<Q", fb, 80, M)            # @80 manifest_size
    fb[88:120] = sha(manifest)                   # @88 manifest_sha256
    struct.pack_into("<I", fb, 120, 0)           # @120 flags（无 kernel/initrd）
    fb[4088:4096] = MAGIC                        # @4088 magic_tail
    img[fo:fo + FOOTER] = fb
    return bytes(img)


def build_format2(size=FOOTER * 2):
    """format_version = 2 的极小文件（只触发前置检查：§7.4 #4 FORMAT_UNSUPPORTED）。"""
    fb = bytearray(FOOTER)
    fb[0:8] = MAGIC
    struct.pack_into("<I", fb, 8, 2)
    struct.pack_into("<I", fb, 12, FOOTER)
    struct.pack_into("<Q", fb, 16, size)
    fb[4088:4096] = MAGIC
    return bytes(size - FOOTER) + bytes(fb)


def build_junk(size=8192):
    """无 footer magic 的普通文件（§7.4 #3 NOT_AN_IMAGE）。"""
    rnd = random.Random(0x5EED)
    return bytes(rnd.randrange(256) for _ in range(size))


# ---------------------------------------------------------------- main

def main():
    os.makedirs(FDIR, exist_ok=True)

    positive = [
        # name, image_id, identity, distro, release, init, contract, seed
        ("debian-minimal.img", "debian-minimal-arm64", "debian:trixie", "debian", "trixie", "systemd", 1, 11),
        ("debian-desktop.img", "debian-desktop-arm64", "debian:trixie", "debian", "trixie", "systemd", 1, 12),
        ("alpine-minimal.img", "alpine-minimal-arm64", "alpine:3.24", "alpine", "3.24", "openrc", 1, 13),
        ("debian-contract2.img", "debian-contract2-arm64", "debian:trixie", "debian", "trixie", "systemd", 2, 14),
        ("debian-openrc.img", "debian-openrc-arm64", "debian:trixie", "debian", "trixie", "openrc", 1, 15),
    ]
    count = 0
    for name, iid, ident, dn, dr, di, cv, seed in positive:
        rootfs = make_rootfs(seed)
        mf = dumps(manifest_dict(iid, ident, dn, dr, di, cv, rootfs))
        wfile(os.path.join(FDIR, name), build_image(rootfs, mf))
        count += 1

    negative = [
        # name, manifest 覆盖, 期望 §7.4 reason
        ("bad-arch.img", dict(image_id="debian-badarch-arm64", arch="amd64"), "ARCH_MISMATCH"),
        ("bad-id.img", dict(image_id="Bad/Id!"), "IMAGE_ID_INVALID"),
        ("ssh-off.img", dict(image_id="debian-sshoff-arm64", ssh=False), "SSH_CAPABILITY_MISSING"),
        ("bad-port.img", dict(image_id="debian-badport-arm64", ssh_port=2222), "SSH_PORT_INVALID"),
        ("app-old.img", dict(image_id="debian-appold-arm64", min_version_code=999999), "APP_TOO_OLD"),
    ]
    for name, over, _reason in negative:
        kwargs = dict(
            image_id="debian-minimal-arm64", identity="debian:trixie", distro_name="debian",
            distro_release="trixie", distro_init="systemd", contract_version=1,
            rootfs=make_rootfs(21),
        )
        kwargs.update(over)
        rootfs = kwargs.pop("rootfs")
        mf = dumps(manifest_dict(rootfs=rootfs, **kwargs))
        wfile(os.path.join(FDIR, name), build_image(rootfs, mf))
        count += 1

    wfile(os.path.join(FDIR, "format2.img"), build_format2())
    wfile(os.path.join(FDIR, "junk.img"), build_junk())
    count += 2

    names = sorted(n for n in os.listdir(FDIR) if n.endswith(".img"))
    with open(os.path.join(FDIR, "MANIFEST.txt"), "w", encoding="utf-8") as f:
        f.write("# generated by gen_fixtures.py — do not edit by hand\n")
        for n in names:
            p = os.path.join(FDIR, n)
            f.write("%s  %d  %s\n" % (n, os.path.getsize(p), sha_hex(open(p, "rb").read())))
    print("generated %d images in %s" % (count, FDIR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
