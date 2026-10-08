#!/bin/sh
# imgboot.sh —— VMDroid `.img` PC 启动共享实现库（纯 POSIX sh + python3）
#
# 归属（DESIGN §11.2）：vmdroid 仓库 `tools/lib/imgboot.sh`，被两处 source：
#   1) vmdroid 仓库            tools/pc-run.sh           （用户入口，DESIGN §11.4 / R-16）
#   2) Podroid-Debian 仓库     tools/pc-boot-smoke.sh    （CI 冒烟，同一实现不复制两份）
# 提取与启动逻辑单一来源，任何一侧改动必须保持下述接口不变。
#
# ===================== 冻结接口（工位 A 依赖，原文如下） =====================
#   imgboot_verify      <img>                校验 footer：双 magic / file_size /
#                                            format_version / footer_size / flags(0x3) /
#                                            flags↔段一致性 / 段边界与段序 /
#                                            manifest sha256；stdout 打印关键字段；
#                                            成功 exit 0，失败 exit 1
#   imgboot_extract     <img> <outdir>       按 footer 偏移提取 kernel/initrd →
#                                            <outdir>/vmlinuz、<outdir>/initrd.img，
#                                            逐一校验 sha256；失败 exit 1
#   imgboot_qemu_argv   <img> <storage>      打印完整 qemu 命令行（单行、shell 转义、
#                                            含可执行名）；供 --dry-run 与真实启动共用
#   imgboot_storage_init <storage> <gb>      不存在或 0 字节 → truncate -s <gb>G +
#                                            mkfs.ext4 -F（DESIGN §11.4 方式 B 步骤 1）
#   imgboot_wait_ready  <logfile> <sec> [<pid>]
#                                            轮询 <logfile> 出现 "Ready!"；超时 exit 1；
#                                            可选 <pid>：进程先亡则立即 exit 1
# 可选辅助（新增，不影响上述 5 个接口）：
#   imgboot_prepare_run_dir <dir>            mkdir -p + 清理陈旧 *.sock（QEMU 会因
#                                            残留 socket 报 "Failed to bind"）
#   imgboot_log <msg>                        [t+X.Xs] 阶段日志（DESIGN §16 风格）
#   imgboot_die <msg>                        stderr 诊断 + exit 1
#
# 环境变量：
#   IMGBOOT_RUN_DIR      chardev socket 目录，默认 ./vmdroid-run
#   IMGBOOT_EXTRACT_DIR  kernel/initrd 提取目录，默认 $IMGBOOT_RUN_DIR/extract
#   IMGBOOT_ARGV_ONLY=1  imgboot_qemu_argv 只打印参数（不含可执行名）
#
# 失败语义：所有失败路径一律 `exit 1`（与接口约定一致）。需要捕获失败（负向测试）时
# 请在子 shell 中调用：`( imgboot_verify bad.img ) && echo ok`。
# ============================================================================

# ---------------------------------------------------------------- 内部工具
_ib_now() {
  _ib_n=$(date +%s%N 2>/dev/null) || _ib_n=
  case $_ib_n in
    ''|*[!0-9]*) _ib_n="$(date +%s)000000000" ;;
  esac
  printf '%s\n' "$_ib_n"
}

_ib_elapsed() {
  awk -v a="${IMGBOOT_T0:-0}" -v b="$(_ib_now)" \
    'BEGIN { d = b - a; if (d < 0) d = 0; printf "%.1f", d / 1e9 }'
}

# 绝对路径归一（不要求路径已存在）
_ib_abspath() {
  command -v python3 >/dev/null 2>&1 || imgboot_die "缺少 python3（apt install python3）"
  python3 -c 'import os, sys; print(os.path.abspath(sys.argv[1]))' "$1"
}

# POSIX shell 单引号转义，输出可直接 eval
_ib_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

IMGBOOT_T0=${IMGBOOT_T0:-$(_ib_now)}

imgboot_log() {
  printf '[t+%ss] %s\n' "$(_ib_elapsed)" "$*"
}

imgboot_die() {
  printf '[t+%ss] imgboot: error: %s\n' "$(_ib_elapsed)" "$*" >&2
  exit 1
}

_ib_python() {
  command -v python3 >/dev/null 2>&1 || imgboot_die "缺少 python3（apt install python3）"
  python3 - "$@" <<'IMGBOOT_PY'
import sys, os, struct, hashlib, json, re

# footer 字段偏移（IMAGE-FORMAT §3，R3 冻结，全小端；v1 已移除 seed 段）
#   0 magic(8) · 8 format_version(u32) · 12 footer_size(u32) · 16 file_size(u64)
#   24 rootfs_offset(u64) · 32 rootfs_size(u64) · 40 rootfs_sha256(32)
#   72 manifest_offset(u64) · 80 manifest_size(u64) · 88 manifest_sha256(32)
#   120 flags(u32, 合法掩码 0x3) · 124 kernel_offset · 132 kernel_size · 140 kernel_sha256
#   172 initrd_offset · 180 initrd_size · 188 initrd_sha256
#   220 reserved(3868) · 4088 magic_tail(8)
MAGIC = b"VMDIMG01"
FTR = 4096
FLAGS_MASK = 0x3
MAXU64 = (1 << 64) - 1
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


class Reject(Exception):
    pass


def reject(msg):
    raise Reject(msg)


def u64(ft, off):
    return struct.unpack_from("<Q", ft, off)[0]


def u32(ft, off):
    return struct.unpack_from("<I", ft, off)[0]


def read_exact(f, off, n):
    f.seek(off)
    b = f.read(n)
    if len(b) != n:
        reject("读取不足: offset=%d want=%d got=%d" % (off, n, len(b)))
    return b


def parse(path):
    """按 IMAGE-FORMAT §5 校验 footer/manifest，返回信息字典；失败 raise Reject。"""
    try:
        size = os.path.getsize(path)
    except OSError as e:
        reject("无法访问 %s: %s" % (path, e))
    if size < FTR:
        reject("文件过小: %d 字节 (< 4096)" % size)

    f = open(path, "rb")
    ft = read_exact(f, size - FTR, FTR)

    # 1) 双 magic
    if ft[0:8] != MAGIC or ft[4088:4096] != MAGIC:
        m0, m1 = ft[0:8] == MAGIC, ft[4088:4096] == MAGIC
        if m0 != m1:
            # 单侧完好 → footer 局部被改写（另一侧由下面的分支解释）
            f.close()
            bad = "magic" if not m0 else "magic_tail"
            reject("footer 双 magic 不一致: %s 损坏（footer 被改写）" % bad)
        k = ft.find(MAGIC)
        if 0 < k <= 32 and k + 24 <= len(ft):
            # 整体错位（典型：文件被截断 n 字节，footer 左移 n）
            cand = struct.unpack_from("<Q", ft, k + 16)[0]
            f.close()
            if cand > size:
                reject("file_size=%d > 实际大小 %d（footer 错位 → 文件被截断）" % (cand, size))
            reject("footer magic 错位（offset=%d，footer 损坏）" % k)
        f.seek(0)
        head = f.read(4)
        f.close()
        if head == b"hsqs":
            reject("裸 squashfs（缺 footer）——请用 Podroid-Debian/tools/mkimg.sh 封装为 .img")
        reject("非 VMDroid 系统镜像: footer 双 magic 不符")
    # 2) file_size（截断/拼接检测）
    file_size = u64(ft, 16)
    if file_size != size:
        reject("file_size=%d != 实际大小 %d（截断或拼接）" % (file_size, size))
    if file_size < 8192:
        f.close()
        reject("file_size=%d (< 8192)" % file_size)
    # 3) format_version / footer_size
    fmt_ver = u32(ft, 8)
    if fmt_ver > 1:
        reject("format_version=%d > 1（格式过新，请升级工具）" % fmt_ver)
    if fmt_ver < 1:
        reject("format_version=%d 非法（v1 应为 1）" % fmt_ver)
    footer_size = u32(ft, 12)
    if footer_size != FTR:
        reject("footer_size=%d != 4096" % footer_size)
    # 4) flags 掩码 0x3 + flags↔段一致性
    flags = u32(ft, 120)
    if flags & ~FLAGS_MASK:
        reject("unknown flags 0x%x（v1 合法位 = bit0 kernel | bit1 initrd，掩码 0x3）" % flags)
    k_off, k_size = struct.unpack_from("<QQ", ft, 124)
    i_off, i_size = struct.unpack_from("<QQ", ft, 172)
    if flags & 0x1:
        if k_size == 0:
            reject("flags.bit0 置位但 kernel_size == 0")
    else:
        if k_off != 0 or k_size != 0:
            reject("flags.bit0 未置位但 kernel 段非零 (offset=%d size=%d)" % (k_off, k_size))
    if flags & 0x2:
        if i_size == 0:
            reject("flags.bit1 置位但 initrd_size == 0")
    else:
        if i_off != 0 or i_size != 0:
            reject("flags.bit1 未置位但 initrd 段非零 (offset=%d size=%d)" % (i_off, i_size))
    # 5) 段边界 + 段序（rootfs → kernel → initrd → manifest → footer），溢出安全
    r_off, r_size = struct.unpack_from("<QQ", ft, 24)
    if r_off != 0:
        reject("rootfs_offset=%d != 0" % r_off)
    if r_size == 0:
        reject("rootfs_size == 0")
    m_off, m_size = struct.unpack_from("<QQ", ft, 72)
    if not (1 <= m_size <= 65536):
        reject("manifest_size=%d 超出合法区间 [1, 65536]" % m_size)
    limit = file_size - FTR
    segs = [("rootfs", r_off, r_size)]
    if flags & 0x1:
        segs.append(("kernel", k_off, k_size))
    if flags & 0x2:
        segs.append(("initrd", i_off, i_size))
    segs.append(("manifest", m_off, m_size))
    prev_end = 0
    for name, off, sz in segs:
        if off > MAXU64 - sz:
            reject("%s 段偏移溢出: offset=%d size=%d" % (name, off, sz))
        if off + sz > limit:
            reject("%s 段越界: [%d, %d) > %d" % (name, off, off + sz, limit))
        if off < prev_end:
            reject("%s 段与前段重叠/乱序: offset=%d < 前段末尾 %d" % (name, off, prev_end))
        prev_end = off + sz
    # 6) manifest sha256
    man = read_exact(f, m_off, m_size)
    f.close()
    manifest_sha = hashlib.sha256(man).hexdigest()
    if bytes.fromhex(manifest_sha) != bytes(ft[88:120]):
        reject("manifest_sha256 不匹配（manifest 被改写/损坏）")
    # 7) manifest JSON 语义（缺省解释按 IMAGE-FORMAT §4）
    try:
        meta = json.loads(man.decode("utf-8"))
    except Exception as e:
        reject("manifest 不是合法 UTF-8 JSON: %s" % e)
    if not isinstance(meta, dict):
        reject("manifest 不是 JSON object")
    if meta.get("format") != "vmdroid-system-image":
        reject("manifest.format != 'vmdroid-system-image'")
    image = meta.get("image")
    if not isinstance(image, dict):
        image = {}
    iid = image.get("id") or ""
    if not ID_RE.match(iid):
        reject("image.id 非法（须匹配 ^[a-z0-9][a-z0-9._-]{0,63}$）: %r" % iid)
    arch = image.get("arch") or ""
    if arch != "arm64":
        reject("arch=%r != 'arm64'（qemu-system-aarch64 无法启动）" % arch)
    caps = meta.get("capabilities")
    ssh_cap = True if not isinstance(caps, dict) else bool(caps.get("ssh", True))
    if not ssh_cap:
        reject("capabilities.ssh == false（SSH 是启动契约硬性要求）")
    acc = meta.get("accounts")
    port = 22 if not isinstance(acc, dict) else acc.get("ssh_port", 22)
    if isinstance(port, str) and port.isdigit():
        port = int(port)
    if port != 22:
        reject("accounts.ssh_port=%s != 22（DESIGN §4.8 端口规范）" % port)
    contract = meta.get("contract")
    if not isinstance(contract, dict):
        contract = {}
    app = meta.get("app")
    if not isinstance(app, dict):
        app = {}

    info = {
        "path": path,
        "size": size,
        "file_size": file_size,
        "format_version": fmt_ver,
        "footer_size": footer_size,
        "flags": flags,
        "manifest_sha256": bytes(ft[88:120]).hex(),
        "segs": {},
        "image": image,
        "capabilities_ssh": ssh_cap,
        "ssh_port": port,
        "contract_version": contract.get("version"),
        "app_min_version_code": app.get("min_version_code"),
        "boot_append": (meta.get("boot") or {}).get("append")
        if isinstance(meta.get("boot"), dict) else None,
    }
    for name, off, sz in segs:
        entry = {"offset": off, "size": sz, "sha256": None}
        info["segs"][name] = entry
    for name, sha_off in (("rootfs", 40), ("kernel", 140), ("initrd", 188)):
        if name in info["segs"]:
            info["segs"][name]["sha256"] = bytes(ft[sha_off:sha_off + 32]).hex()
    info["segs"]["manifest"]["sha256"] = manifest_sha
    return info


def fmt_flags(fl):
    names = []
    if fl & 0x1:
        names.append("HAS_KERNEL")
    if fl & 0x2:
        names.append("HAS_INITRD")
    return "0x%x%s" % (fl, (" (" + "|".join(names) + ")") if names else "")


def field(label, value):
    print("  %-16s: %s" % (label, value))


def emit(info):
    print("imgboot: verify OK — %s" % info["path"])
    field("file_size", "%d" % info["file_size"])
    field("format_version", "%d" % info["format_version"])
    field("footer_size", "%d" % info["footer_size"])
    field("flags", fmt_flags(info["flags"]))
    for name in ("rootfs", "kernel", "initrd", "manifest"):
        seg = info["segs"].get(name)
        if seg is None:
            field(name, "(absent)")
        else:
            field(name, "offset=%d size=%d sha256=%s"
                  % (seg["offset"], seg["size"], seg["sha256"] or "-"))
    img = info["image"]
    field("image.id", img.get("id"))
    field("image.identity", img.get("identity"))
    field("image.variant", img.get("variant"))
    field("image.version", img.get("version"))
    field("image.system_version", img.get("system_version"))
    field("image.arch", img.get("arch"))
    field("contract.version", info["contract_version"])
    field("capabilities.ssh", str(info["capabilities_ssh"]).lower())
    field("accounts.ssh_port", str(info["ssh_port"]))
    field("app.min_version_code", info["app_min_version_code"])
    field("boot.append", info["boot_append"])


def do_verify(path):
    emit(parse(path))


def do_extract(path, outdir):
    info = parse(path)
    flags = info["flags"]
    if (flags & FLAGS_MASK) != FLAGS_MASK:
        reject("footer flags=%s 缺少 kernel/initrd payload，PC 端无法启动（R-16）"
               % fmt_flags(flags))
    try:
        os.makedirs(outdir, exist_ok=True)
    except OSError as e:
        reject("无法创建输出目录 %s: %s" % (outdir, e))
    with open(path, "rb") as f:
        for seg_name, out_name in (("kernel", "vmlinuz"), ("initrd", "initrd.img")):
            seg = info["segs"][seg_name]
            data = read_exact(f, seg["offset"], seg["size"])
            digest = hashlib.sha256(data)
            if digest.hexdigest() != seg["sha256"]:
                reject("%s payload sha256 不匹配（kernel/initrd 损坏）" % seg_name)
            dest = os.path.join(outdir, out_name)
            tmp = dest + ".tmp"
            with open(tmp, "wb") as o:
                o.write(data)
                o.flush()
                os.fsync(o.fileno())
            os.replace(tmp, dest)
            field(out_name, "%s (%d bytes, sha256=%s)" % (dest, len(data), digest.hexdigest()))
    print("imgboot: extract OK — %s" % path)


def main(argv):
    if len(argv) < 2:
        raise Reject("usage: imgboot.sh <verify|extract> ...")
    mode = argv[1]
    try:
        if mode == "verify":
            if len(argv) != 3:
                raise Reject("usage: verify <img>")
            do_verify(argv[2])
        elif mode == "extract":
            if len(argv) != 4:
                raise Reject("usage: extract <img> <outdir>")
            do_extract(argv[2], argv[3])
        else:
            raise Reject("unknown mode: %r" % mode)
    except Reject as e:
        sys.stdout.flush()
        sys.stderr.write("imgboot: %s FAILED: %s\n" % (mode, e))
        sys.exit(1)
    except OSError as e:
        sys.stdout.flush()
        sys.stderr.write("imgboot: %s FAILED: I/O error: %s\n" % (mode, e))
        sys.exit(1)
    except Exception as e:  # 兜底：不留裸 traceback
        sys.stdout.flush()
        sys.stderr.write("imgboot: %s FAILED: %s: %s\n" % (mode, type(e).__name__, e))
        sys.exit(1)


main(sys.argv)
IMGBOOT_PY
}

# ---------------------------------------------------------------- 冻结接口

# 校验 footer：双 magic / file_size / format_version / footer_size / flags(0x3) /
# flags↔段一致性 / 段边界与段序 / manifest sha256；stdout 打印关键字段；exit 0/1
imgboot_verify() {
  [ $# -eq 1 ] || imgboot_die "imgboot_verify <img>"
  [ -e "$1" ] || imgboot_die "镜像不存在: $1"
  imgboot_log "verifying footer: $1"
  _ib_python verify "$1" || exit 1
}

# 按 footer 偏移提取 kernel/initrd → <outdir>/vmlinuz、<outdir>/initrd.img，逐一校验 sha256
imgboot_extract() {
  [ $# -eq 2 ] || imgboot_die "imgboot_extract <img> <outdir>"
  [ -e "$1" ] || imgboot_die "镜像不存在: $1"
  imgboot_log "extracting kernel/initrd -> $2"
  _ib_python extract "$1" "$2" || exit 1
}

# 打印完整 qemu-system-aarch64 命令行：单行、POSIX shell 单引号转义、含可执行名。
# 设 IMGBOOT_ARGV_ONLY=1 可只输出参数（不含可执行名）。
imgboot_qemu_argv() {
  [ $# -eq 2 ] || imgboot_die "imgboot_qemu_argv <img> <storage>"
  ib_img=$(_ib_abspath "$1")
  ib_storage=$(_ib_abspath "$2")
  ib_run=$(_ib_abspath "${IMGBOOT_RUN_DIR:-./vmdroid-run}")
  ib_ext=$(_ib_abspath "${IMGBOOT_EXTRACT_DIR:-$ib_run/extract}")
  ib_kernel=$ib_ext/vmlinuz
  ib_initrd=$ib_ext/initrd.img

  # Linux AF_UNIX sun_path ≤ 107 字节（含结尾 NUL 为 108）
  for ib_s in terminal.sock ctrl.sock host.sock; do
    ib_p=$ib_run/$ib_s
    if [ "${#ib_p}" -gt 107 ]; then
      imgboot_die "chardev socket 路径超长（限 107 字节，实际 ${#ib_p}）: $ib_p
  指引：用更短的运行目录，例如 tools/pc-run.sh --run-dir /tmp/vr <img>，或 cd 到短路径后用相对目录"
    fi
  done

  # 逗号会截断 QEMU -drive / -chardev 选项（DESIGN §6.1 同源风险）
  for ib_check in "$ib_img" "$ib_storage" "$ib_run"; do
    case $ib_check in
      *,*) imgboot_die "路径不得包含逗号（会截断 QEMU 选项）: $ib_check" ;;
    esac
  done

  ib_line=
  ib_add() { ib_line="$ib_line $(_ib_quote "$1")"; }

  if [ ! -e "$ib_kernel" ] || [ ! -e "$ib_initrd" ]; then
    printf 'imgboot: note: kernel/initrd 尚未提取（%s），真实启动前会由 imgboot_extract 生成\n' \
      "$ib_ext" >&2
  fi

  ib_add "-M"; ib_add "virt,gic-version=3"
  ib_add "-cpu"; ib_add "max"
  ib_add "-accel"; ib_add "tcg,thread=multi"
  ib_add "-display"; ib_add "none"
  ib_add "-serial"; ib_add "mon:stdio"
  ib_add "-smp"; ib_add "4"
  ib_add "-m"; ib_add "4096"
  ib_add "-kernel"; ib_add "$ib_kernel"
  ib_add "-initrd"; ib_add "$ib_initrd"
  ib_add "-append"; ib_add "console=ttyAMA0 mitigations=off androidip=10.0.2.15 podroid.x11.dpi=96"
  ib_add "-drive"; ib_add "file=$ib_storage,if=none,id=drive1,format=raw,discard=unmap,detect-zeroes=unmap"
  ib_add "-device"; ib_add "virtio-blk-pci,drive=drive1"
  ib_add "-drive"; ib_add "file=$ib_img,if=none,id=drive2,format=raw,readonly=on"
  ib_add "-device"; ib_add "virtio-blk-pci,drive=drive2"
  ib_add "-netdev"; ib_add "user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:9922-:22,hostfwd=tcp:127.0.0.1:5900-:5900,hostfwd=tcp:127.0.0.1:4713-:4713"
  ib_add "-device"; ib_add "virtio-net-pci,netdev=net0"
  # hvc0-2：缺失则 guest podroid-getty@hvc0 无限重启空烧 CPU（R3: B-R2-8）
  ib_add "-device"; ib_add "virtio-serial-pci"
  ib_add "-chardev"; ib_add "socket,id=ch0,path=$ib_run/terminal.sock,server=on,wait=off"
  ib_add "-device"; ib_add "virtconsole,chardev=ch0"
  ib_add "-chardev"; ib_add "socket,id=ch1,path=$ib_run/ctrl.sock,server=on,wait=off"
  ib_add "-device"; ib_add "virtconsole,chardev=ch1"
  ib_add "-chardev"; ib_add "socket,id=ch2,path=$ib_run/host.sock,server=on,wait=off"
  ib_add "-device"; ib_add "virtconsole,chardev=ch2"

  if [ "${IMGBOOT_ARGV_ONLY:-0}" = "1" ]; then
    printf '%s\n' "${ib_line# }"
  else
    printf 'qemu-system-aarch64%s\n' "$ib_line"
  fi
}

# 不存在或 0 字节 → truncate -s <gb>G + mkfs.ext4 -F（DESIGN §11.4 方式 B 步骤 1）
imgboot_storage_init() {
  [ $# -eq 2 ] || imgboot_die "imgboot_storage_init <storage> <gb>"
  ib_st=$1
  ib_gb=$2
  case $ib_gb in
    ''|*[!0-9]*) imgboot_die "非法容量（GiB）: $ib_gb" ;;
  esac
  [ "$ib_gb" -gt 0 ] || imgboot_die "容量必须 > 0 GiB: $ib_gb"
  ib_dir=$(dirname -- "$ib_st")
  if [ ! -d "$ib_dir" ]; then
    mkdir -p "$ib_dir" || imgboot_die "无法创建目录: $ib_dir"
  fi
  if [ -s "$ib_st" ]; then
    ib_sz=$(stat -c %s -- "$ib_st" 2>/dev/null || wc -c < "$ib_st")
    imgboot_log "reusing existing storage: $ib_st ($(( ib_sz / 1048576 )) MiB), skip mkfs"
    return 0
  fi
  command -v truncate >/dev/null 2>&1 || imgboot_die "缺少 truncate（apt install coreutils）"
  command -v mkfs.ext4 >/dev/null 2>&1 || imgboot_die "缺少 mkfs.ext4（apt install e2fsprogs）"
  imgboot_log "creating storage: $ib_st (${ib_gb}G sparse + mkfs.ext4 -F)"
  truncate -s "${ib_gb}G" "$ib_st" || imgboot_die "truncate 失败: $ib_st"
  mkfs.ext4 -F -q "$ib_st" || imgboot_die "mkfs.ext4 失败: $ib_st"
  imgboot_log "storage ready: $ib_st"
}

# 轮询 <logfile> 出现 "Ready!"；超时 exit 1。可选第三参 <pid>：进程先亡则立即 exit 1。
imgboot_wait_ready() {
  if [ $# -lt 2 ] || [ $# -gt 3 ]; then
    imgboot_die "imgboot_wait_ready <logfile> <sec> [<pid>]"
  fi
  ib_log=$1
  ib_sec=$2
  ib_pid=${3:-}
  case $ib_sec in
    ''|*[!0-9]*) imgboot_die "非法超时秒数: $ib_sec" ;;
  esac
  ib_wait_t0=$(_ib_now)
  ib_i=0
  ib_total=$(( ib_sec * 2 ))
  imgboot_log "waiting for 'Ready!' in $ib_log (timeout ${ib_sec}s)"
  while [ "$ib_i" -lt "$ib_total" ]; do
    if [ -f "$ib_log" ] && grep -q 'Ready!' "$ib_log" 2>/dev/null; then
      ib_e=$(awk -v a="$ib_wait_t0" -v b="$(_ib_now)" \
        'BEGIN { printf "%.1f", (b - a) / 1e9 }')
      imgboot_log "Ready! detected after ${ib_e}s of waiting"
      return 0
    fi
    if [ -n "$ib_pid" ] && ! kill -0 "$ib_pid" 2>/dev/null; then
      imgboot_die "进程 pid=$ib_pid 已先退出，未出现 Ready! —— 见 $ib_log"
    fi
    sleep 0.5
    ib_i=$(( ib_i + 1 ))
  done
  imgboot_die "超时 ${ib_sec}s 未在 $ib_log 中出现 'Ready!'"
}

# 运行目录准备：mkdir -p + 清理陈旧 chardev socket（QEMU 残留 socket 会 "Failed to bind"）
imgboot_prepare_run_dir() {
  [ $# -eq 1 ] || imgboot_die "imgboot_prepare_run_dir <dir>"
  mkdir -p "$1" || imgboot_die "无法创建运行目录: $1"
  rm -f -- "$1/terminal.sock" "$1/ctrl.sock" "$1/host.sock"
  imgboot_log "run dir ready: $1 (stale *.sock removed)"
}
