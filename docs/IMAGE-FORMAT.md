# VMDroid 系统镜像格式规格 (`.img`)

**版本：format_version = 1** · 关联设计文档 [DESIGN.md](DESIGN.md)

VMDroid 的系统是一个**单文件** `*.img`。它既是一个**可直接挂载的 squashfs**
（作为 guest 的 `vdb`），又在尾部携带自描述元数据与 kernel/initrd payload（PC 启动）。

> **v1 决策（2026-10-08）**：**不携带 vda 种子**。上游 `init-podroid` 已实现
> "全零盘首启自动 `mkfs.ext4`"，恢复出厂只需把 `storage.img` 清零 —— 种子段
> 对 v1 是纯负担（无内容可提供 + 增体积 + 稀疏提取复杂度）。原 seed 字段
> （footer bit0、72–119 字节）在 v1 中**不存在**；未来若需预置数据盘 → `format_version = 2`。

---

## 1. 设计目标

| # | 目标 | 手段 |
|---|---|---|
| 1 | 零拷贝启动 | 文件 0x0 就是 squashfs superblock，可直接 `-drive ...,readonly=on` / `addDisk(writable=false)` |
| 2 | 单文件自描述 | 尾部 footer 指向 manifest（JSON），含 identity/版本/能力/账户 |
| 3 | 可校验 | 全文件 sha256（下载时算）+ 每个 payload 的 sha256（footer 内） |
| 4 | 恢复出厂零依赖 | vda 不在镜像内（决策 2026-10-08 移除 seed）：`storage.img` 清零后由 `init-podroid` 自动 `mkfs`，见 DESIGN §4.3 |
| 5 | 单一格式（**N4**） | **只接受 `.img`**：footer 必需；裸 `.squashfs` 一律拒绝（无 manifest 就无 identity/校验） |
| 6 | 不改启动契约 | 只提供 `vdb`；`vda`（`storage.img`）由应用创建，见 DESIGN §2.3 |

---

## 2. 文件布局

```
offset
0x0  ┌─────────────────────────────────────────────┐
     │ rootfs.squashfs  (superblock magic "hsqs")  │  ← vdb，guest 挂载为 /mnt/lower
     │ ... bytes_used = R ...                      │
     ├─────────────────────────────────────────────┤
     │ 零填充 → 对齐到 1 MiB 边界                    │
     ├─────────────────────────────────────────────┤
     │ kernel payload (arm64 Image) [flags.bit0]   │  ← ★R-16 PC 端 -kernel
     │ ... bytes = K ...                           │
     ├─────────────────────────────────────────────┤
     │ 零填充 → 对齐到 1 MiB 边界                    │
     ├─────────────────────────────────────────────┤
     │ initrd payload             [flags.bit1]     │  ← ★R-16 PC 端 -initrd
     │ ... bytes = I ...                           │
     ├─────────────────────────────────────────────┤
     │ 零填充 → 对齐到 1 MiB 边界                    │
     ├─────────────────────────────────────────────┤
     │ manifest JSON (UTF-8)                       │  ← 描述镜像语义
     │ ... bytes = M ...                           │
     ├─────────────────────────────────────────────┤
     │ 零填充 → 对齐到 4 KiB                         │
     ├─────────────────────────────────────────────┤
     │ footer  (固定 4096 B，文件最后 4096 字节)      │  ← 二进制真值：偏移/长度/sha256
     └─────────────────────────────────────────────┘ file_size
```

约束：

| 项 | 约束 |
|---|---|
| `rootfs_offset` | 恒为 `0`（format_version 1） |
| `rootfs_size` | = squashfs `superblock.bytes_used` = `R` |
| 段对齐 | `kernel`、`initrd`、`manifest` 均从 **1 MiB** 边界开始（R2: A-R2-17/B-R2-13）；`manifest` 之后零填充至 **4 KiB** 边界即 `footer_offset`，`file_size = footer_offset + 4096`（**footer 不要求 1 MiB 对齐**，R1: A-R1-8/B-R1-4） |
| `manifest_size` | `1 ≤ M ≤ 65536` |
| `kernel_size` | `0` 或 `≥ 1 MiB`（arm64 `Image`，通常 10–30 MiB；须与 `flags.bit0` 一致） |
| `initrd_size` | `0` 或 `≥ 4096`（须与 `flags.bit1` 一致） |
| 填充 | 所有洞必须全零（读取方必须容忍非零垃圾：容忍即可，不校验） |
| 压缩 | 整文件**不额外压缩**（squashfs 自身已 zstd/xz 压缩；外层压缩会破坏直挂） |

> **为什么直挂可行**：squashfs superblock 位于 0x0，块表用绝对偏移，内核唯一尺寸校验是
> `bytes_used ≤ 设备大小`（`fs/squashfs/super.c`），因此**尾随数据安全、截断危险**。
> P0 必须在真机 QEMU + AVF 各验证一次（DESIGN §13.3）。
> 若任一后端不接受尾随数据 → 走 `extract` 模式（DESIGN §4.3），格式本身不变。

---

## 3. Footer（文件最后 4096 字节，全小端）

| offset | size | 字段 | 说明 |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `"VMDIMG01"` |
| 8 | 4 | `format_version` | u32 = `1` |
| 12 | 4 | `footer_size` | u32 = `4096`（便于未来扩展） |
| 16 | 8 | `file_size` | u64，整个 `.img` 字节数（检测截断） |
| 24 | 8 | `rootfs_offset` | u64 = `0` |
| 32 | 8 | `rootfs_size` | u64 = `R` |
| 40 | 32 | `rootfs_sha256` | 原始字节，覆盖 `[0, R)` |
| 72 | 8 | `manifest_offset` | u64 |
| 80 | 8 | `manifest_size` | u64 = `M` |
| 88 | 32 | `manifest_sha256` | 覆盖 manifest 全部 `M` 字节 |
| 120 | 4 | `flags` | u32：bit0 = `HAS_KERNEL`，bit1 = `HAS_INITRD`，bit2..31 保留（必须为 0） |
| 124 | 8 | `kernel_offset` | u64；无 = `0`（**R-16**：arm64 `Image` payload，PC 端 `-kernel` 直接用） |
| 132 | 8 | `kernel_size` | u64；无 = `0` |
| 140 | 32 | `kernel_sha256` | 无 = 全零 |
| 172 | 8 | `initrd_offset` | u64；无 = `0`（PC 端 `-initrd`） |
| 180 | 8 | `initrd_size` | u64；无 = `0` |
| 188 | 32 | `initrd_sha256` | 无 = 全零 |
| 220 | 3868 | `reserved` | 全零 |
| 4088 | 8 | `magic_tail` | 再次 `"VMDIMG01"`（快速定位/截断检测） |

> **v1 无 seed 字段**（决策 2026-10-08）：原 `seed_offset/seed_size/seed_sha256`
> （72–119）与 `HAS_SEED`（bit0）已移除，后续字段**前移 48 字节**。
> format_version 1 尚未发布任何镜像，重编号无兼容负担。

**footer 解析规则**

1. 先判断 `file_size ≥ 4096`；读最后 4096 字节 → 校验 `magic`/`magic_tail`。
   - 魔数不匹配 → 走拒绝分支（N4：裸 squashfs 提示封装；否则"非本格式"）。
   - 魔数匹配才检查 `file_size < 8192 → 拒绝`（避免误杀极小合法文件，R1: B-R1-11）。
2. 校验 `footer_size == 4096`、`format_version ≤ 1`、
   `flags 保留位为 0`（**合法位 = bit0|bit1，掩码 `0x3`**；R2: A-R2-1/B-R2-1/C-R2-1）。
3. 校验 `file_size == <实际文件大小>`（否则 = 截断/拼接，拒绝）。
4. **flags ↔ 段一致性**（R2: B-R2-6/C-R2-2）：
   `bit0` 置位 ⇔ `kernel_size > 0`；`bit1` 置位 ⇔ `initrd_size > 0`；
   未置位时对应 `*_offset`/`*_size` 必须为 0 —— 任一不符按损坏拒绝。
5. **边界/溢出校验**（R1: B-R1-10，用溢出安全比较 `a > MAX - b`）：
   - 段序（按 §2 布局）：`rootfs` → `kernel` → `initrd` → `manifest` → `footer`；
   - 每个**存在的**段 `[offset, offset+size)` 必须落在 `[0, file_size - 4096)` 内；
   - 不存在的段 `*_offset == *_size == 0`（**不参与不等式链**，R2: B-R2-2 教训）；
   - `manifest_offset + manifest_size ≤ file_size - 4096`；
   - 任一不满足按损坏拒绝。
6. 定位 `manifest_offset/manifest_size` → 读出 → sha256 比对。
7. 逐 payload 校验 sha256（可延迟到"校验"动作，见 DESIGN §6.4）。

---

## 4. Manifest（JSON, UTF-8, 无 BOM）

```jsonc
{
  "format": "vmdroid-system-image",
  "format_version": 1,

  "image": {
    "id": "debian-minimal-arm64",     // 稳定标识，目录内唯一
    "display_name": "Debian 13 (trixie) · 最小化",
    "identity": "debian:trixie",       // ★ 决定切换时是否需要重置数据盘（DESIGN §5.2）
    "variant": "minimal",              // minimal | desktop | containers | full
    "version": "2026.10.0-r1",         // 镜像版本 = 日期+修订号（与 DESIGN §6.1 catalog 同格式）
    "system_version": 34,              // 对应 guest 内 /etc/podroid/system-version（与 catalog 一致）
    "arch": "arm64",                   // 必须 "arm64"，否则拒绝
    "distro": { "name": "debian", "release": "trixie", "init": "systemd" },
    "created_at": "2026-10-07T00:00:00Z",
    "source": "https://github.com/ltbkq/Podroid-Debian",
    "license": "GPL-2.0-or-later"
  },

  "contract": {
    "version": 1,                      // 启动契约版本
    "markers": ["Loading kernel modules...", "Network found",
                "Starting SSH...", "Almost ready...", "Ready!"],
    "ttys": { "hvc0": "login", "hvc1": "resize",
              "hvc2": "host-bridge", "ttyAMA0": "console" },
    "kernel": { "builtin_only": true, "min": "6.0", "max": "7.99" }
  },

  "capabilities": {                    // ★ 应用 UI 的唯一真值来源（DESIGN §4.6）
    "ssh": true,          // 必须为 true
    "x11": true,          // Xvnc :5900 + pulse :4713 —— 首发 debian.img 即装
    "desktop": false,     // 桌面环境 (xfce4)
    "containers": false,
    "desktop_profile": false,
    "downloads_share": true,
    "usb_passthrough_host": true   // 由应用决定，镜像声明仅供参考（R1: A-R1-15）
  },

  "accounts": {                        // ★ 账户规范（DESIGN §4.8）
    "ssh": [
      { "user": "root",  "password": "123", "sudo": false },
      { "user": "ltbkq", "password": "123", "sudo": true }
    ],
    "default_user": "ltbkq",
    "ssh_port": 22                     // guest 内 SSH 端口 = 系统默认 22
  },

  "app": { "min_version_code": 1 },

  "boot": {                        // ★ R-16：PC 端启动所需（与 footer 对应）
    "machine": "virt",             // qemu-system-aarch64 -M
    "cpu": "max",                  // TCG 可用；arm64 主机可换 host
    "append": "console=ttyAMA0 mitigations=off",   // 基础 cmdline（应用可追加 podroid.*）
    "kernel_sha256": "…",          // = footer.kernel_sha256（JSON 侧便于工具读取）
    "initrd_sha256": "…",
    "drives": { "vda": "storage.img(rw,ext4)", "vdb": "<self>(ro,squashfs)" }
  },

  "checksums": {                   // 与 footer 字段一致（JSON 侧便于工具读取）
    "rootfs_sha256": "…"
  }
}
```

**字段规则**

- 未知字段**必须忽略**（前向兼容），不得拒绝解析。
- 缺省解释（`.img` 均带 manifest，此处理论兜底）：
  - 无 `capabilities` → `{ssh:true, x11:true, containers:true}`（对齐上游全量镜像）
  - 无 `accounts` → 仅 `root`、`ssh_port = 22`
  - 无 `contract` → 视为 `contract.version = 1`
- `capabilities.ssh` 若为 `false` → 镜像不合格，`mkimg` 与应用均拒绝（SSH 是硬性要求）。
- `accounts.ssh_port` **缺省 22；≠ 22 一律拒绝**（§4.8 端口规范）。
- **重置判定（DESIGN §5.2，三步）**：
  1. `rootfs_sha256` 相同 → 同一系统，**永不重置**（内容优先）；
  2. `identity` 不同 → `RESET_REQUIRED`；
  3. `contract.version` 或 `distro.init` 不同 → 即使 identity 相同也 `RESET_REQUIRED`（硬判据）。
  `version` / `system_version` **不参与**判定，仅用于展示与升级提示。
- `image.id` 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`（防 `-drive` 选项注入，DESIGN §6.1）。

---

## 5. 读取算法（应用侧伪代码）

```
open(file) -> size
if size < 4096: reject("too small")
seek(size - 4096); read 4096 bytes as footer
if footer.magic != "VMDIMG01" or footer.magic_tail != "VMDIMG01":
    -> 读 offset0：若为 "hsqs" → reject("裸 squashfs，需用 mkimg.sh 封装为 .img")
    -> 否则 reject("非 VMDroid 系统镜像")        # N4：不接受任何非 .img 输入
if footer.file_size != size: reject("truncated")
if footer.format_version > 1: reject("format too new, update app")
if footer.footer_size != 4096: reject("bad footer")
# ★ 合法 flags = bit0(KERNEL)|bit1(INITRD)，掩码 0x3（v1 无 seed；R2 Blocker 修复）
if footer.flags & ~0x3: reject("unknown flags")
# ★ flags ↔ 段一致性
if (footer.flags & 0x1) != (footer.kernel_size > 0): reject("flags/kernel mismatch")
if (footer.flags & 0x2) != (footer.initrd_size > 0): reject("flags/initrd mismatch")
# ★ 边界/溢出校验：每段 [offset, offset+size) ⊆ [0, file_size-4096)
for seg in [rootfs, kernel(if bit0), initrd(if bit1)]:
    if seg.offset > MAX-seg.size or seg.offset+seg.size > file_size-4096: reject("segment out of range")
# 段序必须递增（不存在的段 offset=0，不参与链式比较）
require rootfs.offset == 0
require seq_of_existing([kernel, initrd, manifest]) offsets are strictly increasing
if manifest_offset + manifest_size > file_size - 4096: reject("manifest out of range")
read manifest at [manifest_offset, +manifest_size); sha256 == manifest_sha256?
parse JSON -> require format == "vmdroid-system-image" else reject("非 VMDroid 镜像")
          validate image.id  matches ^[a-z0-9][a-z0-9._-]{0,63}$   # 防 -drive 选项注入
          validate arch == "arm64", capabilities.ssh == true, accounts.ssh_port == 22,
                    app.min_version_code <= currentVersionCode
activePath = file            # direct 模式（零拷贝直挂为 vdb）
```

> 上述加法运算须用**溢出安全**比较（`a > MAX - b` 而非 `a + b > MAX`）。
> `file_size < 8192` 的拒绝只在 footer magic 匹配后才执行，避免误杀（R1: B-R1-11）。

**校验时机**

| 时机 | 动作 |
|---|---|
| 下载/导入 | 全文件 sha256（= catalog `sha256`）+ footer/manifest 校验 → 写 `.meta.json` |
| 每次启动前 | 比对 `.meta.json` 的 `size` + `mtime`（**不**重算 300MB sha256） |
| 手动"校验" | 全量 payload sha256 重算 |
| 激活切换 | manifest `identity` 比对 → 决定是否 `RESET_REQUIRED` |

---

## 6. 生成算法（`mkimg.sh`，位于 Podroid-Debian 仓库）

```sh
tools/mkimg.sh \
    --rootfs out/debian-rootfs.squashfs \
    --manifest manifest.json \
    --kernel  out/vmlinuz-virt \      # ★ R-16：写入 kernel payload
    --initrd  out/initrd.img \        # ★ R-16：写入 initrd payload
    -o out/debian.img
# v1 无 --seed（决策 2026-10-08 移除）；恢复出厂见 DESIGN §4.3
```

步骤：

1. 读 `rootfs` 大小 `R`（取 `superblock.bytes_used`，**丢弃**补齐至 4096 边界的尾部字节，
   本例 `bytes_used`→文件尾 383 B；R1: B-R1-9），算 `rootfs_sha256`。
2. 计算各段 1 MiB 对齐偏移；依次读取 kernel?/initrd? 并各算 sha256。
3. 写 manifest JSON（UTF-8）→ 算 `manifest_sha256`。
4. 顺序写出：`rootfs` → 填充 → `kernel?` → 填充 → `initrd?`
   → 填充 → `manifest` → 填充（4 KiB 对齐）→ `footer`。
5. `fsync`；输出全文件 sha256 + 体积，供 catalog 使用。
6. **自检**：① 同一解析器回读；② 若含 kernel/initrd → 自动跑 `pc-boot-smoke.sh`
   （起 QEMU **90s**，断言出现 `Ready!` 且 `ssh -p 9922` 可登录，见 DESIGN §13.2 R-16 用例）。

**注意**：现有 `Podroid-Debian/out/debian-rootfs.squashfs` **无需重建**，
`mkimg` 只是在尾部追加（秒级完成）。

---

## 7. 兼容性矩阵

| 输入 | 识别 | 处理 |
|---|---|---|
| `VMDIMG01` footer | 完整格式 | 全功能（identity/capabilities/accounts） |
| 裸 squashfs（`hsqs` @0，无 footer） | 魔数 | **拒绝**（N4）：提示用 `mkimg.sh` 封装 |
| Podroid-Debian `debian-rootfs.squashfs` | 同上 | **拒绝**（同上；先封装为 `.img`，秒级追加） |
| 上游 `alpine-rootfs.squashfs` | 同上 | **拒绝**（回归基线改用封装后的 `.img`） |
| 其他（zip/img from 其他项目…） | 魔数不符 | 拒绝并提示 |
| `format_version > 1` | footer | 拒绝，提示升级应用 |

---

## 8. 测试向量

| 向量 | 期望 |
|---|---|
| 合法最小镜像（rootfs + manifest，无 kernel/initrd） | 解析成功，payload 校验通过，`flags == 0` |
| 含 kernel+initrd 的镜像 | `flags == 0x3`；`kernel/initrd` 偏移 1 MiB 对齐、长度与 sha256 与源文件逐字节一致 |
| 尾部 4 KiB 覆写 | `magic` 校验失败 → 拒绝 |
| 文件截断 1 字节 | `footer.file_size != size` → 拒绝 |
| manifest 单字节翻转 | `manifest_sha256` 不匹配 → 拒绝 |
| `arch != arm64` | 拒绝激活 |
| `capabilities.ssh == false` | 拒绝（违反 §4.8） |
| 裸 squashfs | **拒绝**并提示封装（N4） |
| `format_version = 2` | 拒绝并提示升级应用 |
| `manifest_offset+manifest_size` 越界 / 偏移乱序 | 拒绝（B-R1-10 边界校验） |
| `flags.bit0` 置位但 `kernel_size == 0`（或 `bit1`/`initrd_size`） | 拒绝（flags 与段不一致） |
| footer 偏移重编号回归：`manifest@72`、`kernel@124`、`initrd@172` | 逐字段断言（防 seed 移除后再错位，R2: RB-1 教训） |
| kernel payload 单字节翻转 | `kernel_sha256` 不匹配 → 拒绝（R-16：坏内核不能上机） |
| **PC 冒烟**：`qemu-system-aarch64` 用 `.img` 内 kernel/initrd 启动 | **90s** 内出现 `Ready!` + `ssh -p 9922` 可登录（R-16 主验收） |
| `accounts.ssh_port = 2222` | 拒绝（违反 §4.8 端口规范） |
| 扩展名 `.img` 但内容是 zip | 拒绝（以 footer 魔数为准，不看扩展名） |
| 尾随数据（P0 spike） | QEMU + AVF 真机均可挂载并进入 `Ready!` |

---

## 9. 与 DESIGN.md 的对应关系

| 本文 | DESIGN.md |
|---|---|
| 布局与直挂 | §4.2 / §4.3 |
| 仅 `.img`（拒绝裸 squashfs） | §4.3 N4 约束 / §5.4 |
| manifest 解析与激活前校验 | §4.4 / §5.2 |
| capabilities | §4.6 |
| accounts（root + ltbkq，pw 123，SSH 22） | §4.8 |
| 校验时机与 `.meta.json` | §6.4 |
| **无 seed / 恢复出厂 = 清零重建** | §4.3（决策 2026-10-08） |
| 目录（catalog）字段 | §6.1（共用字段一一对应：`image_id↔image.id`、`identity`、`variant`、`version`、`system_version`、`arch`；`url/size/sha256/channel` 仅 catalog，`contract/capabilities/accounts/boot` 仅 manifest） |
