# VMDroid 系统镜像格式规格 v2（完整磁盘镜像 `.img`）

**版本：format_version = 2** · 状态：**冻结 v2.0（2026-10-12）** ·
**参照实现**：`build-tools/mkdisk.sh`（原 `Podroid-Debian/tools`，该远程仓库计划删除已迁移）（build/verify，M1 交叉验证：自检、负向向量、
单盘与 vda+vdb 双盘 UEFI 引导全绿）· 实现与规格冲突时按 DESIGN-disk-image-v2 §0.2 D11 处理。
**上游设计**：[DESIGN-disk-image-v2.md](DESIGN-disk-image-v2.md) · **取代**：[IMAGE-FORMAT.md](IMAGE-FORMAT.md)
（v1，保留仅供诊断阶梯引用）。真机项（P0-1/M-c）结论回填**只增不改**本规格。

v2 的 `.img` 是一块**标准 GPT 分区磁盘**：保护性 MBR + GPT 引导记录、FAT32 ESP（UEFI 引导链）、、
**可写 ext4 根分区**、独立 meta 元数据分区。它可被 `fdisk`/`gparted`/`debugfs` 等标准工具直接
操作，也可在 QEMU(UAAVMF UEFI) 上**不带 `-kernel/-initrd`** 启动到 `Ready!`。

---

## 1. 设计目标

| # | 目标 | 手段 |
|---|---|---|
| 1 | 标准磁盘镜像 | GPT + 保护性 MBR + ESP(FAT32) + ext4，任何标准工具可检查/修改/克隆 |
| 2 | 自包含可启动 | UEFI → `BOOTAA64.EFI`(GRUB) → ESP 内 kernel/initrd；**格式内含完整引导链** |
| 3 | 可写文件系统 | root 分区 rw；系统改动落在 `.img` 自身（用户接受"重装系统才能回原厂"，设计文档 D5） |
| 4 | 单文件自描述 | meta 分区携带 manifest + 摘要，**不依赖文件末尾**（文件末尾是备份 GPT） |
| 5 | 可校验 | catalog 双摘要（`.img.zst` 与 `.img`）+ meta 内 `root_sha256`（离线/全量校验） |
| 6 | 可扩容 | root 恒为最后分区 + 尾部 1 MiB 未分配 + `file_size` 恒等于实际大小（设计文档 §6.5） |
| 7 | 硬切换 | 只接受 v2；v1（`VMDIMG01`/裸 squashfs）→ `LEGACY_FORMAT`，提示重新获取 |

---

## 2. 磁盘布局（字节级）

### 2.1 总览

```
file_size = 266 MiB + S      (S = 根分区大小，MiB 整数倍，默认 4096 MiB)
扇区 = 512 B                 所有分区 1 MiB 对齐

LBA 0            保护性 MBR（分区类型 0xEE，签名 0x55AA）
LBA 1            GPT header（主）
LBA 2 .. 33      GPT 分区表项（128 × 128 B = 16 KiB）
LBA 34 .. 2047   全零间隙（1 MiB 对齐；x86-64 移植时此处置入 BIOS boot 代码）
LBA 2048         p1 ESP   起点（1 MiB）
  +524288 扇区   p1 终点  (256 MiB)
LBA 526336       p2 meta  起点（257 MiB）
  +16384 扇区    p2 终点  (8 MiB)
LBA 542720       p3 root  起点（265 MiB）
  +S/512 扇区    p3 终点  (S)
...              全零未分配尾隙（≈1 MiB − 33 扇区，供扩容首步）
LBA last-32..last-1  备份 GPT 分区表项
LBA last             备份 GPT header   （last = file_size/512 − 1）
```

### 2.2 分区表（GPT）

| 分区 | 类型 GUID | 唯一 GUID（partition GUID） | 名称 | 起止 LBA |
|---|---|---|---|---|
| p1 ESP | `C12A7328-F81F-11D2-BA4B-00A0C93EC93B`（EFI System） | 构建期随机 | `VMDROID-EFI` | `2048` .. `526335` |
| p2 meta | `0FC63DAF-8483-4772-8E79-3D69D8477DE4`（Linux filesystem） | **固定常量 `564D444D-4554-4132-8000-000000000001`**（ASCII `VMDMETA2` 前 8 字节） | `vmd-meta` | `526336` .. `542719` |
| p3 root | `0FC63DAF-8483-4772-8E79-3D69D8477DE4`（Linux filesystem） | 构建期随机（= manifest `disk.root.partuuid`） | `vmd-root` | `542720` .. `542719+S/512` |

**约束**

1. **root 恒为最后一个分区**（设计文档评审 R-01：扩容不得搬移任何分区数据）。
2. 读取方**不以序号定位**：meta 用固定唯一 GUID，root 用 `唯一 GUID == manifest.disk.root.partuuid`。
   序号（`disk.*.part`）仅供工具阅读。
3. 分区表项数 128，表项大小 128 B；首个可用 LBA=34，末个可用 LBA=`last-33`。
4. 所有未使用字节（间隙、尾隙、分区尾部自由空间）**必须全零**——
   读者 MUST NOT 因非零垃圾拒绝（容错即可，v1 IMP-C01 同义）。

**保护性 MBR（LBA 0，512 B）**

| offset | 内容 |
|---|---|
| 0x000..0x1BD | 全零（不写引导代码；arm64 无 BIOS，见设计文档 T1） |
| 0x1BE | 分区项：`status=0x00`，`type=0xEE`，CHS 全 `0xFE/0xFF/0xFF`，`start LBA=1`，`size=min(file_size/512−1, 0xFFFFFFFF)` |
| 0x1CE..0x1FD | 全零 |
| 0x1FE | `55 AA` |

**GPT header（LBA 1，92 字节，小端）**

| offset | size | 字段 | 值 |
|---|---|---|---|
| 0 | 8 | 签名 | `"EFI PART"` |
| 8 | 4 | 修订 | `0x00010000` |
| 12 | 4 | header size | `92` |
| 16 | 4 | header CRC32 | 本字段置 0 后对前 92 字节计算 |
| 20 | 4 | 保留 | 0 |
| 24 | 8 | current LBA | `1` |
| 32 | 8 | backup LBA | `last` |
| 40 | 8 | first usable LBA | `34` |
| 48 | 8 | last usable LBA | `last − 33` |
| 56 | 16 | disk GUID | 构建期随机 |
| 72 | 8 | entries LBA | `2` |
| 80 | 4 | entries 数 | `128` |
| 84 | 4 | 每项大小 | `128` |
| 88 | 4 | entries CRC32 | 对 16 KiB 表项计算 |

备份 header 仅 `current/backup LBA` 与主表互换（current=`last`，backup=`1`），entries LBA=`last−32`。
**读取方校验**：签名、CRC、备份位置、`first/last usable`、表项 CRC（任一失败 → `CORRUPT`）。

**分区表项（128 B，小端）**

| offset | size | 字段 |
|---|---|---|
| 0 | 16 | 类型 GUID |
| 16 | 16 | 唯一 GUID |
| 32 | 8 | first LBA |
| 40 | 8 | last LBA（含） |
| 48 | 8 | 属性（恒 0） |
| 56 | 72 | 名称 UTF-16LE（NUL 填充） |

### 2.3 ESP（p1，FAT32，256 MiB）

```
mkfs.vfat -C（512 B 扇区，FAT32，簇 ≤ 8 KiB，卷标 VMDROID-EFI）
文件清单（缺一不可，生成侧断言）：
  /EFI/BOOT/BOOTAA64.EFI    GRUB arm64 独立镜像：`grub-mkimage -O arm64-efi -p '(hd0,gpt1)/grub'
                             **-c 嵌入式 cfg**（§2.4 —— 自包含，与盘序无关；仅靠外部
                             cfg + 烘焙 prefix 在 vda+vdb 双盘拓扑下会掉 rescue shell，
                             M1 实测）+ 模块清单（**冻结**）：`fat part_gpt normal boot
                             linux gzio search search_fs_file search_fs_uuid configfile
                             echo test`
                             —— `gzio` 必打（`/initrd.img` 是 gzip cpio，GRUB 解压必需；
                             内核若用 gzip 形态同样依赖它。缺失时 GRUB 报
                             "plain image kernel not supported"，与真·缺 EFI_STUB 同错，
                             P0-2 F1b 教训）；模块版本须与生成机 `grub-mkimage` 版本一致
  /grub/grub.cfg             引导配置副本（人工/急救；权威在嵌入式 cfg，见 §2.4）
  /grub/user.cfg             **运行时参数文件，定长 4096 字节**（构建期预置；R8 定稿见 §2.4）
  /vmlinuz                   arm64 内核，**存未压缩 `Image`**（M-a 采纳：省 GRUB 端 inflate
                             ~2s，P0-2 第五轮实测；ESP 256MiB 容得下 ~60MB Image）。
                             gzip 形态合法但慢 ~2s，不采纳。现网内核已验证可经 UEFI+GRUB
                             加载（第四轮，F1 不成立，无需改内核配置）；须与 kernel_sha256 一致
  /initrd.img                cpio/gzip initramfs（含 init-podroid v2）
  /vmdroid/manifest.json     manifest 冗余副本（**权威在 meta 分区**，不参与校验）
```

### 2.4 `/grub/grub.cfg`（生成期固定模板）

```
search --fs-uuid <ESP FAT 卷 ID> --set=root
set timeout=0
echo "VMDroid: booting (kernel from ESP)"
linux  /vmlinuz console=ttyAMA0 mitigations=off vmd.root_partuuid=<root 唯一 GUID>
initrd /initrd.img
boot
```

- ESP 的 FAT 卷 ID 由构建期 `mkfs.vfat -i <8位hex>` 固定，并记录在
  `manifest.disk.esp.fat_uuid`；GRUB 里用其 **`XXXX-XXXX` 横线形式**（裸 hex 会报
  `no such device` 并退化到 prefix 兜底 —— 多盘拓扑下 prefix `(hd0,gpt1)` 会指错盘，
  M1 打包器实测教训）。**不用 `search --part-uuid`**（grub 2.12 的 search 报
  "unspecified search type"）。
- **权威 cfg 在 GRUB 镜像内**（`grub-mkimage -c` 嵌入，见 §2.3）：与盘序无关。
  ESP 内 `/grub/grub.cfg` 是**同内容副本**（供人工/工具查看与急救），
  不被依赖（烘焙 prefix `(hd0,gpt1)` 在双盘拓扑下不可信）。
- **TBD-1 方案 b 落点 → R8 定稿（2026-10-12）：`/grub/user.cfg` 运行时参数文件**
  1. **形态**：FAT 内**定长 4096 字节**文件，构建期预置；内容为 **GRUB 脚本**：
     首行魔数 `#VMDU2\n`，其后一行 `set podroid_params="<空格分隔的内核参数>"\n`，
     其余用 `\n` 填充（**禁止 NUL 字节**——GRUB source 读到 NUL 行为不可控）。
     **实证（R8）**：引号**必需**（无引号时 GRUB set 值截断到第一个空格，只剩第一个参数）；
     引号会被保留在变量值中 → **内核 cmdline 中该段带包裹引号**（首参前/末参后）——
     guest 消费者均为子串/模式捕获（`podroid.x11.dpi=` 数字捕获等，P0-7 调研），
     实测捕获不受包裹引号影响；**不要**试图在 GRUB 侧去引号（无干净手段）。
     （补充实测：`linux` 拆参时**单参数**形态下包裹引号可能被剥离——两种形态 guest 均兼容，
     写入方无需规避。）
  2. **消费**：嵌入式 cfg 在 `linux` 行之前插入 `source /grub/user.cfg`，`linux` 行尾追加
     `$podroid_params`（未定义/空时 GRUB 展开为空串）；user.cfg 只做 `set`。
     **R8 实证（2026-10-12，GRUB 2.12 arm64 真实引导 + 救援 shell 交互实验）**：
     ① `source` **共享环境变量**（set 后 `$podroid_params` 可读，已实测注入内核 cmdline）；
     ② `configfile` **不回传变量**（执行后变量为空）→ 排除；
     ③ `test -f … && …` 守卫在脚本上下文静默 false（无报错但整段跳过）→ 排除守卫；
     ④ `set var="a b"` 的引号被 GRUB 剥离（值 = a b）→ user.cfg 保留引号写法（无害更清晰）。
     **无守卫的前提**：user.cfg 与嵌入式 cfg **同构建预置**、应用只覆盖内容不删文件，
     不存在"文件缺失"态；若未来允许删除，须先解决守卫可用性。
     **外部加载仅此一处且不承载启动命令**（M1 教训 L4 的"盘序指错"影响不到它）。
  3. **写入方**：应用在 **VM 停机期**用 FAT32 **定长覆盖写**（只覆写数据区字节，**永不**改
     目录项/文件大小/簇链——预置保证簇已分配 → 写入器复杂度与风险双降）；写后**回读比对**
     （4 KiB 级，廉价），不一致即回滚原内容（写前驻留内存）并报错。
  4. **安全边界（防 GRUB 命令注入）**：`podroid.x11.dpi`/`podroid.bandwidth` 为纯数字；
     `podroid.dns` 为逗号分隔 IP/主机名字符集；`userKernelExtras` 自由文本**白名单过滤**
     （允许 `[A-Za-z0-9 ._=/,:+-]`，其余字符剥离）后整体包双引号写入；任何不满足者
     **拒绝写入**（fail-closed，启动照常用默认值，记 warning）。
  5. **生命周期**：每次 v2 启动前重写（覆盖式，幂等）；镜像重装/切换后首次启动同样覆盖，
     故残留参数无跨镜像泄漏风险；AVF 走 `addParams`（§3.2 方案 B），**不经 user.cfg**。
- **不使用 `menuentry` 菜单**：单内核 + `timeout=0`，命令直接置顶层执行（实测最稳；
  菜单写错会让内核永不加载且落入 rescue shell）。

- 基础串 **`console=ttyAMA0 mitigations=off`** 是权威值（对应 v1 `boot.append` 语义：
  宿主不得删改，v2 中宿主干脆不注入）。
- `vmd.root_partuuid=` 供 initramfs 定位/校验 root 分区（initramfs 无 udev，
  无 `/dev/disk/by-partuuid`，见设计文档 §4.1）。
- 运行时可变参数**不在** `grub.cfg`（设计文档 §3.4 —— P0-7 结论：QEMU 走**方案 b**，
  追加 `/grub/user.cfg`（4 KiB 定长、全零可覆盖）+ grub `source` 行；AVF 走方案 B 的
  `addParams`，不经本文件）。
- **错误处理待办（P0-2 F4）**：`linux`/`initrd` 失败时 GRUB 落入 rescue shell 占住串口
  （实测：报错后停在 `grub>` 直到超时）。正式模板须加失败分支（打印标记 → 短延时 →
  `reboot`/halt），§2.4 冻结时定稿。

### 2.5 root（p3，ext4，rw）

| 项 | 值/约束 |
|---|---|
| 文件系统 | ext4，4 KiB 块（`-b 4096`），label `vmdroid-root`，保留 journal |
| 大小 S | 档位 `[2, 4, 8, 16, 32] GiB`；构建期断言 `用量 ≤ S × 0.8` |
| PARTUUID | 构建期随机，写入 `grub.cfg`（`vmd.root_partuuid=`）与 manifest |
| 运行期 | guest 可写；宿主**不改其内容与分区表**（扩容只在 guest 内做，设计文档 §6.5） |

---

## 3. meta 分区（p2，raw 8 MiB）

布局：固定头 140 B → manifest 区 → 全零填充 → `magic_tail`（位于 `meta_size − 8`）。
v2.0 `meta_size = 8192`；**读取方按 `meta_size` 定位 tail，不得硬编码 8184**（未来可扩）。

| offset | size | 字段 | 说明 |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `"VMDIMG02"` |
| 8 | 4 | `format_version` | u32 = `2` |
| 12 | 4 | `meta_size` | u32 = `8192`（v2.0）；约束 `148 ≤ … ≤ 分区大小` |
| 16 | 8 | `file_size` | u64 = 整个 `.img` 字节数（截断/追加检测） |
| 24 | 8 | `manifest_offset` | u64，**相对 meta 分区起点** |
| 32 | 8 | `manifest_size` | u64，约束 `1 ≤ manifest_size ≤ meta_size − 148` |
| 40 | 32 | `manifest_sha256` | 覆盖 manifest 全部 `manifest_size` 字节 |
| 72 | 8 | `root_offset` | u64，**绝对**字节偏移 = `542720 × 512`（= 265 MiB） |
| 80 | 8 | `root_size` | u64 = `S` |
| 88 | 32 | `root_sha256` | root 分区 `[root_offset, +root_size)` 全字节（含零尾） |
| 120 | 8 | `esp_offset` | u64 = `2048 × 512`（= 1 MiB） |
| 128 | 8 | `esp_size` | u64 = `256 MiB` |
| 136 | 4 | `flags` | u32：bit0 `HAS_KERNEL_ESP`、bit1 `HAS_INITRD_ESP`，**v2.0 两位必须均为 1**，bit2..31 必须为 0（合法掩码 `0x3`） |
| 140 | `meta_size−148` | `manifest 区` + 零填充 | `manifest_offset/size` 指向此处 |
| `meta_size−8` | 8 | `magic_tail` | 再次 `"VMDIMG02"` |

**不设 `esp_sha256`**（设计文档 R-03：ESP 含 manifest 冗余副本 → 自引用循环；
ESP 完整性由 manifest 的 `kernel_sha256`/`initrd_sha256` 覆盖）。

**字段顺序 ≠ 分区顺序**：本表 `root_*` 在 `esp_*` 前只是编号结果，语义见各字段自身。

---

## 4. Manifest（JSON，UTF-8，无 BOM；权威副本在 meta 分区）

### 4.1 示例（含 v2 全部字段）

```jsonc
{
  "format": "vmdroid-system-image",
  "format_version": 2,

  "image": {
    "id": "debian-minimal-arm64",
    "display_name": "Debian 13 (trixie) · 最小化",
    "identity": "debian:trixie",
    "variant": "minimal",
    "version": "2026.10.0-r1",
    "system_version": 35,
    "arch": "arm64",
    "distro": { "name": "debian", "release": "trixie", "init": "systemd" },
    "created_at": "2026-10-12T00:00:00Z",
    "source": "https://github.com/ltbkq/Podroid-Debian",
    "license": "GPL-2.0-or-later",
    "content_id": "sha256:…"      // ★ v2 新增：内容树摘要，见 §4.3
  },

  "disk": {                        // ★ v2 新增：布局真值（与 §2 一一对应）
    "table": "gpt",
    "sector_size": 512,
    "file_size": 4573888512,       // = 266 MiB + 4096 MiB
    "esp":  { "part": 1, "fstype": "fat32", "first_lba": 2048, "last_lba": 526335,
              "size": 268435456, "fat_uuid": "e0ec264b" },  // FAT 卷 ID（mkfs.vfat -i 固定；
                                                            // grub.cfg 用其 XXXX-XXXX 形式）
    "meta": { "part": 2, "first_lba": 526336, "last_lba": 542719, "size": 8388608 },
    "root": { "part": 3, "fstype": "ext4", "partuuid": "…",
              "first_lba": 542720, "size": 4294967296 },
    "kernel_path": "/vmlinuz",     // ESP 内相对路径（AVF 回退用）
    "initrd_path": "/initrd.img"
  },

  "contract": {
    "version": 2,                  // ★ 契约 v2（guest 侧 init-podroid v2 / 无 overlay）
    "markers": ["Loading kernel modules...", "Network found",
                "Starting SSH...", "Almost ready...", "Ready!"],   // 子集，语义同 v1
    "ttys": { "hvc0": "login", "hvc1": "resize",
              "hvc2": "host-bridge", "ttyAMA0": "console" },
    "kernel": { "builtin_only": true, "min": "6.0", "max": "7.99",
                "image_sha256": "…" }   // = checksums.kernel_sha256
  },

  "capabilities": { "ssh": true, "x11": true, "desktop": false, "containers": false,
                    "desktop_profile": false, "downloads_share": true,
                    "usb_passthrough_host": true },

  "accounts": {
    "ssh": [ { "user": "root",  "password": "123", "sudo": false },
             { "user": "ltbkq", "password": "123", "sudo": true } ],
    "default_user": "ltbkq",
    "ssh_port": 22
  },

  "app": { "min_version_code": 1 },

  "boot": {                        // ★ v2 变更：无 -kernel/-initrd 直启
    "mode": "uefi",                // 固定 "uefi"（v2.0 不允许其他值）
    "machine": "virt",
    "cpu": "max",
    "append": "console=ttyAMA0 mitigations=off",   // = grub.cfg 基础串（权威）
    "kernel_sha256": "…", "initrd_sha256": "…",
    "drives": { "vda": "storage.img(rw,ext4)", "vdb": "self(rw,gpt)" }
  },

  "checksums": {
    "root_sha256": "…",            // = meta.root_sha256（JSON 侧便于工具读取）
    "kernel_sha256": "…",          // R6 修订（2026-10-12）：= ESP /vmlinuz **实际字节**
                                   // （gzip 入参时为展开后 Image）摘要，供 AVF 回退提取后
                                   // 校验；不是构建入参文件的哈希。mkdisk 已按此生成。
    "initrd_sha256": "…"           // ESP /initrd.img 字节摘要（原样拷贝，两种语义一致）
  }
}
```

### 4.2 字段规则（沿用 v1，标注 v2 差异）

- 未知字段**必须忽略**（前向兼容），不得拒绝解析。
- `image.id` 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`（防 `-drive`/`-name` 注入）。
- `arch == "arm64"`、`capabilities.ssh == true`、`accounts.ssh_port == 22`（类型非整数视同 ≠22）、
  `boot.mode == "uefi"`、`contract.version == 2` —— 任一不满足 → 拒绝激活。
- 缺省解释（meta 内 manifest 理论不应缺，读取侧兜底）：无 `capabilities` →
  `{ssh:true,x11:true,containers:true}`；有块缺 `ssh` → `false`（fail-closed）；
  无 `accounts` → `{root, 22}`；**无 `disk` → 拒绝**（v2 没有布局信息不可用，`CORRUPT`）；
  **`app.min_version_code` 缺失（无 `app` 块或有块缺键）→ 拒绝**（`CORRUPT`）
  —— 修订 2026-10-12（B 线歧义 #2 拍板）：废止 v1"无 `app` 块 → 0（不拒绝）"兜底，
  理由：v2 硬切换无历史镜像包袱，生成侧（IMP-D05 / 参照实现）已强制该键，
  读取侧从严保持两侧一致。
- **重置判定（三步，语义沿用 v1 §5.2，判据换实现）**：
  1. `image.content_id` 相同 → 同一系统，**永不重置**；
  2. `identity` 不同 → `RESET_REQUIRED`；
  3. `contract.version` 或 `distro.init` 不同 → `RESET_REQUIRED`。
  `root_sha256` **不参与**重置判定（含零尾，重打包即变，太脆）。
- **生成侧必需字段（IMP-D05 v2 版）**：`image.id/display_name/identity/variant/version/
  system_version/arch/content_id`、`disk.root.partuuid`、`contract.version`、
  `capabilities.ssh`、`accounts.ssh_port`、`app.min_version_code`、`boot.mode`。

### 4.3 `content_id` 算法（构建期，读取方只比字符串）

```
遍历 rootfs 目录树（深度优先），按相对路径字典序，对每项输出一行：
  普通文件: "<path>\0<mode>\0<uid>\0<gid>\0<sha256(file)>\n"
  目录:     "<path>\0<mode>\0<uid>\0<gid>\0DIR\n"
  符号链接: "<path>\0<mode>\0<uid>\0<gid>\0LINK:<target>\n"
  · path = 相对路径无前导 "/"，按码点全局排序；mode = st_mode 完整八进制（如 "100644"）
content_id = "sha256:" + hex(sha256(所有行拼接))

排除（白名单，运行期必然变化的项；**整棵子树连同其根目录项本身一并排除**，
      与 mkdisk-content-id.py 参考实现一致）:
  /etc/machine-id          /var/**            /run/**         /tmp/**
  /etc/ssh/ssh_host_*      /root/.bash_history  /home/**       /lost+found/**
  /boot/vmlinuz-*  /boot/initrd.img-*   （在 ESP，rootfs 内属发行版冗余副本）
  /dev/**   ← R9 修订（2026-10-12）：真实发行版 rootfs 含设备节点（mknod 产物）；
             devtmpfs 运行期整体覆盖 /dev，镜像内节点内容无意义，且设备号编码
             不进哈希的方案反而引入新歧义 —— 直接排除。v2 尚未发布任何镜像，
             记修订不升 format_version（与 R6/R7 同理）；参考实现已同步。
```

- 排除清单**冻结**在本节；增删即 `format_version` 讨论项。
- 同一 rootfs 内容两次打包（不同分区大小/不同 PARTUUID/不同 zst 参数）→ `content_id` 必须相同；
  不同内容必须不同（构建自检：重打包一次比对）。

---

## 5. 读取算法（应用侧伪代码，唯一权威时序）

```
size = file.length()
if size < 64 KiB: reject("too small")                                # → NOT_AN_IMAGE

# ── 阶段1：是不是 v2 磁盘 ──
read [0,512) as mbr
if not (mbr[510..511] == 55 AA and mbr[450] == 0xEE):
    # 无保护性 MBR → 先判是否 v1 家族（诊断阶梯 IMP-D02 v2 版）
    if size >= 4096 and tail[0..8) == "VMDIMG01":  reject("v1 旧格式，请重新获取镜像")  # → LEGACY_FORMAT
    if offset0 == "hsqs":                          reject("v1/裸 squashfs 旧格式")        # → LEGACY_FORMAT
    reject("非 VMDroid 系统镜像")                                                      # → NOT_AN_IMAGE
read LBA1 as hdr; if hdr.sig != "EFI PART": reject("GPT 头缺失")      # → CORRUPT
verify hdr crc32 / entries crc32 / backup position / first-last usable
                                                  else reject("GPT 校验失败")  # → CORRUPT
parse 128 entries → parts[]
if parts 无一项 unique_guid == META_GUID: reject("缺 meta 分区")        # → CORRUPT
if parts 不满足 严格递增、不重叠、全部 ⊆ [0, size):
                                                  reject("分区越界/重叠")     # → CORRUPT
if root 不是最后分区: reject("root 非最后分区")                            # → CORRUPT

# ── 阶段2：meta 结构 ──
read meta 分区 [0, 512) → magic != "VMDIMG02": 
      若 == "VMDIMG01"(不可能在此) / 其他 → reject("meta 损坏")          # → CORRUPT
if meta.magic_tail at meta_size-8 != "VMDIMG02": reject("meta 尾魔数缺失") # → CORRUPT
if meta.format_version > 2: reject("格式过新，请升级应用")                 # → FORMAT_UNSUPPORTED
if meta.file_size != size:  reject("文件被截断/追加")                      # → CORRUPT
if meta.flags & ~0x3 or (meta.flags & 0x3) != 0x3: reject("flags 非法")    # → CORRUPT
if not (1 <= manifest_size <= meta_size - 148): reject("manifest 长度越界") # → CORRUPT
if meta.root_offset/size 与 §2 公式或 manifest.disk 不一致: reject(...)     # → CORRUPT
manifest 区间 ⊆ meta 分区且不与头部/tail 重叠，否则 reject                # → CORRUPT

# ── 阶段3：manifest 语义 ──
sha256(manifest) == meta.manifest_sha256 else reject("manifest 摘要不符")   # → CORRUPT
parse JSON → format == "vmdroid-system-image" else reject("非本镜像")      # → NOT_AN_IMAGE
               format_version == 2             else reject(...)           # → FORMAT_UNSUPPORTED
validate §4.2 全部规则 → 对应 reason（ARCH_MISMATCH / SSH_CAPABILITY_MISSING /
               SSH_PORT_INVALID / IMAGE_ID_INVALID / APP_TOO_OLD /
               BOOT_MODE_UNSUPPORTED / CONTRACT_VERSION）

# ── 阶段4（full 校验，非启动前快检）──
sha256(root 区间) == meta.root_sha256  else reject("root 分区摘要不符")     # → CORRUPT
（可选，AVF 回退路径实现时）FAT32 解析 ESP → sha256(/vmlinuz) == checksums.kernel_sha256
activePath = file   # 直接作为可写 vdb（QEMU: 无 readonly；AVF: addDisk(writable=true)）
```

**校验时机**（沿用 v1 表 + 状态机）

| 时机 | 动作 |
|---|---|
| 下载 | `sha256(.img.zst) == catalog.sha256` |
| 安装（解压流） | 边解压边算 zst 流 sha + **全零 4 KiB 块跳写（稀疏）** → 全量 `sha256(.img) == catalog.image_sha256` → 阶段 1–3 → 原子 rename → 写 `.meta.json {state:"pristine", size, mtime, image_sha256}` |
| 每次启动前 | 设计文档 §5.4 状态机（pristine 快检 / dirty 归因 / image.log 二分） |
| 手动校验 | pristine → 阶段 1–4 全量；dirty → 仅阶段 1–3 + UI 提示 |
| 激活切换 | `content_id` → `identity` → `contract.version`/`distro.init` |

**读取方 MUST NOT 拒绝**（生成侧约束，MAY 记 warning）：1 MiB 对齐、S 档位、尾隙全零、
ESP 文件清单、`grub.cfg` 内容、`content_id` 的白名单正确性、根分区内部一致性。
**R7 修订（2026-10-12，扩容缺口拍板）**：运行期扩容（§6.5）后 `manifest.disk.file_size`
是**构建期快照**，允许小于 `meta.file_size` —— 以 `meta.file_size` 为唯一真值，
读取方 MUST NOT 因此拒绝；guest 扩容流程**必须**原位回写
**`meta.file_size`（@+16）与 `meta.root_size`（@+80，root 分区新范围）** 两个 u64 LE
（meta 在 root 之前，扩容不移动它；root_sha256 在扩容后视为失效，属 dirty 语义，
仅 full 校验受影响），见 `init-podroid-v2`。

---

## 6. 生成算法（`mkdisk.sh`，取代 v1 `mkimg.sh`）

```sh
tools/mkdisk.sh \
    --rootfs-dir out/rootfs/ \        # 展开的目录树（非 squashfs）
    --manifest  manifest.json \       # §4 模板（派生字段由脚本覆写）
    --kernel    out/vmlinuz \         # 同时进 ESP
    --initrd    out/initrd.img \
    --grub-efi  out/BOOTAA64.EFI \
    --root-size 4096MiB \             # 档位 [2048,4096,8192,16384,32768]MiB
    [--smoke | --no-smoke] \          # 语义沿用 IMP-C03：auto/warn/skip/强制
    -o out/debian.img
```

步骤（**全程无 root、无 loop 挂载**；顺序受依赖约束，设计文档 R-04）：

1. `truncate -s $((266+S/1MiB))MiB out`；`sfdisk` 按 §2.2 写 GPT
   （含备份表；`sfdisk` 对普通文件直接可用）。
2. 建 root：`truncate -s $S rootfs.img` + `mke2fs -t ext4 -b 4096 -L vmdroid-root
   -d rootfs rootfs.img` → `dd conv=notrunc` 至 `[265 MiB, 265 MiB + S)`。
3. 流式计算 `root_sha256`（读该区间）、`content_id`（§4.3）、kernel/initrd sha256。
4. 覆写 manifest 派生字段（IMP-C02 同义：`checksums.root_sha256`、`kernel_sha256`、
   `initrd_sha256`、`disk.*`、`content_id` —— 与 meta 值必须一致）。
5. 建 ESP：`mkfs.vfat -C esp.img 262144` → `mcopy` §2.3 五项文件（含 manifest 冗余副本；
   **`/vmlinuz` 写未压缩 `Image`** —— 对 `--kernel` 入参先 `gunzip -c` 展开，见 §2.3 M-a）
   → `dd conv=notrunc` 至 `[1 MiB, 257 MiB)`。
6. 写 meta 分区 `[257 MiB, 257 MiB + 8 MiB)`：§3 结构 + manifest。
7. **自检**：① 按 §5 阶段 1–3 回读；② GPT 备份表位置与 CRC；
   ③ `sha256(重算 root 区间) == root_sha256`；④ `root 恒为最后分区`；
   ⑤ rootfs 用量 ≤ S×0.8；⑥ manifest ≤ `meta_size−148`。
8. `zstd -19 -T0` → `.img.zst`；输出 `sha256(.img.zst)`、`sha256(.img)`、
   `size`、`image_size` 供 `mkcatalog.sh`。
9. **冒烟**：`qemu-system-aarch64 -M virt -bios <QEMU_EFI.fd> -drive file=out.img,format=raw …`
   （**无 `-kernel/-initrd`**）→ 90 s 内 `Ready!` + `ssh -p 9922` 可登录；
   `--no-smoke` 仅供测试向量生成。
   （固件实测：`-bios QEMU_EFI.fd` 2 MB 与 AAVMF pflash 功能/时延等同 —— P0-2 第六轮；
   到 `Ready!` 在 PC/TCG 约 +9s，见 runbook §P0-2 最终结论。）

---

## 7. 分发容器与 catalog（schema v2）

### 7.1 `.img.zst`

| 项 | 约束 |
|---|---|
| 容器 | 标准 zstd frame（无 seek 参数），`zstd -19 -T0` |
| 内容 | 完整 `.img` 字节流（含全零尾，zstd 对零段压缩比极高 → 下载体积仍小） |
| 解压 | 应用流式解压 + **全零 4 KiB 块跳写**（稀疏落盘）→ 装后实际占用 ≈ rootfs 用量 |
| 校验 | 下载：`sha256(.img.zst) == catalog.sha256`；安装后：`sha256(.img) == catalog.image_sha256` |

### 7.2 `catalog.json`（schema bump）

```jsonc
{ "schema": 2,
  "assets": [{
    "image_id": "debian-minimal-arm64",
    "url": "debian-minimal-arm64.img.zst",
    "size": 0,            // .img.zst 字节数 → 下载预检（+32 MiB margin）
    "sha256": "…",        // .img.zst 摘要 → 下载校验
    "image_size": 0,      // .img apparent 字节数（= 266 MiB + S）→ 落盘预检
    "image_sha256": "…",  // .img 摘要 → 安装校验
    "display_name": "…", "identity": "…", "variant": "…", "version": "…",
    "system_version": 35, "arch": "arm64", "app_min_version_code": 1,
    "channel": "stable", "notes": "…"
  }],
  "…": "签名 = ECDSA P-256 over 规范化 JSON（机制同 v1）"
}
```

- 应用对 `schema < 2` **拒绝**并提示升级；签名公钥与机制沿用 v1。
- 落盘预检用 `st_blocks` 语义估算实际占用（apparent size 是 GiB 级，不能直接判空间不足）。

---

## 8. 兼容性矩阵

| 输入 | 识别 | 处理 |
|---|---|---|
| v2 `.img`（保护性 MBR + `VMDIMG02`） | §5 阶段 1–3 | 全功能 |
| v1 `.img`（尾部 `VMDIMG01`） | 无 0xEE 分区 + 尾魔数 | **`LEGACY_FORMAT`**：拒绝启动 + 提示"旧格式，请重新下载" |
| 裸 squashfs（`hsqs` @0） | 无 0xEE 分区 + `hsqs` | **`LEGACY_FORMAT`**（同上引导重下；不建议提示"封装"——mkimg.sh 已废弃） |
| GPT 磁盘但缺 meta 分区 / meta 魔数错 | 阶段 1/2 | `CORRUPT` |
| `format_version > 2` | meta | `FORMAT_UNSUPPORTED`（提示升级应用） |
| 其他 zip/伪 img | 阶段 1 | `NOT_AN_IMAGE` |
| 文件被截断/追加 | `meta.file_size != size` 或尾魔数 | `CORRUPT` |

> `LEGACY_FORMAT` 是 BootGuard 枚举的**新增值**（v1 reason 表 10 项 → 11 项），
> `image.log`、UI 文案、selftest 断言需同步。

---

## 9. 测试向量

| # | 向量 | 期望 |
|---|---|---|
| 1 | 合法镜像（默认 4 GiB 档） | 阶段 1–3 通过；`flags == 0x3`；分区表与 §2 逐 LBA 一致 |
| 2 | `content_id` 稳定性 | 同一 rootfs 打包两次（不同 S/PARTUUID/zst 参数）→ `content_id` 相同；改一个文件 → 不同 |
| 3 | v1 `.img`（尾 `VMDIMG01`） | `LEGACY_FORMAT` |
| 4 | 裸 `.squashfs` | `LEGACY_FORMAT` |
| 5 | 文件截断 1 字节 | 尾魔数/GPT 备份位置失败 → `CORRUPT` |
| 6 | 文件尾追加 1 字节 | `meta.file_size != size` → `CORRUPT` |
| 7 | GPT header CRC 翻转 | `CORRUPT` |
| 8 | meta 尾魔数覆写 | `CORRUPT` |
| 9 | manifest 单字节翻转 | `manifest_sha256` 不符 → `CORRUPT` |
| 10 | root 分区单字节翻转（full 校验） | `root_sha256` 不符 → `CORRUPT` |
| 11 | 删除 meta 分区（改表项） | `CORRUPT`（找不到固定 GUID） |
| 12 | root 分区移到最后之前（表项重排） | `CORRUPT`（"root 非最后分区"） |
| 13 | `boot.mode != "uefi"` | 拒绝（BOOT_MODE_UNSUPPORTED） |
| 14 | `contract.version = 1` | 拒绝（CONTRACT_VERSION） |
| 15 | `arch != arm64` / `ssh:false` / `ssh_port:2222` / `id` 含 `/` | 各对应 reason |
| 16 | `format_version = 3` | `FORMAT_UNSUPPORTED` |
| 17 | flags bit2 置位 / bit0 清零 | `CORRUPT`（掩码与一致性） |
| 18 | 稀疏解压等价性 | `.img.zst` 解压（含跳零）后字节 == 原 `.img`，`image_sha256` 相等 |
| 19 | 扩容回归 | 宿主 `setLength(+N)` → guest `sgdisk -e`+`sfdisk -N 3`+`resize2fs` → 可用空间增加、meta/ESP 原样、`file_size` 字段更新后快检通过 |
| 20 | **PC 冒烟** | 无 `-kernel` 启动 90 s 内 `Ready!` + `ssh -p 9922` |
| 21 | **AVF 回退** | 从 ESP 提取 kernel/initrd 直启 → 同样到 `Ready!`（P0-1 结论决定是否必测） |
| 22 | grub 镜像缺 `gzio` | `--smoke` 失败，GRUB 报 `plain image kernel not supported`（**与"真·缺 EFI_STUB"同错** → 模块清单必须冻结含 `gzio`；现网内核已有 stub，P0-2 F1/F1b 教训） |
| 23 | **ESP 内容破坏**（改 `BOOTAA64.EFI`/cfg/vmlinuz 任一字节） | 读取方**接受**（阶段 1–3 不校验 ESP；IMP-C01"MUST NOT reject"——M1 打包器自测向量） |
| 24 | `search --fs-uuid` 用裸 hex（非 `XXXX-XXXX`） | GRUB `no such device` → 退化 prefix 兜底；双盘拓扑下引导失败 → `--smoke` 拦截（M1 教训 L3/L4） |

---

## 10. 与设计文档的对应关系

| 本文 | DESIGN-disk-image-v2.md |
|---|---|
| §2 布局 / 分区序 | §2（评审修订 R-01/R-02） |
| §2.3/§2.4 ESP 与 GRUB | §3.1/§3.3 |
| §3 meta 结构 | §5.1（R-03 移除 esp_sha256） |
| §4 manifest / content_id | §5.2 / §5.4 |
| §5 读取算法与状态机 | §5.3 / §5.4 / §5.5 |
| §6 生成算法 | §8.1（R-04 步骤重排） |
| §7 容器与 catalog | §6.1 / §9 |
| §8 兼容矩阵（`LEGACY_FORMAT`） | §10 硬切换 |
| §9 测试向量 | §12 TESTPLAN（本文为 L0 权威清单） |
| AVF 回退读 ESP | §3.2 方案 B（P0-1） |
