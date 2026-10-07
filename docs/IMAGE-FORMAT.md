# VMDroid 系统镜像格式规格 (`.img`)

**版本：format_version = 1** · 关联设计文档 [DESIGN.md](DESIGN.md)

VMDroid 的系统是一个**单文件** `*.img`。它既是一个**可直接挂载的 squashfs**
（作为 guest 的 `vdb`），又在尾部携带自描述元数据与可选的持久盘种子。

---

## 1. 设计目标

| # | 目标 | 手段 |
|---|---|---|
| 1 | 零拷贝启动 | 文件 0x0 就是 squashfs superblock，可直接 `-drive ...,readonly=on` / `addDisk(writable=false)` |
| 2 | 单文件自描述 | 尾部 footer 指向 manifest（JSON），含 identity/版本/能力/账户 |
| 3 | 可校验 | 全文件 sha256（下载时算）+ 每个 payload 的 sha256（footer 内） |
| 4 | 可携带 vda 种子 | 可选 ext4 payload，用于"恢复出厂" |
| 5 | 向后兼容 | 无 footer 的裸 squashfs 仍可导入（降级为"未知镜像"） |
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
     │ persist 种子 (ext4)  [可选, flags.bit0]      │  ← 仅"恢复出厂"时提取为 storage.img
     │ ... bytes = S ...                           │
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
| 段对齐 | `seed`、`manifest`、`footer` 均从 1 MiB 边界开始；`footer` 固定在 `[file_size-4096, file_size)` |
| `manifest_size` | `1 ≤ M ≤ 65536` |
| `seed_size` | `0` 或 `≥ 4096`，且为 4096 的倍数 |
| 填充 | 所有洞必须全零（读取方必须容忍非零垃圾：容忍即可，不校验） |
| 压缩 | 整文件**不额外压缩**（squashfs 自身已 zstd/xz 压缩；外层压缩会破坏直挂） |

> **为什么直挂可行**：squashfs superblock 位于 0x0，块表用绝对偏移，内核只读取到
> `bytes_used` 为止，**尾随数据天然被忽略**。P0 必须在真机 QEMU + AVF 各验证一次
> （DESIGN §13.3）。若任一后端不接受尾随数据 → 启用 `extract` 降级路径
> （DESIGN §4.3），格式本身不变。

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
| 72 | 8 | `seed_offset` | u64；无种子 = `0` |
| 80 | 8 | `seed_size` | u64；无种子 = `0` |
| 88 | 32 | `seed_sha256` | 无种子 = 全零 |
| 120 | 8 | `manifest_offset` | u64 |
| 128 | 8 | `manifest_size` | u64 = `M` |
| 136 | 32 | `manifest_sha256` | 覆盖 manifest 全部 `M` 字节 |
| 168 | 4 | `flags` | u32：bit0 = `HAS_SEED`，bit1..31 保留（必须为 0） |
| 172 | 3916 | `reserved` | 全零 |
| 4088 | 8 | `magic_tail` | 再次 `"VMDIMG01"`（快速定位/截断检测） |

**footer 解析规则**

1. `file_size = <实际文件大小>`；`file_size < 8192` → 拒绝。
2. 读最后 4096 字节 → 校验 `magic == "VMDIMG01"` 且 `magic_tail == "VMDIMG01"`。
3. 校验 `footer_size == 4096`、`format_version ≤ 1`、`flags 保留位为 0`。
4. 校验 `file_size == <实际文件大小>`（否则 = 截断/拼接，拒绝）。
5. 定位 `manifest_offset/manifest_size` → 读出 → sha256 比对。
6. 逐 payload 校验 sha256（可延迟到"校验"动作，见 DESIGN §6.4）。

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
    "version": "1.0.0",                // 镜像自身版本（语义化）
    "system_version": 1,               // 对应 guest 内 /etc/podroid/system-version
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
    "downloads_share": true
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

  "checksums": {                       // 与 footer 字段一致（JSON 侧便于工具读取）
    "rootfs_sha256": "…",
    "seed_sha256": null
  }
}
```

**字段规则**

- 未知字段**必须忽略**（前向兼容），不得拒绝解析。
- 缺省解释（兼容旧/裸镜像）：
  - 无 `capabilities` → `{ssh:true, x11:true, containers:true}`（对齐上游全量镜像）
  - 无 `accounts` → 仅 `root`（上游 Alpine/Debian 现状）
  - 无 `contract` → 视为 `contract.version = 1`
- `identity` 是**唯一**参与重置判定的字段；`version`/`system_version` 只用于展示与升级判断。
- `capabilities.ssh` 若为 `false` → 镜像不合格，`mkimg` 与应用均拒绝（SSH 是硬性要求）。

---

## 5. 读取算法（应用侧伪代码）

```
open(file) -> size
if size < 8192: reject("too small")
seek(size - 4096); read 4096 bytes as footer
if footer.magic != "VMDIMG01" or footer.magic_tail != "VMDIMG01": 
    -> 裸 squashfs 降级路径：检查 offset0 == "hsqs"，是则按"无 manifest 镜像"处理
if footer.file_size != size: reject("truncated")
if footer.format_version > 1: reject("format too new, update app")
if footer.flags & ~0x1: reject("unknown flags")
read manifest at [manifest_offset, +manifest_size); sha256 == manifest_sha256?
parse JSON -> validate arch == "arm64", capabilities.ssh == true,
              app.min_version_code <= currentVersionCode
activePath = file            # direct 模式（零拷贝直挂为 vdb）
```

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
    [--seed  seed.ext4] \
    -o out/debian-minimal-arm64.img
```

步骤：

1. 读 `rootfs` 大小 `R`，算 `rootfs_sha256`（流式）。
2. 计算各段 1 MiB 对齐偏移；若有 seed，读取并算 `seed_sha256`。
3. 写 manifest JSON（UTF-8）→ 算 `manifest_sha256`。
4. 顺序写出：`rootfs` → 填充 → `seed?` → 填充 → `manifest` → 填充 → `footer`。
5. `fsync`；输出全文件 sha256 + 体积，供 catalog 使用。
6. 自检：用同一解析器回读一遍（工具与应用共用解析规则）。

**注意**：现有 `Podroid-Debian/out/debian-rootfs.squashfs` **无需重建**，
`mkimg` 只是在尾部追加（秒级完成）。

---

## 7. 兼容性矩阵

| 输入 | 识别 | 处理 |
|---|---|---|
| `VMDIMG01` footer | 完整格式 | 全功能（identity/capabilities/accounts） |
| 裸 squashfs（`hsqs` @0，无 footer） | 魔数 | 零拷贝直挂；`identity = sha256:<前16hex>`；capabilities 按缺省解释 |
| Podroid-Debian `debian-rootfs.squashfs` | 同上 | 可直接导入（**现有产物即刻可用**） |
| 上游 `alpine-rootfs.squashfs` | 同上 | 回归基线可导入 |
| 其他（zip/img from 其他项目…） | 魔数不符 | 拒绝并提示 |
| `format_version > 1` | footer | 拒绝，提示升级应用 |

---

## 8. 测试向量

| 向量 | 期望 |
|---|---|
| 合法最小镜像（rootfs + manifest，无 seed） | 解析成功，payload 校验通过 |
| 含 seed 的镜像 | `flags.bit0` 置位，seed 偏移/长度正确 |
| 尾部 4 KiB 覆写 | `magic` 校验失败 → 拒绝 |
| 文件截断 1 字节 | `footer.file_size != size` → 拒绝 |
| manifest 单字节翻转 | `manifest_sha256` 不匹配 → 拒绝 |
| `arch != arm64` | 拒绝激活 |
| `capabilities.ssh == false` | 拒绝（违反 §4.8） |
| 裸 squashfs | 降级路径成功激活 |
| `format_version = 2` | 拒绝并提示升级应用 |
| 尾随数据（P0 spike） | QEMU + AVF 真机均可挂载并进入 `Ready!` |

---

## 9. 与 DESIGN.md 的对应关系

| 本文 | DESIGN.md |
|---|---|
| 布局与直挂 | §4.2 / §4.3 |
| manifest 解析与降级 | §4.4 / §5.4 |
| capabilities | §4.6 |
| accounts（root + ltbkq，pw 123，SSH 22） | §4.8 |
| 校验时机与 `.meta.json` | §6.4 |
| 目录（catalog）字段 | §6.1（与本 manifest 字段一一对应） |
