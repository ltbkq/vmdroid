#!/usr/bin/env python3
"""mkdisk-content-id.py — IMAGE-FORMAT-v2 §4.3 content_id 参考实现（生成侧）

    content_id = "sha256:" + hex(sha256(concat(lines)))

    line := 普通文件  "<path>\0<mode>\0<uid>\0<gid>\0<sha256(file)>\n"
          | 目录      "<path>\0<mode>\0<uid>\0<gid>\0DIR\n"
          | 符号链接  "<path>\0<mode>\0<uid>\0<gid>\0LINK:<target>\n"

    · path  = 相对 rootfs 的路径，无前导 "/"，按码点字典序全局排序
    · mode  = st_mode 完整八进制（如 "100644"/"40755"/"120777"）
    · 排除（§4.3 白名单，运行期必然变化项）：
        /etc/machine-id  /root/.bash_history
        整棵子树：/var/**  /run/**  /tmp/**  /home/**  /lost+found/**
        前缀匹配：/etc/ssh/ssh_host_*  /boot/vmlinuz-*  /boot/initrd.img-*
      （子树排除 = 连目录项本身一并跳过；/etc/machine-id 等为精确单文件）
      白名单是**冻结**规格：增删即 format_version 讨论项。
"""
import hashlib
import os
import stat
import sys

# 注意：排除匹配用**相对路径**（无前导 "/"，与遍历产物一致）。
# 2026-10-12 修复：原 EXACT 集写作 "/etc/machine-id" 带前导斜杠，而 rel 不带 →
# 这两项从未命中（B 线夹具交叉验证抓到；子树/前缀规则不受影响）。
EXACT = {"etc/machine-id", "root/.bash_history"}
SUBTREES = ("var", "run", "tmp", "home", "lost+found", "dev")  # dev: R9 修订(2026-10-12) 设备节点不进哈希
PREFIXES = (("etc", "ssh"), ("boot",))  # 需要文件名前缀判断的目录


def excluded(rel_parts, name):
    rel = "/".join(rel_parts)
    if rel_parts and rel_parts[0] in SUBTREES:
        return True
    if rel in EXACT:
        return True
    if len(rel_parts) == 3 and rel_parts[:2] == ["etc", "ssh"] and name.startswith("ssh_host_"):
        return True
    if len(rel_parts) == 2 and rel_parts[0] == "boot" and (
        name.startswith("vmlinuz-") or name.startswith("initrd.img-")
    ):
        return True
    return False


def main(root):
    entries = []  # (relpath, kind, st, extra)

    def walk(abspath, parts):
        with os.scandir(abspath) as it:
            for e in it:
                rel_parts = parts + [e.name]
                if excluded(rel_parts, e.name):
                    continue
                st = e.stat(follow_symlinks=False)
                rel = "/".join(rel_parts)
                if stat.S_ISLNK(st.st_mode):
                    entries.append((rel, "link", st, os.readlink(e.path)))
                elif stat.S_ISDIR(st.st_mode):
                    entries.append((rel, "dir", st, None))
                    walk(e.path, rel_parts)
                elif stat.S_ISREG(st.st_mode):
                    entries.append((rel, "file", st, e.path))
                elif stat.S_ISSOCK(st.st_mode) or stat.S_ISFIFO(st.st_mode) or \
                     stat.S_ISCHR(st.st_mode) or stat.S_ISBLK(st.st_mode):
                    # R9 扩展（2026-10-12，arch 的 S.gpg-agent socket 触发）：
                    # socket/fifo/device 无内容可哈希且属运行期产物（gpg-agent socket
                    # 指向已死路径；devtmpfs 运行期覆盖设备节点）→ 跳过（确定性不变）。
                    continue
                else:
                    raise SystemExit("content_id: 未知文件类型 %s" % e.path)

    walk(root, [])
    entries.sort(key=lambda t: t[0])

    h = hashlib.sha256()
    for rel, kind, st, extra in entries:
        mode = format(st.st_mode, "o")
        head = "%s\0%s\0%d\0%d\0" % (rel, mode, st.st_uid, st.st_gid)
        if kind == "dir":
            h.update((head + "DIR\n").encode())
        elif kind == "link":
            h.update((head + "LINK:" + extra + "\n").encode())
        else:
            fh = hashlib.sha256()
            with open(extra, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    fh.update(chunk)
            h.update((head + fh.hexdigest() + "\n").encode())

    print("sha256:" + h.hexdigest())


if __name__ == "__main__":
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        sys.exit("usage: mkdisk-content-id.py <rootfs-dir>")
    main(sys.argv[1])
