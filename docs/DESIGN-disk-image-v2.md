# VMDroid 完整磁盘镜像 v2 设计文档（分区表 + 引导记录 + 可写文件系统）

**状态：已冻结（2026-10-12，R5 三方交叉评审：DESIGN ↔ IMAGE-FORMAT-v2 ↔ mkdisk.sh 参照实现）**
· 后续变更须在 §0.1/§0.2 追加修订记录 · 关联：[DESIGN.md](DESIGN.md)、[IMAGE-FORMAT.md](IMAGE-FORMAT.md)（v1，本文取代对象）

> 本文是"把系统镜像从**裸 squashfs + 尾随 footer**改为**带分区表、引导记录、完整可写文件系统的
> 标准磁盘镜像**"的设计文档。评审通过后，v2 的字节级规格单独落 `IMAGE-FORMAT-v2.md`，
> 再进入实现（里程碑见 §13）。

### 0.1 自评审修订记录（2026-10-12，v0.1 → v0.2）

| # | 发现的问题 | 修订 |
|---|---|---|
| R-01 | **布局缺陷**：meta 分区原排在 root 之后 ⇒ 运行期扩容 root 必须搬移元数据分区 | 分区序改为 **ESP → meta → root**，root 恒为最后分区（§2/§6.5/§8.1 同步） |
| R-02 | `file_size` 公式漏算 meta 分区 8 MiB | `file_size = 266 MiB + S`（§2 约束表） |
| R-03 | **自引用循环**：meta 记 `esp_sha256`，而 ESP 内又有 manifest 冗余副本（manifest 又要含 esp 摘要） | 移除 `esp_sha256`；ESP 完整性改由 `kernel/initrd_sha256` 覆盖并写明威胁模型（§5.1 注） |
| R-04 | 构建步骤顺序错误（manifest 依赖 root 摘要，ESP 又要装 manifest 副本） | 步骤重排：GPT → root → 摘要 → manifest → ESP → meta → 自检（§8.1） |
| R-05 | "删除 `Stacking overlay...` 标记、同步 contract.markers"与事实不符 | 该打印本就不在 `BootStageDetector.MARKERS` 8 项内；`contract.markers` 五项子集不变（§4.1/§7） |
| R-06 | 引用了仓库中不存在的 `assets/firmware.properties` | 更正为 IMAGE-FORMAT §4 的规划性表述（§7） |
| R-07 | initramfs 无 udev，`/dev/disk/by-partuuid` 不可用，分区定位缺可实现细节 | 固定序 `/dev/vdb3` + cmdline `vmd.root_partuuid=` 校验 GPT 表项（§4.1） |
| R-08 | `userKernelExtras`（自定义内核参数设置）在无 `-append` 下的去路未覆盖 | 并入 TBD-1（方案 b 的 `user.cfg` 或砍设置项，§3.4） |
| R-09 | dirty 归因依赖"正常关机"，app 崩溃后 size/mtime 不符会误判篡改 | 引入 `image.log` boot 记录二分归因规则 + 明示"首启即 dirty"窗口（§5.4） |

### 0.2 后续决策记录（2026-10-12，M0 spike + M1 打包器实测后拍板）

> 用户委托"解决剩余三件事"，其中时延目标等决策由本文档评审代理依据实测拍板；
> 与实测冲突时以实测为准并回改本表。

| # | 决策 | 依据 |
|---|---|---|
| D7 | **固件 = `-bios QEMU_EFI.fd`（2 MB 单文件）**，AAVMF 双 pflash 仅在"需要可写 UEFI 变量"时启用 | P0-5 静态实测（AAVMF 表观 64MiB×2）+ P0-2 第六轮 A/B（功能/时延等同） |
| D8 | **ESP 内核存未压缩 `Image`**（`--kernel` 入参为 gzip 时构建期展开） | P0-2 第五轮：−2.09s；`.img.zst` 整盘压缩使分发体积不受影响 |
| D9 | **GRUB 采用嵌入式 cfg（`grub-mkimage -c`）+ `search --fs-uuid`（`XXXX-XXXX`）+ 无菜单顶层命令；`gzio` 必打** | M1 打包器五条实现教训（runbook §M1 进展 L1–L5）：双盘拓扑实测只有嵌入式 cfg 稳定 |
| D10 | **启动时延目标按后端分档（原"统一 ≤3s"废止）**：PC/TCG **≤ +10s**（实测 +9.13s，✅ 达标）；手机 QEMU(TCG) **≤ +5s**；AVF(KVM 固件启动，若 P0-1=方案 A) **≤ +2s** —— 后两档为验收线，待 M-c/P0-1 真机数据回填，超标则回本表重议 | P0-2 六轮分解（EDK2 ~6s + UEFI 装载 ~3s 为 TCG 固有；KVM 下固件段预估 <1s） |
| D11 | **`mkdisk.sh` 为格式的参照实现**：规格与实现冲突时以"能通过 mkdisk 自检 + 双盘引导"的组合为准回改文档 | M1 交叉验证（2026-10-12：build/verify/负向向量/单盘/双盘引导全绿） |
| D12 | **`checksums.kernel_sha256` = ESP `/vmlinuz` 实际字节**（gzip 入参时为展开后 Image）——原"构建入参"语义与展开式存储冲突，AVF 回退校验必然失败（规格 **R6** 修订；mkdisk/codec/夹具已对齐） | D 线 codec 冒烟暴露；实测 manifest==ESP 展开件 sha ≠ gzip 入参 sha |
| D13 | **扩容（§6.5）guest 必须回写 `meta.file_size`(@+16) 与 `meta.root_size`(@+80)** ——宿主 setLength 后两者永久陈旧，不回写则扩容后 codec 结构校验永久 CORRUPT（规格 **R7** 修订；`manifest.disk.file_size` 为构建期快照，以 meta 为唯一真值） | I 线 E2E + mkdisk 复检发现；实测回写后扩容镜像 `--verify` = VERIFY_OK，M2 套件 25/25 |

**R5 交叉评审（冻结依据，2026-10-12）**：DESIGN ↔ IMAGE-FORMAT-v2 ↔ `mkdisk.sh` 三方一致性
核对，修复 6 处陈旧/不一致（§3.1/§3.3 的 `--part-uuid` 引用、`menuitem` 模板、manifest 示例
`fat_uuid` 缺失、content_id 措辞与参考实现对齐、测试向量补 #23/#24、TBD-1 方案 b 落点）。

---

## 0. 决策记录（2026-10-12，用户拍板）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 引导方式 | **纯磁盘引导**：移除 v1 的 footer kernel/initrd payload 段，镜像自带引导记录与内核，运行时不再用 QEMU `-kernel/-initrd` 直启 |
| D2 | 根文件系统 | **可写 ext4 根分区**（放弃 squashfs 只读 + overlay 模型） |
| D3 | 分区表 | **GPT**（+ 保护性 MBR）；原选项中的"BIOS/UEFI 双引导"见下方技术修正 T1 |
| D4 | 兼容策略 | **硬切换，只认 v2**：v1 `.img` 一律拒绝并提示重新获取，不做双栈 |
| D5 | 运行时可写模型 | **单文件直写（无母盘、无克隆、无 backing）**：`images/<id>.img` 就是 guest 直接写入的运行盘 |
| D6 | 下载/分发 | **`.img.zst` zstd 容器 + 安装期流式解压**；**根分区构建期预留容量，后期按需在线扩容**（用户 2026-10-12 修正：不是"裸 .img 直发"，而是"预留容量 + 可扩容"） |

**技术修正（实现约束，非偏好）：**

| # | 修正 | 说明 |
|---|---|---|
| T1 | **arm64 没有传统 BIOS** | guest 是 arm64（`-M virt`），QEMU 不提供 SeaBIOS/CSM，MBR 引导代码无处可跑。v2 的"引导记录"= **保护性 MBR + GPT + ESP 内 UEFI 引导文件（GRUB `BOOTAA64.EFI`）**；MBR 引导代码与 BIOS boot partition 只在将来 x86-64 移植时启用（格式上预留 1 MiB 对齐间隙，见 §2） |
| T2 | UEFI 需要固件 | QEMU 端新增 **AAVMF（edk2 aarch64）** pflash 资产；AVF/crosvm 是否支持固件启动**未知 → P0 spike P0-1** |
| T3 | ext4 未压缩的体积代价 | 根分区未压缩：镜像 apparent size ≈ 分区总大小（GiB 级）。缓解见 D6（zstd 分发 + 稀疏落盘 + 预留可扩容），实测进 P0-4 |

---

## 1. 背景与目标

### 1.1 为什么要改

v1（`IMAGE-FORMAT.md`）是"文件 0x0 即 squashfs superblock + 尾随 kernel/initrd/manifest +
4 KiB footer"的**裸 squashfs 变体**。它的优点（零拷贝直挂、体积小）也是它的天花板：

| 痛点 | 现状 | v2 目标 |
|---|---|---|
| 不是标准磁盘镜像 | 无分区表、无引导记录，`losetup`/`gparted`/`fdisk`/标准装机工具全不可用 | **GPT + 保护性 MBR + ESP**，任何标准工具可直接检查、克隆、修改 |
| 不能独立启动 | 必须宿主 `-kernel/-initrd` 直启，内核绑定在 APK 资产里 | **UEFI → GRUB → 内核** 全链在镜像内，PC 一条 `qemu-system-aarch64 -bios … -drive file=x.img` 启动到 `Ready!`，与 APK 完全解耦 |
| rootfs 只读（写走 overlay） | 系统改动堆在 `storage.img` 的 overlay upper，`apt install`/内核升级与数据盘纠缠 | **可写 ext4 根分区**：系统归系统（`.img`），数据归数据盘（`vda`） |
| 格式私有 | 只有自研 codec 能解析 | 文件系统 + 分区表即规格，manifest 是唯一自定义部分 |

### 1.2 目标（验收口径）

1. `.img` = GPT 分区磁盘：ESP(FAT32) + ext4 根（rw），可被 `fdisk -l`/`parted`/`gparted` 正确识别。
2. QEMU（AAVMF UEFI）与 PC 端**不带任何 `-kernel/-initrd`** 启动到 `Ready!` + SSH 可登录。
3. ext4 根分区可写：guest 内 `apt install`、写 `/etc`、装内核，**重启保留**。
4. 根分区容量**构建期可配置、运行期可扩容**（见 §6.5）。
5. 分发资产 `.img.zst`，下载体积与 v1 同量级（目标 ≤ v1 的 1.5×，P0-4 实测），安装后
   实际磁盘占用 ≈ rootfs 实际用量（稀疏落盘）。
6. 生命周期机制不倒退：安装校验、identity/契约重置判定、恢复出厂、脏状态可见、
   硬切换后老镜像明确报错。

### 1.3 非目标（本期不做）

- 不做 Secure Boot / 签名内核（预留：PE/COFF 校验链可后加）。
- 不做 x86-64 guest（格式预留 BIOS 引导位，但不实现）。
- 不做双格式共存（D4：v1 直接淘汰）。
- 不做 qcow2/overlay 增量盘（D5 已否决，见 §15 备选表）。
- 不改变 vda（`storage.img`）数据盘模型：仍是"独立可写数据盘 + 恢复出厂清零重建"。

---

## 2. 镜像布局（v2 字节级草案）

```
 LBA / offset
 0          ┌────────────────────────────────────────────┐
            │ 保护性 MBR (0xEE 分区, 0x55AA 签名)          │  ← 引导记录①
 1          │ GPT header ("EFI PART")                     │
 2..33      │ GPT 分区表项 ×128 (16 KiB)                   │
 34..2047   │ 间隙（≈1 MiB；x86 移植时放 BIOS boot 代码）   │
 1 MiB      ├────────────────────────────────────────────┤
            │ p1  ESP      FAT32  256 MiB  (EF00)          │  ← 引导记录②
            │   /EFI/BOOT/BOOTAA64.EFI  (GRUB arm64 独立版)│
            │   /grub/grub.cfg          (root=PARTUUID…)  │
            │   /vmlinuz                (arm64, EFI stub) │
            │   /initrd.img             (含 init-podroid v2)│
            │   /vmdroid/manifest.json  (仅冗余副本，见 §5.1)│
 257 MiB    ├────────────────────────────────────────────┤
            │ p2  vmd-meta raw  8 MiB (8300, 固定分区GUID) │  ← 自描述元数据（§5）
            │   magic "VMDIMG02" + manifest + root_sha256  │
 265 MiB    ├────────────────────────────────────────────┤
            │ p3  root  ext4  rw  size = S (默认 4096 MiB)│  ← 完整文件系统，**必须是最后
            │      label=vmdroid-root  PARTUUID=构建期生成 │     一个分区**（扩容不搬数据）
            │      （/home 不在此，见 §4.3）                │
 265+S MiB  ├────────────────────────────────────────────┤
            │ 未分配尾隙 ≈1 MiB（扩容首步的插入空间）         │
 文件末尾    │ 备份 GPT 表项 + 备份 header（最后 33 扇区）     │  ← 引导记录③
            └────────────────────────────────────────────┘ file_size
```

**约束表**

| 项 | 约束 |
|---|---|
| 扇区 | 512 B 逻辑扇区（QEMU virt / crosvm 默认），分区 1 MiB 对齐 |
| `file_size` | `= 1 MiB + 256 MiB + 8 MiB + S + 1 MiB = 266 MiB + S`，S 为根分区大小；**恒为 1 MiB 整数倍** |
| 分区序 | p1(ESP) → p2(meta) → p3(root) 严格递增、不重叠，尾部保留 1 MiB 未分配 + 备份 GPT；**root 恒为最后分区**（评审修订 2026-10-12：否则扩容要搬移元数据分区） |
| 分区定位 | codec **不靠序号**定位：meta 用**固定 partition GUID** 常量，root 用 `type=Linux FS 且 name="root"` + manifest 记录其 PARTUUID（序号仅写进 `disk.root.part` 供工具读） |
| S（根分区） | 构建参数 `--root-size`，档位 `[2, 4, 8, 16, 32] GiB`，默认 **4 GiB**；至少为构建期 rootfs 实际用量 ×1.25（构建期断言，超限 fail） |
| ESP | 固定 256 MiB（kernel ~20–70 MB + initrd ~20–60 MB + GRUB ~15 MB，余量充足） |
| PARTUUID | root 的 partition GUID 构建期随机生成，写入 `grub.cfg` 与 `manifest.json`；同一镜像重打包必须保持稳定（回归测试） |
| 尾部未分配区 | 必须全零（扩容时 guest `sgdisk -e` 先把备份表挪到新末尾，见 §6.5） |
| 压缩 | 文件本体**不压缩**（它是可写运行盘）；压缩只发生在**分发容器** `.img.zst`（D6） |

> **为什么留 1 MiB 尾隙**：扩容第一步是把文件末尾向后延长，备份 GPT 必须先移到新末尾
> （`sgdisk -e`），再把最后的 root 分区末尾往后吃空间；留白让"扩文件 → 备份表搬家 →
> 扩分区"在任何容量档位都能先完成，且**全程不搬动任何分区数据**（meta/ESP 都在 root 之前）。

---

## 3. 引导链

### 3.1 QEMU 后端（主路径）

```
QEMU -M virt + -bios qemu/QEMU_EFI.fd   （2 MB 单文件固件，P0-5 定案，A/B 已验）
  → edk2 UEFI 扫描磁盘 → 找 \EFI\BOOT\BOOTAA64.EFI（vdb 的 ESP）
  → GRUB（独立镜像，**嵌入式 cfg 以 search --fs-uuid 定位 ESP**，盘序无关，见 3.3）
  → 读 /grub/grub.cfg → 加载 ESP 内 /vmlinuz + /initrd.img
  → 内核 + initramfs(init-podroid v2) → 挂 p3(root ext4) → switch_root → systemd → "Ready!"
```

新增 QEMU 参数（相对 v1 变化）：

```
# 新增：UEFI 固件（APK 资产 qemu/QEMU_EFI.fd，2 MB）
-bios <assets 提取路径>/qemu/QEMU_EFI.fd
# 删除：-kernel / -initrd / -append
# vdb 由 readonly=on 改为可写：
-drive file=images/<id>.img,if=none,id=drive2,format=raw,cache=writeback,aio=threads,\
        discard=unmap,detect-zeroes=unmap
```

- `discard=unmap + detect-zeroes=unmap`：guest `fstrim` 后把洞还给宿主（`.img` 保持稀疏，
  扩容后老空间也能回收）；与 vda 现有配置对齐。
- **固件选型（P0-5 定案 + P0-2 第六轮 A/B 验证，2026-10-12）**：`-bios QEMU_EFI.fd`
  （2 MB 单文件，无可写 UEFI 变量）—— 功能与时延与 AAVMF 双 pflash（各 64 MiB）
  **完全等同**（固件段均 ~6s），故**采纳单文件**，APK 固件增量 +2 MB。
  pflash 备选仅在"需要可写 UEFI 变量"时启用（APK 内 zstd 存储，落地解压）。
  实测数据：`docs/spikes/M0-runbook.md §P0-5 / §P0-2 第六轮`。

### 3.2 AVF 后端（**最高风险，P0-1**）

AVF 走 `VirtualMachineCustomImageConfig`（`AvfReflect.setKernelPath/setInitrdPath/addDisk`），
**公开 API 是否支持"无 kernel 的固件启动"未知**（crosvm 本身有 `--pflash`，但 AVF 配置层未暴露）。

| 方案 | 触发条件 | 做法 |
|---|---|---|
| A. AVF 固件启动 | spike 证明 AVF 可给 pflash/固件 | 与 QEMU 同构：`addDisk(img, writable=true)` + 固件 |
| **B. ESP 提取直启（回退，默认按此实现）** | AVF 无固件能力 | 应用**解析 ESP(FAT32)** 读出 `/vmlinuz`、`/initrd.img` → `setKernelPath/setInitrdPath` + `addParams(root=… 等同 grub.cfg 的串)`，数据盘照常 `addDisk` |
| C. AVF 后端本期下线 | B 也不可用（如 kernel 检签） | UI 标注"当前设备仅 QEMU 后端可用"，不阻塞发布 |

> B 方案说明：这不是回退到 v1 —— 内核来源仍是**镜像内部**（ESP），只是绕过 UEFI；
> 且 FAT32 解析器反正要为元数据读取实现（§5），边际成本≈0。
> **本期必选实现 B**（保底可用），A 作为增强。

### 3.3 GRUB 细节（按 M1 `mkdisk.sh` 实测修订，R5 交叉评审）

- 生成：`grub-mkimage -O arm64-efi -p '(hd0,gpt1)/grub' -c embed.cfg <模块清单>` → `BOOTAA64.EFI`。
  **cfg 以 `grub-mkimage -c` 嵌入镜像**（自包含、与盘序无关）——实测教训：仅靠外部
  `/grub/grub.cfg` + 烘焙 prefix `(hd0,gpt1)` 在 **vda(存储)+vdb(镜像) 双盘**拓扑下会指错盘，
  GRUB 连 cfg 都读不到、直接落 rescue shell。模块清单见 IMAGE-FORMAT-v2 §2.3（`gzio` 必打）。
- 嵌入式 cfg（权威；ESP 内 `/grub/grub.cfg` 为同内容副本，供人工/急救）：

  ```
  search --fs-uuid <ESP FAT 卷 ID，XXXX-XXXX 横线形式> --set=root
  set timeout=0
  echo "VMDroid: booting (kernel from ESP)"
  linux  /vmlinuz console=ttyAMA0 mitigations=off vmd.root_partuuid=<PARTUUID>
  initrd /initrd.img
  boot
  ```

  - **不用 `menuentry` 菜单**（单内核顶层命令，实测最稳；菜单写错=内核永不加载）；
    `search --fs-uuid` 必须用横线形式（裸 hex → `no such device` → 退化 prefix 兜底）；
    `search --part-uuid` 在 grub 2.12 报 "unspecified search type"，弃用。
  - FAT 卷 ID 由 `mkfs.vfat -i` 固定，记录在 `manifest.disk.esp.fat_uuid`。
- cfg **不含运行时可变参数**（见 §3.4）；`timeout=0`，无可感知菜单延时。
  实测（P0-2 六轮，PC/TCG）：固件→GRUB ~6.0s、到内核接管共 **+9.13s** vs 直启 ——
  目标按 M-d 分档修订（见 §11 P0-2 与 §0.2 决策记录）。

### 3.4 运行时可变参数的传递（v1 `-append` 没了，TBD-1）

v1 由应用注入：`androidip= ssh=1 podroid.x11.dpi= podroid.bandwidth= podroid.dns=
userKernelExtras`（`QemuEngine.kt:596-603`、`AvfEngine.kt:1111-1113`）。v2 无 `-append`，方案：

| 方案 | 结论 | 原因 |
|---|---|---|
| **a. 固定 cmdline + 运行时参数走已有通道（host-bridge hvc2/vsock + 9p）** | ⚠️ **仅适用 AVF，QEMU 不可行**（P0-7 源码实测后降级） | `podroid-hostd.service` 的 `After=` 含 `podroid-network.service` —— **hostd 比 network 晚**，`podroid-network` 要读的 `dns=`/`bandwidth=` 根本等不到 hostd；`podroid-xvnc` 与 hostd 无顺序保证。改 guest 启动顺序 = 改契约，否决 |
| **b. ESP 内 `/grub/user.cfg`（4 KiB 定长文件），GRUB `source` 拼进 `linux` 行** | ✅ **QEMU 路径选定** | 引导期可用、与 guest 现有"直接读 `/proc/cmdline`"零冲突。代价：需实现 **FAT32 写入器**（写错=镜像损坏）+ 启动前写盘的缓存一致性（停机期写入即可，与 §6.5 扩容同一原则） |
| c. QEMU `fw_cfg` 注入，guest 读 `/sys/firmware/qemu_fw_cfg` | ⚠️ 备选 | 无需写盘，但 `podroid_kernel.config` **未启用 `CONFIG_FW_CFG_SYSFS`** → 要改内核配置 + guest helper；且 AVF 无对应能力（AVF 走方案 B 的 `addParams`，各用各的通道，可接受） |
| d. SMBIOS 注入 | ❌ 否决 | crosvm/AVF 无对应能力 |

**P0-7 源码实测结论（2026-10-12，`Podroid-Debian` 仓库）：**

| 参数 | 消费者 | 归属 |
|---|---|---|
| `ssh=1`、`androidip=` | **无人消费**（`docs/DELTAS.md:52` 明示为上游死参数） | **v2 直接删除，不需任何通道** |
| `podroid.backend=avf` | systemd `ConditionKernelCommandLine`（`podroid-vsock`/`podroid-downloads`） | 引导期必需 —— **仅 AVF**；方案 B 的 `addParams` 天然满足 |
| `podroid.epoch=` | `podroid-bootstrap`（AVF 无 RTC） | 仅 AVF → 方案 B |
| `podroid.tty=` | `podroid-getty` | 仅 AVF（v1 QEMU 本就不传，走默认值） |
| `podroid.dns=`、`podroid.bandwidth=` | `podroid-network`（早于 hostd 启动） | **QEMU 需引导期通道 → 方案 b** |
| `podroid.x11.dpi=` | `podroid-xvnc`（与 hostd 无顺序保证） | 同上 → 方案 b |
| `userKernelExtras`（设置项） | 内核 cmdline | 同上 → 方案 b；或砍掉该设置项（TBD-1 子项） |

> **TBD-1 已收窄为**：QEMU UEFI 路径的 4 个引导期参数（`dns`/`bandwidth`/`x11.dpi`/`userKernelExtras`）
> 用方案 b（ESP `user.cfg`）还是 c（fw_cfg + 内核配置）。**建议 b**（不动内核）；
> AVF 走 §3.2 方案 B，本节不构成其阻塞。

---

## 4. guest 侧改动（contract v2）

> v1 曾以"改 `init-podroid` 违反 G4"为由否决分区方案（DESIGN §4.2 备选 C）。
> D1/D2 决策**必然**要改 guest：`contract.version` 升到 **2**，由 `Podroid-Debian` 侧交付。

### 4.1 `init-podroid` v2（initramfs 内）

| v1 | v2 |
|---|---|
| `mount -t squashfs -o ro /dev/vdb /mnt/lower` | **按 `root=PARTUUID=<p3>` 定位分区 → `mount -t ext4 -o rw,noatime,commit=1,barrier=1 /dev/vdb3 /mnt/root`**（先 `e2fsck -y` 引导修复，参考现 vda 分支的退出码策略，但**绝不自动 mkfs**——根分区损坏必须 FATAL，不得静默重置系统） |
| overlayfs：`lower=squashfs + upper=storage.img` | **删除 overlay 与 `podroid-overlay-normalize` 一次性归一化**；`/mnt/persist` 仍挂 vda，但只承载数据（§4.3） |
| `resize2fs /dev/vda` | 保留；**新增**：`sgdisk -e`（无 sgdisk 时由 sfdisk 重写承担）→ `sfdisk -N 3`（root 恒最后、吃满新空间；先带内核 reread，退 `--no-reread`+`blockdev --rereadpt`）→ 尺寸核验 → `resize2fs /dev/vdb3` → **回写 `meta.file_size`（R7）**（§6.5） |
| 控制台标记 | 保留 `Mounting storage...`（**BootStageDetector 硬匹配项**）、`Mounting system...`；删除 `Stacking overlay...` 打印 —— 它本就不在 8 项 `MARKERS` 里（`BootStageDetector.kt:77-86`），故 `contract.markers` 子集无需改动 |

**分区定位**：initramfs 无 udev → 没有 `/dev/disk/by-partuuid/` 软链。按规范固定序取
**`/dev/vdb3`**（root 恒为第 3 分区），并用 `grub.cfg` 通过 cmdline 传入 initramfs 的
`vmd.root_partuuid=<PARTUUID>` 做**校验**（`dd` 读 GPT 表项 3 的 unique GUID 16 字节比对，
不匹配 → FATAL）。`vmd.*` 是 v2 新增的引导期参数族（固定在 `grub.cfg`，非运行时可变）。

### 4.2 启动时机与标记

initramfs 阶段不变（storage → system → switch_root），只是"system"从 squashfs 变 ext4 直挂；
`podroid-*` 服务单元、`Ready!` 契约、tty 角色（hvc0/1/2、ttyAMA0）**全部原样保留**。

### 4.3 数据归属（关键语义决策）

v1 里 `/home`、`/etc` 改动、`apt install` 全部落在 `storage.img`（overlay upper）——
换镜像/重装系统时**家目录不丢**。v2 中系统改动落 `.img`，若 `/home` 放根分区，
"重装系统"会连家目录一起抹掉（相对 v1 是倒退）。因此：

| 路径 | 归属 | 生命周期 |
|---|---|---|
| `/usr`、`/etc`、`/var`（系统状态） | **root 分区（p3，`.img`，dirty）** | 随镜像重装/切换而重置 |
| `/home`、`/root`（用户数据） | **vda `storage.img`**：首启 `mkdir /mnt/persist/home` + skel 拷贝 → initramfs `mount --bind /mnt/persist/home /home` | 跨镜像切换、跨重装保留（对齐 v1 语义） |
| docker/podman/lxc、Downloads 共享 | vda（同 v1） | 同 v1 |
| 恢复出厂"清数据" | vda 整文件清零重建（同 v1 机制） | 不动 `.img` |

> bind 时机在 initramfs（systemd 启动前），与 v1 overlay 落位时机等价，
> 不改变"用户登录时家目录已就绪"的契约。

### 4.4 镜像内容侧（构建期，`Podroid-Debian` / `distro-build`）

1. 内核与 initrd **打进 ESP**（`/vmlinuz`、`/initrd.img`），`init-podroid v2` 进 initrd。
2. `grub-mkimage` 产物进 ESP `/EFI/BOOT/BOOTAA64.EFI`。
3. 新增内核升级同步 hook（`/etc/kernel/postinst.d/50-vmdroid-esp-sync`）：
   apt 装了新内核 → 挂 ESP(rw) → 复制 `vmlinuz`+`initrd` → 更新 ESP 内副本
   （**权威副本在 ESP**，root 的 `/boot` 只是发行版常规位置）。GRUB 配置指向 ESP 副本，
   故 bootloader 只需 FAT 驱动。
4. `manifest.json` 由构建流水线生成进 ESP `/vmdroid/`（§5）。
5. **内核 `CONFIG_EFI_STUB` 需求 —— ✅ 已排除（2026-10-12 F1 隔离复测）**：
   第一轮"GRUB 拒载现网内核"的报错**实为脚本 `grub-mkimage` 缺 `gzio` 模块**（gzip 流被当
   裸 Image 读 → 报同款 "plain image kernel not supported"）。隔离复测（现网内核 + gzio）
   **直接通过**：内核接管 12.367s / initramfs 18.426s（UEFI 链，`m0-p02` 第四轮）。
   → `podroid_kernel.config` **无需改动**；对格式规格唯一的硬要求是
   **`grub-mkimage` 模块清单含 `gzio`**（IMAGE-FORMAT-v2 §2.3/§6 已冻结），
   最终闸门为 `mkdisk.sh --smoke`。

---

## 5. 元数据、校验与 codec（v2）

### 5.1 元数据放哪（v1 footer 的替代）

v1 的"文件末 4096 B footer"在 v2 **不存在**：文件末尾被备份 GPT 占用，且文件会被 guest
写入（自引用 sha 也不成立）。

| 方案 | 结论 | 原因 |
|---|---|---|
| **manifest 独立小分区（raw，固定偏移）** | ✅ 选定 | 读取方无需解析任何文件系统就能拿到 manifest（Kotlin 只需 GPT + 裸读）；分区内容不随文件末尾移动 |
| manifest 放 ESP 的 JSON 文件 | ❌ 否决 | 读取方要先实现 FAT32 解析才能读到 manifest（鸡生蛋）；ESP 还会被 UEFI/GRUB 使用 |
| manifest 放根分区 `/etc/podroid/` | ❌ 否决 | 需要 ext4 解析器；且 guest 会改写它（dirty 后不可信） |

**布局（即 §2 的 p2）**：`vmd-meta` raw 分区 8 MiB，位于 ESP 之后、root 之前：

```
p1 ESP 256MiB → p2 vmd-meta 8MiB(raw) → p3 ext4 root (S, 最后) → 尾隙 → 备份 GPT
```

p2(meta) 内容（前 8 KiB 有效，其余零）：

| offset | 字段 | 说明 |
|---|---|---|
| 0 | `magic` = `"VMDIMG02"`（8 B） | v2 识别（与 v1 `VMDIMG01` 区分，供旧 codec 明确报"版本过旧"） |
| 8 | `format_version` u32 = 2 | |
| 12 | `meta_size` u32 | 记录区总长（= 8192） |
| 16 | `file_size` u64 | 整个 `.img` 字节数（截断检测） |
| 24 | `manifest_offset` / `manifest_size`（u64×2，相对 meta 分区起点） | JSON manifest |
| 40 | `manifest_sha256` 32 B | |
| 72 | `root_offset` / `root_size`（u64×2，绝对偏移） | root 分区字节范围 |
| 88 | `root_sha256` 32 B | **root 分区字节摘要**（离线导入校验用；构建期算，覆盖 S 全长含零尾） |
| 120 | `esp_offset` / `esp_size`（u64×2，绝对偏移） | ESP 范围（回退方案读取 kernel 用；**不设 `esp_sha256`** —— 见下注） |
| 136 | `flags` u32：bit0=HAS_KERNEL_ESP, bit1=HAS_INITRD_ESP | 保留位必须为 0 |
| 140 | `reserved`（至 8183） | 全零 |
| 8184 | `magic_tail` = `"VMDIMG02"` | 截断/损坏快速判定 |

> meta 分区 = "v1 footer 的精神续作"：**固定位置（固定 partition GUID）、双魔数、自带校验**，
> 但不依赖文件末尾，不怕 guest 写根分区。`file_size` 恒等于实际大小（guest 不改分区布局）。
> 字节级完整字段表（含保留区、对齐、crc 规则）在 `IMAGE-FORMAT-v2.md` 冻结。
>
> **为什么不校验 ESP 整体（评审修订 2026-10-12）**：ESP 内含 manifest 冗余副本，
> 若 meta 记录 `esp_sha256` → manifest 又要记它 → **自引用循环**。改为：
> ESP 完整性由 `manifest.checksums.kernel_sha256/initrd_sha256`（它们在 ESP 内）覆盖，
> 篡改 GRUB/`grub.cfg` 属本地物理接触威胁模型，不作为校验目标（与 v1 同级）。

### 5.2 Manifest（JSON，在 meta 分区内；schema 沿用 v1，增量变更）

```jsonc
{
  "format": "vmdroid-system-image",
  "format_version": 2,                       // ★ 硬切换
  "image": { "id": "...", "identity": "debian:trixie", "variant": "minimal",
             "version": "2026.10.x-r1", "system_version": 35,
             "arch": "arm64", "distro": {...}, "content_id": "sha256:..." },  // ★ 新增，见 5.4
  "disk": {                                   // ★ 新增：布局真值
    "table": "gpt", "sector_size": 512,
    "esp":  { "part": 1, "fstype": "fat32", "size": 268435456, "fat_uuid": "e0ec264b" },
    "meta": { "part": 2, "size": 8388608 },
    "root": { "part": 3, "fstype": "ext4", "partuuid": "...", "size": 4294967296 },
    "kernel_path": "/vmlinuz", "initrd_path": "/initrd.img"   // ESP 内相对路径
  },
  "contract": { "version": 2, "markers": [...同 v1 五项子集...], "kernel": {...} },
  "capabilities": {...}, "accounts": {...}, "app": {...}, "boot": {...},
  "checksums": { "root_sha256": "…", "kernel_sha256": "…", "initrd_sha256": "…" }
}
```

其余字段语义、缺省解释、必需字段表（IMP-D05）、`image.id` 白名单正则、`ssh_port=22`
强制、未知字段忽略等**全部沿用 v1 §4**，不重述。

> **权威副本**：manifest 的真值在 **meta 分区**；ESP 内 `/vmdroid/manifest.json` 是构建期
> 同写的**冗余副本**（供 PC 工具/人工查看，不参与校验）。两者不一致 → 以 meta 为准，记 warning。

### 5.3 读取算法（v2 codec）

```
size = file.length()
size < 64 KiB                                     → NOT_AN_IMAGE
read LBA0: 0x55AA? 分区类型 0xEE?                  → 否 → NOT_AN_IMAGE（诊断阶梯见下）
read LBA1: magic == "EFI PART"?                   → 否 → NOT_AN_IMAGE
GPT: 分区表 crc32、备份表位置 == file_size-33扇区    → 否 → CORRUPT
定位 meta 分区(固定GUID) → 双魔数 VMDIMG02 / file_size==size → 否 → 魔数是 VMDIMG01? → LEGACY_FORMAT(v1, 提示重新获取)
                                     其他 → CORRUPT
parse manifest → sha256 校验 → format_version==2, arch==arm64, id 正则,
               capabilities.ssh==true, ssh_port==22, app.min_version_code
分区边界校验: p1/p2/p3 顺序、不重叠、落在 [0,file_size)；root 恒为最后分区
（full 校验才做）顺序读 root 分区全区 sha256 == root_sha256
```

**诊断阶梯**（保持 v1 IMP-D02 风格）：先判"本应是 v2 但结构坏"（→`CORRUPT`），
再判"是 v1 镜像"（→`LEGACY_FORMAT`，新 reason，BootGuard 拒绝 + UI 提示"该镜像为旧格式，
请重新下载"），最后判"根本不是镜像"（→`NOT_AN_IMAGE`）。

**读取方 MUST NOT 拒绝**的生成侧约束（沿用 IMP-C01）：分区对齐、S 档位、尾隙全零、
ESP 内文件集合 —— 只记 warning。

### 5.4 内容标识与"脏"模型（D5 直写的后果）

v1 用 `rootfs_sha256`（squashfs 段摘要）做"同一系统永不重置"与完整性校验。
v2 中 `.img` 被 guest 写入，整文件/根分区 sha 在**首启后永久失效**。拆成两个概念：

| 概念 | 定义 | 用途 |
|---|---|---|
| `image.content_id`（manifest，构建期） | rootfs **内容树**摘要（`sorted(path,mode,uid,gid,size,sha256)` 的 sha256，**不含**运行时变化项：`/var/cache`、`/etc/machine-id`、`/etc/ssh/*_host_key` 等白名单剔除） | `decideReset()` 的"同一系统"判据（替代 v1 `rootfs_sha256`），版本升级/同源重打包不触发数据盘重置 |
| `image_sha256`（catalog/`.meta.json`，安装期） | 整个 `.img` 解压后的 sha256 | 安装校验（对 `.img.zst` 的 sha256 之外的第二道） |
| `root_sha256`（meta 分区，构建期） | root(p3) 分区字节摘要 | 离线导入校验 / 镜像未被使用前的全量校验 |

**`.meta.json` 状态机**（`SystemImageStore` 扩展）：

```
安装完成: { state:"pristine", size, mtime, image_sha256, ... }
启动前:   pristine + size/mtime 一致 → 放行
          pristine + 不一致 → 查 image.log：安装后是否有本镜像的 boot 记录？
              有 → 归因为 guest 写入 → 转 dirty、刷新 size/mtime 基线、放行（记 info）
              无 → 外部篡改 → 拒绝启动（CORRUPT）
          dirty + size/mtime 与"上次关机记录"一致 → 放行（内容不再校验，记 warning）
          dirty + 不一致 → 同上二分：有 boot 记录归因 guest，否则 CORRUPT
guest 关机: 记录 { state:"dirty", dirty_since, last_mtime }（引擎 stop 时同步做，
            崩溃场景由上一条"启动前归因"兜底）
手动"校验": pristine → 全量（image_sha256 + root_sha256）
            dirty    → 结构+manifest 校验，UI 提示"系统已被修改，无法校验内容；如需回到原厂请重装"（§6.4）
```

**首启即 dirty（明示）**：v2 root 可写意味着 guest 首次启动就会写入
`/etc/machine-id`、SSH host key、journal、`/var` —— **`pristine` 窗口只有
"安装完成 → 首次启动前"**，全量校验必须在该窗口内（安装时已做，§5.5）。
`content_id` 的白名单正是为抵消这些必然写入（§5.4 表第一行）。

限制（明示接受）：dirty 镜像**无法**做内容级完整性校验——这是 D5 的直接代价，
与 v1"启动前快检本就只有 size+mtime"处于同一可信级别（详见 §15 被否决的母盘方案对比）。

### 5.5 校验时机（沿用 v1 表格，增补）

| 时机 | 动作 |
|---|---|
| 下载 | `.img.zst` sha256 == catalog `sha256` |
| 安装（解压流） | 边解压边算 zst 流 sha + 稀疏落盘 → 再全量算 `image_sha256` == catalog `image_sha256` → 结构/manifest 校验 → 原子 rename |
| 每次启动前 | §5.4 状态机 |
| 激活切换 | `content_id` → `identity` → `contract.version`/`distro.init` 三步判定（语义同 v1 §5.2） |

---

## 6. 生命周期

### 6.1 下载与安装（D6）

```
catalog.json (schema v2)
  entry: { image_id, url("*.img.zst"), size(zst字节), sha256(zst),
           image_size(.img 字节), image_sha256(.img), ... }

下载:  cache/<id>.img.zst.part  → sha256 校验 → <id>.img.zst      （预检 margin = size + 32 MiB）
安装:  zstd -d 流式:
       读 zst 块 → 解压 → **全零块跳过不写**（稀疏落盘）→ images/<id>.img.part
       → 全量 image_sha256 校验 → codec 结构校验 → 原子 rename
       → 写 .meta.json {state:pristine}
缓存:  默认保留 <id>.img.zst（供 §6.4 离线重装）；设置项"安装后删除压缩包"可关
```

- 峰值实际占用 ≈ `zst 大小 + rootfs 实用量`（apparent size 是 GiB 级但全零部分不落盘）。
- 安装后 UI 展示两个数字：**占用**（`st_blocks×512`，已有 `HostMetrics`）与**表观容量**（分区上限）。
- SAF 导入同时接受 `.img.zst` 与 `.img`（后者直接走结构+sha 校验，跳过解压）。

### 6.2 激活与启动

同 v1：`active.json` → `BootGuard.checkBeforeStart()` → 引擎。变化仅在引擎参数（§7）与
BootGuard 新增 reason `LEGACY_FORMAT`（v1 镜像）。

### 6.3 切换镜像

同 v1：同 `identity` 家族互切不动 vda；跨 identity 一次确认后清零 `storage.img`。
**新增提示**：若当前镜像 `state=dirty`，切换/删除前 UI 明确告知"该系统内的修改不会保留"。

### 6.4 恢复出厂（D5 直写的代价，两级）

| 级别 | 动作 | 耗时 | 网络 |
|---|---|---|---|
| **A. 清数据**（默认推荐） | 清零重建 `storage.img`（同 v1 机制）；保留 `.img` 现状 | 秒级 | 不需要 |
| **B. 重装系统**（彻底回原厂） | 若有 `cache/<id>.img.zst` → 重新解压覆盖 `.img`（复用 §6.1 安装路径）；无缓存 → 引导去 catalog 重下 | 视体积，分钟级 | 视缓存 |

- 设置页展示缓存占用，允许清理（清理 = 放弃离线重装能力）。
- UI 文案必须区分"清数据"与"重装系统"，避免用户以为 A 能回滚 `apt install`（**不能**）。

### 6.5 根分区扩容（D6："预留容量 + 后期可扩容"）

**构建期**：`--root-size` 档位 `[2,4,8,16,32] GiB`，默认 4 GiB（≈ 实测 rootfs 用量 ×1.25 上限断言）。

**运行期扩容**（停机操作，两步跨两侧）：

```
应用侧（VM 停机时）:
  1) 校验/告警: 读 ext4 超级块 s_free_blocks_count_lo（1024+0x0C，u32）× 块大小
     （1024+0x18 的 s_log_block_size → 1024<<n），只读 4 KiB 超级块，不解文件系统
     → 低空间阈值（默认 <15%）→ 设置页/通知提示"系统盘空间不足，可扩容至 X GB"
  2) RandomAccessFile.setLength(new_size)   # 稀疏追加，秒级
  3) 更新 .meta.json {size, mtime} 基线 + 标记 pending_grow
guest 侧（下次启动，initramfs 内）:
  4) dev_size = blockdev --getsize64 /dev/vdb
     gpt_end  = sgdisk/读备份表得到的末尾 LBA
     if dev_size > gpt_end:
        sgdisk -e /dev/vdb          # 备份 GPT 移到新末尾（无 sgdisk 时由 sfdisk 重写承担）
        sfdisk -N 3                 # root(最后分区) 末尾吃满新空间；**先带内核 reread**，
                                    # 失败退 --no-reread + blockdev --rereadpt
                                    #（--no-reread 单用会让 /dev/vdb3 内核视图停在旧尺寸
                                    #  → resize2fs 必失败，grown 用例实测）
        resize2fs /dev/vdb3         # 先校验内核已见新尺寸，再在线扩容；失败仅 WARN
        回写 meta.file_size = dev_size 与 meta.root_size = /dev/vdb3 新尺寸
        （u64 LE @ meta+16 / +80；**R7**：不回写则扩容后 codec 读取永久 CORRUPT——
        两者都是读取方合法性判据；meta 分区在 root 之前，扩容不移动它 → 原位写安全；
        写失败仅 WARN 不挡启动）
  5) 全部成功才输出 "Root filesystem expanded."（半成功只打对应 WARN）+ 计入契约日志
```

- QEMU/AVF 都以"停机改文件 → 下次开机按块设备实际大小对齐"为原则，
  **不做在线改分区**（宿主不编辑分区表，规避两后端缓存/感知差异）。
- 反向缩容**不支持**（ext4/GPT 缩容风险高，且 D5 下缩容需搬数据）。
- 档位上限 32 GiB 由应用设置页 `SYSTEM_OPTIONS_GB` 提供（与 `STORAGE_OPTIONS_GB` 并列）。

---

## 7. 应用侧（Android）改动面

| 模块 | 改动 |
|---|---|
| `QemuEngine` | 删 `-kernel/-initrd/-append`；新增 pflash 固件参数；vdb 改 rw + `discard=unmap`；`extractBootPayloads()` **删除** |
| `AvfEngine` | 按 §3.2 B：ESP 解析提取 kernel/initrd → `setKernelPath/setInitrdPath`；镜像 `addDisk(writable=true)`；`extractBootPayloads()` 语义改为"从 ESP 提取"（若走 A 则整个跳过） |
| `AvfReflect` | 若 A 可用：新增固件/pflash 相关 API 探测（能力探测失败自动落 B） |
| `VmdImageCodec` | 重写为 v2：GPT + meta 分区（固定 partition GUID）+ FAT32 最小读取器（仅 §3.2 回退需要）；v1 解析路径保留为**仅诊断**（报 `LEGACY_FORMAT`） |
| `SystemImageStore/Repository` | 状态机 `pristine/dirty`；`.img.zst` 下载+流式稀疏解压；两级恢复出厂；扩容 API；`content_id` 判定 |
| `ImageCatalog`/`ImageDownloader` | schema v2（`sha256`(zst) + `image_sha256` + `image_size`）；margin 按 zst 计算，另加 `image_size` 落盘余量检查 |
| `BootGuard` | reason 表增 `LEGACY_FORMAT`；quick verify 接入状态机 |
| `VmdroidApplication` | 资产提取增 `efi/AAVMF_*.fd`；**移除** `vmlinuz-virt`/`initrd.img` 提取（v1 FIXLIST ISSUE-05 的回退） |
| 设置页 | 新增"系统盘容量"档位；展示镜像占用/表观容量/dirty 状态/缓存占用 |
| 契约检测 | `contract.markers` 五项子集不变（overlay 打印本就不在 8 项 `MARKERS` 内）；IMAGE-FORMAT §4 规划的"APK 侧内核 sha 与 `contract.kernel.image_sha256` 交叉比对"取消（v2 内核只来自镜像自身，无第二份真值可比） |

### 7.1 启动过程可视化（v2 新增需求，2026-10-12）

**需求**：用户在应用里点"启动"后，能看到**完整启动过程**——v2 走 UEFI 后，
固件（EDK2 ~6s）与 GRUB 阶段在 v1 里根本不存在，如今它们的串口输出、内核日志、
initramfs/`init-podroid v2` 的 `Mounting storage...` 等标记**全程可见**，既是体验也是排障面。

**现状盘点**（数据流已就绪，缺展示）：
- QEMU：`-serial unix:serial.sock` → `monitorBootSerial()` 同时喂 ①`BootStageDetector`
  ②`filesDir/console.log`（归档，`boots/<boot_id>/` 轮转）③`consoleText: StateFlow<String>`
  （`VmEngine` 接口已有）；
- AVF：consoleOutput 流 → detector + `console.log`；**是否同步喂 `consoleText` 需对齐**
  （QEMU 有、AVF 无则双后端体验不对称 → 本需求一并补齐）；
- UI：`HomeScreen` 只消费 `bootStage`（阶段标签），`consoleText` 无展示端。

**设计**：
1. **展示位置**：启动期间（`VmState` 非 Running）Home 的启动卡片区显示**实时启动日志尾巴**
   （等宽小字，最后 ~12 行 / 1.5 KB，向上滚动），与现有 `bootStage` 阶段标签并列
   （阶段 = 进度条语义，日志 = 明细语义）；`Running` 后自动收起为"上次启动日志"一行摘要，
   终端入口照旧。
2. **数据源**：不新增流——UI 侧对 `engine.consoleText` 做**节流派生**
   （`HomeViewModel` 内 conflate ~200ms 取尾部，避免每字符重组 UI）；引擎侧零改动（QEMU）。
3. **AVF 对齐**：consoleOutput 采集循环同步更新 `_consoleText`（与 QEMU 同语义，
   注意 AVF 隐私门：与 `console.log` 现有门一致，不开 verbose 时也喂内存 flow——
   flow 只活在进程内，不落盘，无隐私差异）。
4. **v2 阶段标记**：EDK2/GRUB 段没有契约 marker——靠**日志原文**呈现（`UEFI firmware...`、
   `BdsDxe:...`、GRUB 的 `VMDroid: booting (kernel from ESP)` echo 行）；
   `BootStageDetector` 的 8 项 marker 语义**不动**。
5. **性能/安全边界**：`consoleText` 现状是全量累积（v1 即如此），本需求只在 UI 侧取尾，
   不改引擎内存语义；后续若长跑内存显著，再议环形缓冲（不在本期）。
6. **明确不做**：启动过程中不可交互的 GRUB 菜单/编辑（`timeout=0`，规格 §2.4）、
   固件设置界面。

**验收**：QEMU 与 AVF 双后端，点启动后 ≤1s 内 Home 出现日志滚动；能看到固件/GRUB/内核/
initramfs 四段输出直到 `Ready!`；Running 后收起；`boots/<boot_id>/console.log` 归档不变。

---

### 7.2 实现期取舍回流（2026-10-12，M3 实现后补记）

> 实现工位在交付时报告、但设计文档此前未覆盖的取舍，按"事实 + 理由 + 后续"回流至此，
> 避免设计文档与代码脱节。逐条均可在代码注释中溯源。

| # | 取舍 | 事实与理由 | 后续 |
|---|---|---|---|
| T-1 | **zstd 解压实现 = 随 APK 分发的静态 `zstd` 可执行文件**（初版 `assets/bin/zstd` → `filesDir/bin/zstd`；**2026-10-13 真机实证 error=13 后迁 `jniLibs/arm64-v8a/libzstdbin.so` → nativeLibraryDir**，`ProcessBuilder(zstd,"-dc")` + 稀疏落盘） | 无纯 JVM zstd 解码器可用（zstd-jni 的 native 是 glibc 构建，Android bionic 加载不可靠）；项目已有"提取 ELF 后 ProcessBuilder 执行"的先例（QEMU） | ~~⚠️ CI 需产出 aarch64 静态 zstd 放入资产~~；**Android 10+ W^X：真机已实证**（2026-10-13：untrusted_app 执行 filesDir 内文件 → EACCES error=13，同文件在 run-as 域可执行 → 判定为 SELinux 域差异），**回退方案已落地**（2.0.1）：二进制迁 jniLibs（`useLegacyPackaging=true` → 解包到 nativeLibraryDir，不可写可 exec），`resolveBinary()` 候选链不变（hint→`$VMD_ZSTD`→`$PATH`→固定位）；落地后二测：glibc 静态版在应用域被 seccomp **SIGSYS（exit 159 = 128+31）**杀于解压中途 → **NDK r28 bionic 静态重编**（syscall 全在应用白名单内）三测通过（安装链 206MB→2.4GB 稀疏 + sha 双校验 OK，真机 OPPO PKX110/Android 16） |
| T-2 | **`CatalogDisplay` 不携带 `systemVersion/arch`**（`.meta.json` 的 display 块缺这两项） | 纯展示字段；下载器交接 `InstallHandoff` 与 Store `InstallDisplay` 收敛时裁剪，避免两工位字段循环依赖 | 需要时在 Store 侧从 manifest 回填（manifest 本来就有） |
| T-3 | **`boot_done` 未入 `LogDiagnostics.SYNC_EVENTS`** | `LogDiagnostics.kt` 属日志工位所有；极端崩溃窗口可能丢 image.log 证据 | 已由 fsync 的 `boots/<id>.meta.json` 第二证据兜底（归因双证据设计）；后续日志工位把 `boot_done` 加进同步集即可 |
| T-4 | **落盘空间启发式 `landingEstimate = min(image_size, zst×3)`** | `image_size` 是 apparent（GiB 级含零稀疏区），直接判空间不足必误杀；只按 zst 判又会漏"下得下装不下"；`.img` 此刻不存在无法读 `st_blocks` | 终极防线仍是安装期落盘写失败（明确报错）；装后 UI 用 `st_blocks` 展示真实占用 |
| T-5 | **下载 margin 饱和加法** | 修 v1 潜在 Long 溢出（`size + 32MiB` 溢出为负 → 静默放行） | 无后续（缺陷修复） |
| T-6 | **install 交接契约**：`InstallHandoff(source, imageId, zstSha256, zstSize, imageSha256, imageSize, display)` → `Store.install(InstallSource, InstallRequest)` | 下载器与存储层解耦（Store 不引用 catalog 类型，保住 selftest 纯 JVM 编译）；`""/0` 视为未知 → Store 自算 | 已编译期对齐并双工位核验；新增交接字段须两侧同步 |
| T-7 | **v1 遗留 `.img.part.info` 不再续传**（`pendingTargets` 只认 `.img.zst.part.info`） | 否则 v1 半成品会被当 v2 任务白下一整包再被拒 | 遗留 v1 文件不主动删（清理属设置页/手工，见 §6.4 缓存清理待办） |
| T-8 | **`VmdDiskProbe` 快速探测（3×512B）与 `VmdDiskCodec.read` 权威解析分工** | 引擎每启一探，走完整 codec 太贵；探测失败一律回退 v1 路径 | **已知假阴性**：损坏的 v2 镜像被判 v1 → 无 `-kernel` → 启动超时（不会错挂盘，guest `vmd.root_partuuid` 校验兜底）——可接受，已写入探针注释 |
| T-9 | **`markBooted()` 接线在 `VmdroidService`**（Starting 之后的 Stopped/Error/Idle 转换处，`runCatching` 不阻断停机） | 正常停机刷新 dirty 基线 + 记 `boot_done`；崩溃窗口由 image.log/boots 双证据归因兜底 | 无后续 |
| T-10 | **引擎 v2 路径本期不传 QEMU 侧 4 参数**（dpi/bandwidth/dns/userKernelExtras） | TBD-1 方案 b 未实现（§16 已更新状态）；guest 走默认值 | TBD-1 定稿后补；`androidip=/ssh=1` 为死参数已删除（P0-7） |

---

## 8. 构建管线改动

### 8.1 新 `mkdisk.sh`（取代 v1 `mkimg.sh`，v1 不再维护）

> **状态（2026-10-12）：雏形已实现并通过交叉验证** —— `build-tools/mkdisk.sh`（原 Podroid-Debian/tools，已迁移）
> （build + `--verify` 单一 parser + 自检 + content_id + zstd），产物在单盘与 vda+vdb
> 双盘拓扑均 UEFI 引导至 initramfs；5 条实现教训回填至 IMAGE-FORMAT-v2 §2.3/§2.4。
> 剩余：真实 distro rootfs 切换、`pc-boot-smoke-v2.sh`、codec selftest v2 夹具
> （runbook §M1 进展）。

```sh
mkdisk.sh --rootfs-dir out/rootfs/ --manifest manifest.json \
          --kernel out/vmlinuz --initrd out/initrd.img \
          --grub-efi out/BOOTAA64.EFI \
          --root-size 4GiB -o out/debian.img
```

步骤（**全程无需 root、无需 loop 挂载**，适配容器构建；顺序受"manifest 依赖 root 摘要、
ESP 又含 manifest 冗余副本"约束）：

1. `truncate -s $((266+S))MiB img` → `sfdisk` 写 GPT：
   p1 ESP `[1, 257) MiB`、p2 meta `[257, 265) MiB`、p3 root `[265, 265+S) MiB`，
   备份表落在文件最后 33 扇区（`sfdisk` 对普通文件直接可用）。
2. root：e2fsprogs 的 `-d` 只支持整文件镜像，故**先单独建 rootfs.img（truncate -s S）** →
   `mke2fs -t ext4 -d rootfs -L vmdroid-root rootfs.img` → `dd conv=notrunc` 进 p3 区间。
3. 计算：`root_sha256`（流式读 p3 区间）、`content_id`（内容树摘要，白名单剔除运行时项）、
   `kernel/initrd sha256`。
4. 组 manifest（权威版本，含上述摘要）→ **写 ESP**：`mkfs.vfat -C esp.img 262144` →
   `mcopy` `BOOTAA64.EFI`、`grub.cfg`、`vmlinuz`、`initrd.img`、`/vmdroid/manifest.json`（冗余副本）
   → `dd conv=notrunc` 进 p1 区间。
5. 写 meta 分区（p2）：双魔数结构 + manifest + `root_sha256`。
6. 回读自检：GPT crc32、备份表位置、manifest sha、root sha、分区边界、**root 恒为最后分区**。
7. `zstd -19 -T0` → `.img.zst`；输出 `sha256`、`image_sha256`、两组体积供 `mkcatalog.sh`。
8. **冒烟（`--smoke` 语义沿用 IMP-C03）**：`qemu-system-aarch64 + AAVMF + -drive file=img`
   （无 `-kernel`）90 s 内 `Ready!` + `ssh -p 9922` 可登录；`--no-smoke` 仅测试向量用。

### 8.2 工具依赖（`build-rootfs/Dockerfile.rootfs` 与 distro-build 各脚本）

`squashfs-tools` → 移除；新增 `e2fsprogs dosfstools mtools util-linux parted/sgdisk
grub-efi-arm64-bin zstd`。`unsquashfs` 自检改为 `debugfs`/`e2fsck -fn` + `mdir`。

### 8.3 APK 资产变化

| 资产 | v1 | v2 |
|---|---|---|
| `vmlinuz-virt` / `initrd.img` | 从镜像 footer 抽取（当前实现） | **彻底删除**（随镜像走，AVF 回退时从 ESP 提取） |
| `qemu/efi-virtio.rom` 等 | 保留 | 保留 |
| `qemu/QEMU_EFI.fd`（`-bios` 单文件固件） | — | **首选，仅 +2 MB**（P0-5 实测）；备选 AAVMF 双 pflash（各 64 MiB，仅当需要可写 UEFI 变量时，APK 内 zstd 存储） |
| `assets/alpine-rootfs.squashfs` | 已废弃 | 删除相关死代码/文档行（`build-all.sh rootfs` 一并下线） |

---

## 9. 分发与 catalog（schema v2）

```jsonc
{ "schema": 2,
  "assets": [{ "image_id": "debian-minimal-arm64",
               "url": "debian-minimal-arm64.img.zst",
               "size": 0,             // .img.zst 字节数（下载预检用）
               "sha256": "…",         // .img.zst 摘要（下载校验）
               "image_size": 0,       // .img apparent 字节数（落盘预检用）
               "image_sha256": "…",   // .img 解压后摘要（安装校验）
               ...其余字段同 v1... }] }
```

- 签名机制（ECDSA P-256）不变；`schema` 字段 bump，应用对 `schema<2` 拒绝并提示升级。
- 落盘预检：`free ≥ image_size 的实际估算（≈ zst×3 或 rootfs 用量+余量）`，
  用 `st_blocks` 语义避免拿 apparent size 误判空间不足（沿用 `HostMetrics`）。

---

## 10. 硬切换与迁移（D4）

1. 应用 versionCode bump；`app.min_version_code` 与镜像双向协商语义不变。
2. 已安装 v1 镜像：`BootGuard` → 新 reason `LEGACY_FORMAT` → 拒绝启动 + UI 提示
   "旧格式镜像不受支持，请重新下载"；提供"清理旧镜像"入口（删除 `.img` + `.meta.json`）。
3. 老 `active.json` 若指向 v1 → 激活状态自动失效（`activeRootfsPath` 语义不变）。
4. `storage.img` **不迁移不清除**（identity 判定沿用，data 盘跨格式保留——用户数据无损）。
5. catalog 旧资产下架；Release 资产改 `.img.zst`；README/TESTPLAN/CLAUDE.md 同步。
6. 不提供 v1→v2 的原地转换（需要 squashfs 解包 + 重建分区，等价于重装，直接引导重下）。

---

## 11. 风险与 P0 spike 清单

| # | 风险/待验证 | 判定标准 | 失败回退 |
|---|---|---|---|
| **P0-1** | **AVF/crosvm 无 kernel 固件启动** | 设备上 `AvfReflect` 能否配置 pflash/固件（或 `setKernelPath` 缺省时从盘启动） | §3.2 方案 B（ESP 提取直启），已列为必做；仍失败 → C |
| **P0-2** | UEFI+GRUB 启动时延（手机 TCG） | ✅ **完成（2026-10-12，六轮）**：功能全通；最优配置 = `-bios QEMU_EFI.fd` + ESP 未压缩 `Image` + 嵌入式 cfg + `gzio`；**最优时延 +9.13s（PC/x86/TCG）**。目标已按 §0.2 **D10 分档拍板**：PC/TCG ≤ +10s（**✅ 达标**）、手机 QEMU ≤ +5s、AVF/KVM ≤ +2s（后两档待 M-c/P0-1 真机回填） |
| **P0-3** | `init-podroid v2` + `/home` bind + 契约标记在真机全通 | `boot-test` 8 项标记 + SSH + X11/pulse + 容器计数 | 回滚 guest 分支，v2 不发布 |
| **P0-4** | 体积/压缩率/安装耗时 | `.img.zst` ≤ v1×1.5；安装 ≤ 90 s；装后实际占用 ≤ rootfs×1.15 | 加档位/减包；zstd 换 `-19 --long`；必要时接受 2× |
| **P0-5** | AAVMF 资产体积 | ✅ **结论（2026-10-12）**：AAVMF CODE/VARS 表观 64 MiB（非零仅 ~2 MB）；**首选 `-bios QEMU_EFI.fd` 单文件 = +2 MB**。功能/时延 A/B = P0-2 第六轮（`M0_FW=bios`） |
| **P0-6** | D5 脏模型的用户认知（"重装才能回原厂"） | 设置页文案评审 | 强化两级恢复出厂的引导文案 |
| **P0-7** | TBD-1：`androidip=` 是否必须早期生效 | ✅ **已完成（2026-10-12，源码判定）**：`ssh=1`/`androidip=` 是死参数；真正需要引导期通道的是 QEMU 侧 4 个参数 → 结论见 §3.4（方案 b） | — |
| **P0-8** | ext4 可写 + `discard=unmap` 长期膨胀 | 长跑：反复安装/删除包后实际占用（`fstrim` 后） | 定期 `fstrim` 单元 + 文档化 |

---

## 12. 测试计划草案（详见后续 `TESTPLAN-disk-image-v2.md`）

1. **L0 静态**：GPT/crc/边界/manifest sha/root sha/`file_size`；v1 镜像 → `LEGACY_FORMAT`；
   截断/覆写/追加/单字节翻转各负向向量（沿用 `codec-selftest` 夹具生成方式独立构造）。
2. **L1 构建自检**：`mkdisk.sh --smoke`（QEMU 无 `-kernel` 启动到 `Ready!` + SSH）。
3. **L2 应用回归**：`systemimage-selftest` 扩展（状态机 pristine/dirty、两级恢复出厂、
   扩容前后 `.meta.json` 一致性、`content_id` 判定矩阵）。
4. **L3 PC 回归**：`tools/pc-run.sh` 改为纯磁盘引导（`imgboot.sh` 重写：不再解 footer 取 kernel，
   改为"校验 GPT/meta 分区 + 直接 `-drive` + pflash"）。
5. **L4 真机**：QEMU(手机) 与 AVF 各一轮：首启、重启保留 `apt install`、清数据、重装、扩容、
   切换镜像（同 identity / 跨 identity）、脏状态提示。
6. **L5 体积/性能**：P0-4/P0-8 的量化记录进 TESTPLAN。

---

## 13. 里程碑

| 阶段 | 交付 | 出口条件 |
|---|---|---|
| **M0 Spike** | P0-1/2/4/7 四项实测（一台 Android + 一台 PC） | P0-1 有 A/B 结论；体积数字落表 —— **状态（2026-10-12）：P0-2 ✅（六轮）/P0-5 ✅/P0-7 ✅；P0-1、M-c 真机数据待设备；P0-4 待 M1**（出口条件尚未全达成） |
| **M1 格式与构建** | `IMAGE-FORMAT-v2.md` 冻结 + `mkdisk.sh` + distro-build 切换 | L0/L1 全绿，PC `--smoke` 通过 |
| **M2 guest 与引导** | `init-podroid v2` + `/home` bind + ESP 同步 hook + 契约 v2 | P0-3 真机全通 |
| **M3 应用侧** | codec v2、zst 安装、状态机、两级恢复出厂、扩容、双后端参数 | L2/L4 全绿 |
| **M4 迁移与发布** | `LEGACY_FORMAT` 路径、catalog schema v2、5 镜像重建、文档同步 | L3/L5 + 首发镜像 Release |

> 依赖关系：M0 → M1 → M2/M3（可并行）→ M4。M0 不过则回退 §3.2-B 深化，不阻塞 M1。

---

## 14. 对现有文档/代码的影响面

| 对象 | 处理 |
|---|---|
| `docs/IMAGE-FORMAT.md`（v1） | M1 冻结后标注 **superseded by IMAGE-FORMAT-v2.md**（保留供 v1 诊断阶梯引用） |
| `docs/DESIGN.md` §2.3/§4/§5/§7/§11.4 | 同步：vda/vdb 语义、备选表（原 C 方案复活）、生命周期、引擎段、PC 启动 |
| `CLAUDE.md` / `README.md` | 移除 squashfs/`-kernel` 直启描述与陈年死代码行（L40/L171/L208 等） |
| `docs/guide/*.html` | `how-it-works`/`migrations`/`backends` 的"squashfs+overlay"叙述改写 |
| `tools/lib/imgboot.sh`、`pc-run.sh`、`selftest.sh` | 重写（footer 解析 → GPT/meta 分区解析；`-kernel` → pflash） |
| `codec-selftest` / `systemimage-selftest` | 夹具生成器改 v2（独立构造仍是规格可实现性的交叉验证） |
| `VmdImageCodecTest.kt` | 负向用例迁移 + `LEGACY_FORMAT` 新用例 |
| Sibling 仓库 `Podroid-Debian`、`distro-build` | `mkimg.sh` → `mkdisk.sh`；rootfs 打包、guest 契约 v2、ESP 同步 hook |

---

## 15. 备选方案与否决记录

| 备选 | 结论 | 原因 |
|---|---|---|
| A. GPT + ESP + ext4(rw)（本文选定） | ✅ | 满足 D1–D6 全部决策；标准工具可用；两后端同构 |
| B. 母盘 + 激活期稀疏克隆 | ❌（D5 否决） | 校验/恢复出厂最干净（母盘 sha 恒有效、秒级恢复出厂），但存储峰值≈2×、安装多一次全量拷贝；用户选 D5 |
| C. qcow2 backing 增量盘 | ❌（D5 否决） | 空间与恢复出厂最优，但 crosvm/AVF 的 qcow2+backing 支持不确定，两后端不对称风险高 |
| D. 单文件直写 + 母盘 = `.img.zst` 缓存兼恢复出厂 | ⚠️ **实为 D5+D6 组合的落地形态**（§6.4-B） | zst 既是分发容器也是"离线原厂还原包"，不计入安装目录（安装目录只有 `.img` 一份），与 D5 不冲突 |
| E. 只读压缩（squashfs/erofs）+ 数据分区 | ❌（D2 否决） | 不满足"完整可写文件系统"的诉求；但其体积优势已由 `.img.zst` + 稀疏 + 零尾压缩部分找回（免费空间≈零，zstd 高压缩比） |
| F. 保留 v1 footer 作为附加元数据区 | ❌ | 文件末尾被备份 GPT 占用；改放独立 meta 分区更稳（§5.1） |
| G. manifest 放 ESP/根分区文件 | ❌ | 读取方要先实现 FAT/ext4 解析（鸡生蛋 / guest 可改写不可信），见 §5.1 |
| H. 双栈兼容 v1+v2 | ❌（D4 否决） | 两套解析、两套冒烟、两套夹具的长期成本；5 个镜像全部重建即可 |
| I. `androidip` 等参数继续走内核 cmdline（fw_cfg/SMBIOS） | ❌ | crosvm/AVF 无对称能力（§3.4 方案 c） |

---

## 16. 待决项（2026-10-12 收敛：3 已定案 / 1 已定向待实现 / 1 待实测回填）

| # | 待决 | 状态 |
|---|---|---|
| TBD-1 | 运行时参数通道（§3.4 b vs c） | ✅ **已定稿（R8，2026-10-12）**：走 **b**——ESP `/grub/user.cfg`（定长 4 KiB，GRUB 脚本形态，嵌入式 cfg `configfile` 链消费，`linux` 行 `$podroid_params`）；应用停机期 FAT **定长覆盖写** + 回读校验 + 注入白名单（详见规格 §2.4 R8）。**验证要求**：真实引导实证 cmdline 注入；FAT 写入器与引擎接线为实现项 |
| TBD-2 | GRUB vs systemd-boot | ✅ **已定案：GRUB**——M1 实测落地（嵌入式 cfg + `gzio` + `search --fs-uuid`），§3.3 按实现修订；systemd-boot 未再评估（GRUB 已满足全部需求） |
| TBD-3 | meta 分区固定 8 MiB 是否留扩展 | ✅ **已定案：保持 8 MiB**，`meta_size` 字段允许 >8192 扩展；8044 B manifest 上限已由 mkdisk 断言 |
| TBD-4 | 根分区默认档位（4 GiB？） | ⏳ **待 P0-4 实测回填**（真实 distro rootfs 用量 → 定 4 GiB 是否满足"用量 ≤ S×0.8"且给用户足量余地）；档位表 `[2/4/8/16/32]GiB` 与 `--root-size auto` 选档逻辑（pack-v2）已实现，改默认值只动一处常量 |
| TBD-5 | AAVMF 是否入 APK | ✅ **已定案（D7）：不入 AAVMF，用 `-bios QEMU_EFI.fd`（2 MB 单文件）**——P0-5 实测 + P0-2 第六轮 A/B 证实功能/时延等同；资产已落 `assets/qemu/`（含 NOTICE）；AAVMF 双 pflash 仅"需可写 UEFI 变量"时备选 |
