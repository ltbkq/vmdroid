# VMDroid 设计文档

**软件名：VMDroid** —— 一个独立的 Android 虚拟机应用 + 可插拔的系统镜像（`.img`）。

| | |
|---|---|
| 状态 | 草案 v0.1（2026-10-07） |
| 关联仓库 | 本仓库 `ltbkq/vmdroid`（应用） · [`ltbkq/Podroid-Debian`](https://github.com/ltbkq/Podroid-Debian)（系统镜像构建） · [`ExTV/Podroid`](https://github.com/ExTV/Podroid)（VM 功能上游，GPLv2） |
| 许可证 | GPL-2.0-or-later（继承上游） |
| 目标平台 | Android 8+（API 26+），arm64（aarch64） |
| 文档语言 | **简体中文为准（主）**，英文为辅（辅译可能滞后，冲突以中文为准） |
| 单位约定 | **MB = 10⁶ B**（体积阈值/发布物）；**MiB = 2²⁰ B**（仅标注字节精确值与对齐）——R3: A-R3-35 |
| 镜像格式规格 | 见 [IMAGE-FORMAT.md](IMAGE-FORMAT.md) |

---

## 1. 背景与目标

### 1.1 现状问题

Podroid 是一个"单体 APK"：虚拟机（QEMU/AVF 引擎、内核、initrd、桥接、终端、VNC）和
Guest 操作系统（`alpine-rootfs.squashfs`）全部打包进同一个 APK。

实测数据（来自 `Podroid-Debian/docs/PLAN.md`）：

| 项 | 大小 |
|---|---|
| 上游 APK | 367 MB |
| 其中 `assets/alpine-rootfs.squashfs` | 297,156,608 B（≈ 283 MiB） |
| Debian 构建产物 `out/debian-rootfs.squashfs` | 345,251,840 B（≈ 329 MiB） |
| 去掉系统镜像后的 APK（估算） | **≈ 70 MB** |

由此产生的具体痛点：

1. **升级系统 = 重打 367 MB APK**。改一行 `/etc` 配置也要用户重新下载整个应用。
2. **磁盘双份占用**。APK 内资产一份 + `PodroidApplication.extractAssets()`
   复制到 `filesDir` 一份（`PodroidApplication.kt:103-108`），峰值 ≈ APK 体积 + 镜像体积。
3. **升级即全量重拷**。`.assets_stamp` 用 `lastUpdateTime` 判断
   （`PodroidApplication.kt:83-91`），任何 APK 升级都强制重新复制 ~300 MB 镜像。
4. **系统与应用强耦合**。换发行版（Alpine → Debian）必须改资产文件名或重编 APK，
   上游 Podroid-Debian 只能沿用历史资产名 `alpine-rootfs.squashfs` 来避免改 Kotlin。
5. **无法离线分发系统**。用户不能单独拿到一个系统文件去备份、拷贝、复用。

### 1.2 目标

> **核心目的只有两条**：
> ① **软件与系统分开** —— 虚拟机是一个独立软件，系统是一个独立文件；
> ② **系统可以更换** —— 换系统 = 换一个 `.img`，不重装应用、不改应用代码。
>
> 首发只提供 **一个** 系统文件 `debian.img`（最小化安装），
> 后续系统（桌面版、容器版、其他发行版）**只上架新镜像，应用不更新**。

| # | 目标 | 验收标准 |
|---|---|---|
| G1 | 虚拟机独立成软件 | VMDroid APK ≈ 70 MB，**不含任何发行版 rootfs** |
| G2 | 系统是单个 `.img` 文件 | 一个自描述、可校验、可独立分发/备份的文件，见 [IMAGE-FORMAT.md](IMAGE-FORMAT.md) |
| G3 | VM 功能参照 Podroid | VM 栈（引擎、socket 布局、启动契约、桥接、终端、VNC、USB）与上游 **1:1 对齐**，见 §3 |
| G4 | **保持 vda + vdb 启动契约** | `init-podroid`、内核、initrd、guest 脚本 **零改动** |
| G5 | 应用内下载 + 手动导入 | 目录订阅 + 断点续传下载；SAF 文件选择器导入；两者均流式校验 |
| G6 | 不破坏持久化语义 | `storage.img`（vda）跨镜像升级保留；换发行版时按 identity 决定是否必须重置 |
| G7 | **首发镜像 `debian.img`（最小化）** | 只含启动契约所需组件（含 Xvnc/pulseaudio，**不含**桌面环境与容器栈）+ kernel/initrd payload；体积 **≤ 150 MB**（与 §4.5 / README 一致）；`boot-test` 报 `Ready!` |
| G8 | 后续系统零应用更新 | 新镜像仅凭 `catalog.json` 上架即可下载、安装、切换（能力差异由 manifest 协商，§4.6） |
| G9 | **`.img` PC 端可启动（需求 R-16）** | Linux PC 上 `qemu-system-aarch64` **一条命令**（`tools/pc-run.sh debian.img`）启动该 `.img` 到 `Ready!`；`.img` 内含 `kernel`+`initrd` payload（§11.4） |

### 1.3 非目标

- 不做 PC **图形前端/桌面应用**；但**提供 PC 端 QEMU 启动支持**（`tools/pc-run.sh` + 文档，
  一个 `.img` 即可启动，见 §11.4 / G9 / R-16）。
- 不做多虚拟机并行（单实例，与上游一致）。
- 不改 Guest 发行版内容本身（仍由 `Podroid-Debian` 构建）。
- 不改启动契约、控制台标记、tty 角色、端口转发语义（见 `Podroid-Debian/docs/COMPAT.md`）。
- 不改上游内核/initrd（v1 阶段随 APK 分发；格式已预留外置槽位，见 §4.2 与 IMAGE-FORMAT §2）。

---

## 2. 总体架构

### 2.1 分离前后

```
【现状 · Podroid 单体】
┌─────────────────────────── APK (367 MB) ───────────────────────────┐
│ VM 引擎(QEMU/AVF) │ 内核+initrd │ UI │ 桥接 │ alpine-rootfs.squashfs │
└─────────────────────────────────────────────────────────────────────┘
        │  extractAssets() 全量复制到 filesDir
        ▼
  filesDir/{vmlinuz-virt, initrd.img, qemu/, alpine-rootfs.squashfs, storage.img}

【目标 · VMDroid 分离】
┌────────── VMDroid APK (~70 MB) ──────────┐      ┌─── 系统镜像 .img (≤150 MB) ──┐
│ VM 引擎(QEMU/AVF) │ 内核+initrd │ UI      │      │ rootfs.squashfs (vdb, 只读)    │
│ 桥接/终端/VNC/USB │ 镜像管理器(新增)       │ ───▶ │ + kernel/initrd payload (PC 启动) │
│ 启动镜像选择控件   │                        │      │ + 自描述 manifest + 校验和      │
└───────────────────────────────────────────┘      └────────────────────────────────┘
        │ 只复制内核/initrd(~20 MB)
        ▼
  filesDir/{vmlinuz-virt, initrd.img, qemu/, images/<image_id>.img, storage.img}

  注：≤150 MB 为**首发最小化镜像**目标；当前 Podroid-Debian 全量构建
      （345 MB / 329 MiB，含容器栈+桌面）仅作体积对比基线。
```

### 2.2 组件图

```
┌──────────────────────────── VMDroid (本仓库) ────────────────────────────┐
│                                                                         │
│  ui/            Home · Terminal · X11 · Settings · Images(新)              │
│                 ★Home 启动镜像选择控件 (§8.2)                              │
│  service/       VmdroidService（前台服务，VM 生命周期/唤醒锁/通知）        │
│  engine/        VmEngine ──┬── QemuEngine   (TCG, QMP, unix sockets)     │  ← 参照 Podroid
│                            └── AvfEngine    (pKVM, vsock, reflection)    │
│                 EngineHolder · BootStageDetector · QmpClient             │
│                 hostbridge/ · usb/ · (avf/ninep 9p 下载共享)             │
│  systemimage/   ★ 新增包                                                │
│     ├ VmdImageCodec      .img 读写/解析/校验 (IMAGE-FORMAT.md)           │
│     ├ SystemImageRepo    已安装镜像 + 当前激活镜像（DataStore）           │
│     ├ ImageInstaller     SAF 导入 → 流式拷贝+sha256 → 激活               │
│     ├ ImageDownloader    HTTP Range 断点续传 → 校验 → 激活               │
│     ├ ImageCatalogRepo   目录(JSON)拉取/缓存 + ECDSA 签名校验              │
│     ├ BootGuard          无镜像/损坏/需重置 时阻止启动并引导              │
│     └ VmdLog             ★ 启动轮转/boot_id/meta.json/image.log/导出(§16) │
│  firmware/       vmlinuz-virt · initrd.img · qemu/   (APK 资产, 保留)     │
└─────────────────────────────────────────────────────────────────────────┘
                      │ vdb = images/<id>.img   (只读, 零拷贝直挂)
                      │ vda = storage.img       (可写, 应用创建/清零恢复出厂)
                      ▼
┌────────────── Guest（仍由 Podroid-Debian 构建，契约不变）────────────────┐
│ init-podroid: vda→/mnt/persist(ext4)  vdb→/mnt/lower(squashfs)          │
│              overlay → switch_root → /sbin/init(systemd)                │
│ 控制台标记(契约子集5条): Loading kernel modules... / Network found /    │
│            Starting SSH... / Almost ready... / **Ready!**               │
│            完整检测列表 8 项见上游 BootStageDetector.MARKERS（§16.4）      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.3 磁盘与文件布局

| 设备 | 文件 | 内容 | 所有者 | 生命周期 |
|---|---|---|---|---|
| `vda` | `filesDir/storage.img` | ext4，可写 overlay upper + `docker`/`containers`/`lxc` 绑定 | App 创建（sparse）；**恢复出厂 = 清零重建**（guest `init-podroid` 自动 mkfs，§4.3） | **跨镜像升级保留**；Reset VM / identity 变更时重建 |
| `vdb` | `filesDir/images/<image_id>.img` | `.img` 文件（squashfs 起始 + kernel/initrd + manifest/footer）；**仅 `.img` 格式**（N4） | 镜像管理器 | 随镜像安装/删除/激活变化 |
| — | `filesDir/vmlinuz-virt`, `initrd.img`, `qemu/` | VM 固件 | APK 资产提取（3 项，不再有 rootfs） | 随 APK 升级 |

**命名基线（R1: A-R1-6，全仓统一）**

| 场合 | 取值 |
|---|---|
| 发布文件名（Release 资产 / 用户手上） | `debian.img` |
| `manifest.image.id` = catalog `image_id` | `debian-minimal-arm64`（**不含版本**，稳定标识） |
| 安装后路径 | `filesDir/images/debian-minimal-arm64.img` |
| 变体系列 | `debian-minimal-arm64` / `debian-desktop-arm64` / `debian-containers-arm64` / `debian-full-arm64`（同 `identity=debian:trixie`；跨发行版按同规则派生 `ubuntu-*`/`alpine-*`） |

```
filesDir/
├── vmlinuz-virt · initrd.img · qemu/        # VM 固件（APK 资产）
├── .assets_stamp                             # 现有 stamp 机制，只剩 3 项资产
├── images/
│   ├── debian-minimal-arm64.img             # 已安装镜像（可多个）
│   ├── debian-minimal-arm64.img.part        # 下载/导入中的临时文件
│   ├── debian-minimal-arm64.img.meta.json   # {sha256,size,mtime,verified_at,format_version}
│   └── active.json                          # {image_id, identity, rootfs_sha256, activated_at}
├── storage.img                               # vda 用户数据盘
├── .last_boot_id                             # ★ 上次 boot_id（轮转配对用，§16.2）
├── verbose.log                               # ★ verbose 明细（§16.7，可选）
├── log.txt                                   # 导出产物（§16.8）
├── boots/                                    # ★ 历史启动归档（meta.json + console.log，§16.2）
├── image.log                                 # ★ 镜像生命周期 JSONL（§16.5）
└── terminal.sock · ctrl.sock · serial.sock · qmp.sock · host.sock · console.log
```

---

## 3. 与 Podroid 的关系（VM 功能参照表）

VMDroid 以 **fork ExTV/Podroid** 的方式实现（GPLv2，保留版权头与 git 历史），
VM 功能 **全部沿用**，只改"系统从哪来"这一件事。

| Podroid 子系统 | 上游位置 | VMDroid 处理 | 说明 |
|---|---|---|---|
| `VmEngine` 接口 | `engine/VmEngine.kt` | **原样保留** | `state/bootStage/consoleText/start/stop/createTerminalSession/addPortForward/...` |
| `EngineHolder` 路由 | `engine/EngineHolder.kt` | **原样保留** | 后端热切换、端口转发 diff、`rulesAppliedAtLaunch` 语义 |
| `QemuEngine` | `engine/QemuEngine.kt` | **仅改 rootfs 路径来源** | `buildCommand()` 的 `-M virt,gic-version=3`、`-cpu max,pauth-impdef=on`、`-accel tcg,thread=multi`、iothread 分离、`discard=unmap` 全部保留；§7.1 |
| `AvfEngine` | `engine/avf/AvfEngine.kt` | **仅改 rootfs 路径来源** | `ensureStorageImage()`、vsock 控制/转发/9p、`addDisk(writable=false)` 保留；§7.2 |
| Socket 布局 | `filesDir/*.sock` | **原样保留** | `terminal.sock`(hvc0) · `ctrl.sock`(hvc1) · `serial.sock`(ttyAMA0) · `qmp.sock` · `host.sock`(hvc2) |
| `BootStageDetector` / `QemuBootMonitor` | `engine/` | **原样保留** | 滚动缓冲区（最后 ~1KB）匹配 `Ready!` → `VmState.Running` |
| 启动阶段脚本 | guest 侧 | **原样保留** | 由 Podroid-Debian 提供，`.img` 的 rootfs 必须包含 |
| host bridge | `engine/hostbridge/` | **原样保留** | QEMU `/dev/hvc2` / AVF vsock 9101，一行请求一行响应 |
| 端口转发 | `PortForwardRepository` + QMP/vsock | **原样保留** | 隐式转发 9922/5900/4713；收紧方案见 §10.1 |
| 终端（Termux fork） | `terminal-view/`, `terminal-emulator/` | **原样保留** | `createTerminalSession()` |
| X11/VNC 查看器 + PulseAudio | `x11/` | **原样保留** | RFB + PCM over TCP |
| USB 直通 | `engine/usb/` | **原样保留** | `add-fd` + `device_add usb-host`（QEMU 专属） |
| 9p Downloads 共享 | `engine/avf/ninep/` | **原样保留** | AVF 专属（QEMU 用 `-fsdev local`） |
| 前台服务 | `service/PodroidService.kt` | **重命名** `VmdroidService` | 生命周期/唤醒锁/通知/自动启动意图 |
| 资产提取 | `PodroidApplication.kt:103-108` | **裁剪为 3 项** | 删除 `alpine-rootfs.squashfs` 任务 |
| 系统镜像 | APK 内资产 | **移除 → 新增 `systemimage/`** | 本设计文档的核心 |
| 包名 | `com.excp.podroid` | `io.github.ltbkq.vmdroid`（见 §12.1） | 新包名 = 无法原地升级上游安装 |
| 品牌/文案 | Podroid | VMDroid | 保留 guest 内 `podroid-*` 标识（契约的一部分） |

> **契约红线**：`podroid-*` 这些 **guest 内部** 的服务名、脚本路径、控制台标记字符串、
> `/etc/podroid/` 目录名，都是启动契约的一部分，**在 `.img` 内必须原样存在**，
> 不随应用改名。改名只发生在 Android 侧类名/包名。

---

## 4. 系统镜像 `.img` 规格（摘要）

完整字节级规格见 **[IMAGE-FORMAT.md](IMAGE-FORMAT.md)**，此处仅列设计动机。

### 4.1 设计约束

1. **必须能直接作为 `vdb` 只读挂载**（QEMU `readonly=on` 与 AVF `addDisk(writable=false)` 一致），
   否则要么复制 300 MB（安装慢、双份占用），要么改 `init-podroid`（违反 G4）。
2. **必须携带自描述元数据**（identity、版本、架构、契约版本、校验和），否则无法做
   升级/回滚/重置决策，也无法做完整性校验。
3. **恢复出厂不得依赖镜像内数据**（决策 2026-10-08，移除 vda 种子）：
   上游 `init-podroid` 对"全零 `storage.img`"会 `e2fsck` 失败 → `mkfs.ext4` 自动重建，
   故应用只需**清零/重建 `storage.img`** 即可恢复出厂，`.img` 里不必带种子。
4. **单文件、可断点、可流式校验**。
5. **PC 端自包含可启动（R-16/G9）**：`.img` 内必须含 **kernel + initrd payload**，
   Linux PC 上 `qemu-system-aarch64` 一条命令启动到 `Ready!`，不依赖 APK/Android。

### 4.2 选定方案：squashfs 前置 + 尾随段 + footer

```
┌────────────────────── .img 文件（单文件） ──────────────────────┐
│ 0x0                rootfs.squashfs (hsqs superblock 在 0x0)     │  ← 直接当 vdb 挂载
│ ...squashfs 结束                                                 │
│ [1 MiB 对齐]     kernel payload（arm64 Image，flags.bit0）★R-16  │  ← PC 端 -kernel 直接用
│ [1 MiB 对齐]     initrd payload（flags.bit1）★R-16               │  ← PC 端 -initrd 直接用
│ [1 MiB 对齐]     manifest JSON (UTF-8)                          │
│ 末尾 4096 B      footer（magic + 各段偏移/长度/sha256）           │
└─────────────────────────────────────────────────────────────────┘
   无 persist 种子段（决策 2026-10-08，见 §4.3）；footer 字段偏移见 IMAGE-FORMAT §3
```

**为什么可行**：squashfs 的 superblock 在文件 0x0，块表用绝对偏移，
内核只读到 `superblock.bytes_used` 为止，**尾随数据天然被忽略** —— 因此
`.img` 本身就是一个合法的 squashfs，可以零拷贝直挂为 `vdb`。

| 备选方案 | 结论 | 原因 |
|---|---|---|
| A. squashfs + 尾随段（选定） | ✅ | 零拷贝直挂；能携带元数据与 kernel/initrd；无需改 initramfs |
| B. 容器格式，安装时拆成两个文件 | ⚠️ 备用 | 需在安装时多写 ~300 MB、峰值双份占用；**作为 A 的降级路径保留**（见 §4.3） |
| C. GPT 分区裸盘（p1=ext4, p2=squashfs） | ❌ | `init-podroid` 直接 `mount /dev/vdb`，不认分区；改它违反 G4 |
| D. QEMU `-blockdev` slice（offset/size） | ⚠️ 后续优化 | QEMU 可行但 AVF/crosvm 无对应能力，两后端会不对称 |
| E. `.img` 就是裸 squashfs（无 footer） | ❌ **不支持** | 无 manifest 则无 identity/版本/校验，无法做升级与重置决策（**需求 N4：只收 `.img`**）；现有 `.squashfs` 产物须经 `mkimg.sh` 封装 |
| F. `.img` 内带 vda 种子（ext4 payload） | ❌ **v1 移除**（决策 2026-10-08） | 见 §4.3：上游 initrd 已能对全零盘自动 mkfs，种子无增量价值 |

### 4.3 两种消费模式（+ 一条降级分支）

| 模式 | 触发条件 | 行为 |
|---|---|---|
| **direct**（默认，首选） | 文件是合法 `.img`（footer 校验通过） | **原地挂载**，零额外写入；`rootfs` 段始终留在 `.img` 内 |
| **factory-reset**（恢复出厂） | 用户点"恢复出厂" | **整文件清零重建 `storage.img`**（`setLength(0)`+`setLength(size)`，见下方 ⚠️）→ 下次开机由 `init-podroid` 自动 `mkfs.ext4`；`rootfs` 仍在 `.img` 内直挂 |

> **恢复出厂为何不需要种子（决策 2026-10-08，R2: RM-24 关闭；判据经 R3 实测修正，B-R3-1）**：
> 上游 `ExTV/Podroid@ce01218` 的 `init-podroid` 内置首启格式化逻辑：
> `mount ext4` 失败 → `e2fsck -y` → **`rc ≥ 8` 且盘首 1 MiB 全零 → `mkfs.ext4 -F /dev/vda`**；
> `rc ≥ 4` → `FATAL` 不重格式化（保数据）；否则直接重挂；之后 `resize2fs`。
> （源码注释："Never reformat a disk e2fsck could read: only a brand-new,
> all-zero storage.img (first boot) gets mkfs"）
>
> - **应用侧动作（必须整文件清零）**：`setLength(0)` → `setLength(size)`
>   （等价：删文件重建 sparse 同尺寸），**不需要**任何镜像内数据；下一 boot guest 自己 mkfs。
>
> ⚠️ **只清零首 1 MiB 无效（R3: B-R3-1，e2fsprogs 1.47.0 本地实测）**：
> 4 KiB 块布局的**备份超级块位于 block 32768 = 128 MiB 处**，只清头部时
> `e2fsck` 输出 `Superblock invalid, trying backup blocks...` 并**回退修复**（rc=1），
> 同时把主超级块写回首 1 MiB → 此后两个分支都不满足（rc<8 且首 1 MiB 已非全零）
> → **静默跳过 mkfs，用户数据原样保留，恢复出厂无声失效**。
> 反之整文件清零 → 备份超级块一并消失 → rc=8 + 全零 → mkfs ✔（实测通过）。
>
> - **顺带关闭 R1: B-R1-18**（种子稀疏提取）与 **B-R2-21**（mkfs/resize2fs 位置
>   已确认在 initramfs 的 `init-podroid` 内，非 guest rootfs）。
> - **代价**：恢复出厂后 guest 需一次完整首启（约 30–60s）；省掉每镜像 100MB+ 种子体积。
> - **隐私**：整文件清零后 mkfs 只重写元数据，数据块已是零 → 无残留；部分清零 + mkfs
>   会保留旧数据块，故**整文件清零同时是隐私要求**。
> - **待验证（initramfs 为上游 gitignored 生成物，本地不可查）**：
>   `zcat assets/initrd.img | cpio -t | grep -E '/(e2fsck|mkfs.ext4|dd|tr|head)$'`
>   —— 确认 initramfs 内是 e2fsprogs 而非 BusyBox `e2fsck`（**BusyBox 退出码语义不同，
>   `rc≥8` 判定不成立**）及 `dd/tr/head` applet 存在（判定式依赖它们）。

> **N4 约束**：启动镜像**只支持 `.img` 格式**。无 footer 的裸 `.squashfs`、
> `.tar*`、其他项目镜像**一律拒绝**并提示用 `mkimg.sh` 封装。
> 这使 identity 恒有 manifest 来源，消灭了 §5.2 的"两套 identity"问题（R1: B-R1-5）。

**降级分支 `fallback-split`（仅当 P0 spike 判定 direct 不可用时）**：

```
spike 失败（某后端不接受尾随数据）
  → 方案 B：安装期把 rootfs 拆成独立文件，vdb 指向拆出文件，.img 仅作容器
     · 峰值/长期占用 = 2×（.img + rootfs 文件并存，因 .img 仍是激活对象与校验来源）
     · ★ 需重新决策 B-R1-17（M0 退出条件之一）：A) 接受 2×；B) 拆分后删除 .img、
       把 .meta.json 迁到拆出文件（则"单文件分发"仅限分发阶段，安装后变两文件）
  → 或：中止 direct 特性，改"导入即拆分"为唯一路径
```

`fallback-split` **不改变格式与导入白名单**（输入仍是 `.img`），只改安装后的落盘形态。
恢复出厂（清零重建）与降级分支互不相干，两者只在"direct 是否可用"上有关联。

### 4.4 版本与兼容协商

manifest 携带，激活前由 `BootGuard` 拦截：

| 字段 | 作用 |
|---|---|
| `format_version` | 解析器兼容（当前 1） |
| `arch` | 必须 `arm64`，否则拒绝 |
| `app.min_version_code` | 镜像要求的最低应用版本 |
| `contract.version` | 启动契约版本（控制台标记、tty 角色、设备语义） |
| `contract.kernel.*` | 内核要求；**Android 端** v1 仍用 APK 内置内核（与 payload 同源同版本）；**PC 端（R-16）直接用 `.img` 内 kernel/initrd payload**。若镜像要求的内核与 APK 内置不一致 → 拒绝激活并提示升级应用 |

### 4.5 首发镜像：`debian.img`（最小化）

首发**只做一个**系统文件，命名固定为 `debian.img`；`image_id` **固定为**
`debian-minimal-arm64`（§2.3 命名基线），`identity = debian:trixie`。

**定位**：能开机、能进终端、能联网的**最小可用 Debian**，其余功能按需后加。

| 维度 | 首发 `debian.img` | 对比当前 `Podroid-Debian` 全量构建 |
|---|---|---|
| 包数量 | **~210–250**（deps 闭包实测 223 / 含 Recommends 247；CI 断言 ≤260） | 362（当前全量构建） |
| 体积 | 目标 **≤ 150 MB**（zstd-19，**含 kernel/initrd payload**，R-16） | 345 MB（329 MiB，对比基线） |
| init | systemd（`systemd-sysv`） | 同 |
| SSH | dropbear（契约要求，`Ready!` 前置） | 同 |
| 登录账户 | **`root` + `ltbkq` 两个账户，默认密码均为 `123`，均可 SSH 登录** | 仅 `root` |
| sudo | `ltbkq` 在 sudo 组（`NOPASSWD`） | 无普通用户 |
| 容器栈 | ❌ 不装 docker/podman/lxc | ✅ 装 |
| **Xvnc (:5900) + 字体** | ✅ **装**（X11 契约，见下） | ✅ 装 |
| pulseaudio (:4713) | ✅ 装（音频契约） | ✅ 装 |
| 桌面环境 (xfce4) | ❌ 不装（用户在 guest 内 `apt install` 或换桌面镜像） | ✅ `--desktop` |

**启动契约服务（`podroid-ready` 按 `After=` 排在它们之后 —— 注意 `After=`
是排序而非依赖，缺失不阻止 `Ready!`，但会让对应隐式转发失效，R1: B-R1-6）**：

```
systemd-sysv → /sbin/init 链路（唯一提供者）
dbus → D-Bus 总线（hostd/pulse 依赖；R1: B-R1-7 —— `dbus-sysv` 非独立包名）
dropbear                      → guest :22（SSH 系统默认端口；宿主 9922 → guest 22）
iproute2, isc-dhcp-client     → podroid-network（AVF 用 dhclient）
util-linux, mount             → 挂载/getty/stty；**overlayfs 由内核 builtin 提供，无对应包**
/usr/local/lib/podroid/*      → bootstrap/network/ready/resize/migrate
/usr/local/bin/podroid-*      → getty/login/resize + overlay-normalize
/usr/local/lib/podroid/podroid-hostd → host bridge（Home 容器计数、通知、端口转发）
sudo, ca-certificates, locales(min)  → 基本可用性
tigervnc-standalone-server, tigervnc-common → podroid-xvnc（:5900，X11 契约）
xfonts-base, fonts-dejavu-core, dbus-x11   → Xvnc 可用（字体缺失则 X 起不来）
pulseaudio, pulseaudio-utils          → podroid-pulse（:4713，音频契约）
```

**Xvnc 为什么属于"最小镜像必须保留"**：`podroid-xvnc.service` 是
`Before=podroid-ready.service` 的**启动契约单元**（上游 Alpine 同样有），
`BootStageDetector` 的 `Ready!` 之前 Xvnc 必须已尝试启动；
应用侧 X11 查看器直接连 `:5900`。**不装 Xvnc = 契约缺口**，
因此首发镜像**装 Xvnc，但不装桌面环境**（Xvnc 起来后是空 X display，
用户在 guest 内 `apt install xfce4 xfce4-terminal` 即可得到桌面，
或换 `debian-desktop.img`）。

**账户与登录（镜像构建期固化，见 §4.8）**：

```
root   密码 123   # 与上游 Podroid-Debian 一致，保留用于 adb/调试/回归脚本
ltbkq  密码 123   # 日常账户，sudo 组，NOPASSWD
dropbear 允许 root + ltbkq 两账户密码登录（两者都能 ssh 进入）
```

**首发明确不装**：docker.io / podman / lxc / crun / netavark / aardvark-dns /
桌面环境（xfce4、lightdm、x11-utils）。

**这意味着应用侧必须能"优雅降级"**（否则首发镜像会被 UI 判成坏镜像）：

| 能力缺失 | 应用表现 |
|---|---|
| 无容器守护进程 | Home 容器计数显示 `—`（不报错），容器页隐藏 |
| Xvnc 有、但无桌面 | X11 入口可用，画面为空 X display；提示"在终端执行 `apt install xfce4 xfce4-terminal`，或在系统镜像页切换到桌面版" |
| dropbear / Ready! | **必需**，`boot-test` 仍以 `Ready!` 为通过标准 |

实现上由 manifest 的 `capabilities` 字段驱动（§4.6），**而不是**应用探测端口 ——
探测会引入启动时延与假阴性；manifest 由镜像作者声明，应用只读。

### 4.6 能力声明（capabilities）

manifest 新增（详见 [IMAGE-FORMAT.md](IMAGE-FORMAT.md)）：

```jsonc
"capabilities": {
  "ssh": true,          // dropbear :22            → 必为 true
  "x11": true,          // Xvnc :5900 + pulse :4713 → 首发 debian.img 即装
  "desktop": false,     // 桌面环境 (xfce4)          → 首发不含
  "containers": false,  // docker/podman/lxc 守护进程
  "desktop_profile": false,
  "downloads_share": true,  // 9p/vsock Downloads 共享
  "usb_passthrough_host": true  // 与镜像无关，始终由应用决定（列出仅为完整性）
}
```

- 应用 UI 以 `capabilities` 作为**唯一真值来源**；缺失该字段的旧镜像按
  `{ssh:true, x11:true, containers:true}` 兼容解释（对齐上游全量镜像的预期）。
- 端口转发页：不可用能力对应的隐式转发不展示、不启用。

### 4.7 镜像路线图（系统可更换 = 后续只加镜像）

| 阶段 | 文件名 | identity | 内容 | 说明 |
|---|---|---|---|---|
| **首发** | `debian.img` | `debian:trixie` | 最小化 + Xvnc/pulseaudio（本节） | 契约全通、`Ready!` |
| 二期 | `debian-desktop.img` | `debian:trixie` | + xfce4 桌面（Xvnc 已有） | 同 identity → **免重置切换** |
| 二期 | `debian-containers.img` | `debian:trixie` | + docker/podman/lxc | 同上 |
| 三期 | `debian-full.img` | `debian:trixie` | 桌面 + 容器 = 等价当前全量构建 | 迁移旧用户 |
| 三期+ | `ubuntu.img` / `alpine.img` / … | `ubuntu:24.04` / `alpine:3.24` | 其他发行版 | 新 identity → 切换时按 §5.2 重置 |

规则：

1. **同 identity 家族**（`debian:*` 之间）切换**永不触发重置**，数据盘保留。
2. **跨发行版**切换触发 `RESET_REQUIRED`（一次确认）。
3. 每个镜像独立发布为 Release 资产 + 更新 `catalog.json`，**应用无需发版**。
4. `identity` 只写发行版+版本（如 `debian:trixie`），不写 variant，
   使 minimal/desktop/containers/full 之间可以自由互切。

### 4.8 账户与 SSH 登录规范（所有镜像强制）

**每个系统镜像（含后续所有发行版）在构建期固化以下账户，作为镜像规范的一部分，
在 `catalog.json` 与 manifest 中声明：**

| 账户 | 密码 | 组 / 权限 | 用途 |
|---|---|---|---|
| `root` | `123` | — | 管理、调试、回归脚本（与上游 `Podroid-Debian` 一致） |
| `ltbkq` | `123` | `sudo` 组，`NOPASSWD:ALL` | **日常账户**，推荐登录入口 |

要求：

1. **SSH 使用系统默认端口 22**（guest 内 dropbear 监听 `:22`，
   **不改端口**，与上游一致）。`9922` 只是 adb 在宿主侧的转发端口，
   与 guest 内端口无关。两个账户都必须能通过 SSH 登录：
   ```sh
   adb forward tcp:9922 tcp:9922   # PC:9922 -> 手机:9922（QEMU hostfwd）-> guest:22
   ssh root@localhost  -p 9922   # 密码 123
   ssh ltbkq@localhost -p 9922   # 密码 123
   ```

   端口约定（guest 内，均为系统默认，不自定义）：

   | 服务 | guest 端口 | 说明 |
   |---|---|---|
   | **SSH（dropbear）** | **22** | 系统默认端口，本规范强制 |
   | Xvnc | 5900 | 系统默认；**所有 VMDroid 镜像必装**（§4.5 契约单元，与是否含桌面无关） |
   | pulseaudio TCP | 4713 | 上游约定 |
   | host bridge | hvc2 / vsock 9101 | 非 TCP |

   宿主侧转发（仅供 adb/本机访问，均只绑回环）：
   `9922 → 22`、`5900 → 5900`、`4713 → 4713`。
2. dropbear 配置不得限制 `root` 登录（`PermitRootLogin` 等价语义 = 允许），
   并允许两个账户的**密码认证**（无密钥也能进）。
3. `ltbkq` 的家目录 `/home/ltbkq` 必须在首次启动可用（`skel` 拷贝完成），
   默认 shell 为发行版的 login shell（Debian: `/bin/bash`）。
4. `ltbkq` 免密 sudo，使其能执行 `apt install` / `systemctl` 等日常操作。
5. **构建期实现**（`Podroid-Debian` 侧，chroot 内）：
   ```
   useradd -m -s /bin/bash -G sudo ltbkq
   echo 'root:123'   | chpasswd
   echo 'ltbkq:123'  | chpasswd
   echo 'ltbkq ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ltbkq
   chmod 0440 /etc/sudoers.d/ltbkq && visudo -c        # chroot 内自检（R1: B-R1-23）
   # dropbear（/etc/default/dropbear）：
   DROPBEAR_EXTRA_ARGS=""   # 不加 -w/-g/-s/-B：dropbear 默认即允许 root 与密码登录
                            #   -w=禁root -g=禁root密码 -s=禁密码 -B=允许空密码(禁用)
   #   ★ host 私钥不得烘焙进镜像（R1: B-R1-2 / R2: B-R2-4 定案）：
   rm -f /etc/dropbear/dropbear_*_host_key*
   #   定案机制 = podroid-bootstrap 内 dropbearkey（与上游 Alpine 行为一致），
   #   在 `podroid-bootstrap` 脚本中新增：缺失则
   #     dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key
   #     dropbearkey -t rsa     -f /etc/dropbear/dropbear_rsa_host_key
   #   （/etc 走 overlay → copy-up 到 persist；不采用 dropbear -R 分支）
   ```
   写入点在现有 `build/rootfs-finalize.sh` 的 dropbear 段内追加 `rm -f`；
   `dropbearkey` 逻辑**新增到** `rootfs/usr/local/lib/podroid/podroid-bootstrap`
   （该脚本目前既不删也不生成 key —— 属两处需实现的 guest 改动，M2 交付，R2: B-R2-4）。
6. manifest 声明（应用据此在 Home/镜像页显示 SSH 登录提示，见 §10.1(2)）：
   ```jsonc
   "accounts": {
     "ssh": [
       {"user": "root",  "password": "123", "sudo": false},
       {"user": "ltbkq", "password": "123", "sudo": true}
     ],
     "default_user": "ltbkq",
     "ssh_port": 22                    // ★ 缺省 22；mkimg/应用遇 ≠22 一律拒绝
   }
   ```
7. **与应用的关系**：账户属于**镜像内容**，应用不创建、不改写账户；
   应用只在 UI 中展示 manifest 声明的登录信息（Home/镜像页显示
   `ssh -p 9922 ltbkq@localhost`）。
8. **兼容性**：manifest 缺 `accounts` 字段 → 按 `{root only, port 22}` 解释，UI 只提示 root。
9. **host key 生命周期**：镜像内**不得**存在 `/etc/dropbear/dropbear_*_host_key*` 私钥；
   由首次启动生成（见第 5 条），使公开分发的 `.img` 不泄露任何私钥。

> 注意：`ltbkq` 作为账户名是用户指定的固定值，写入构建脚本与规范；
> 后续发行版（ubuntu.img / alpine.img …）**必须同样满足**本节第 1–4 条，
> 否则镜像不得上架 catalog。

---

## 5. 镜像生命周期

### 5.1 状态机

```
                    ┌──────────────────────────────────────────┐
                    │                                          │
   (无) ──import──▶ │ IMPORTING ──校验通过──▶ INSTALLED ──激活──▶ ACTIVE │
        ──download─▶│ DOWNLOADING(%)/RESUMABLE   │  ▲          │      │
                    │      │                     │  └──切换────┘      │
                    │      └──校验失败──▶ CORRUPT ┴─重新下载/删除      │
                    │                          │                      │
                    │      identity 与当前不同 ─┴──▶ RESET_REQUIRED    │
                    │                                   │ 用户确认     │
                    │                                   ▼              │
                    │                              重建 storage.img    │
                    │                                   │              │
                    │                                   └──▶ ACTIVE ◀──┘
                    └──────────────────────────────────────────┘
```

| 状态 | 含义 | UI 表现 |
|---|---|---|
| `ABSENT` | 未安装任何镜像 | Home 显示空态块、**启动按钮隐藏**；进入引导页（下载/导入） |
| `DOWNLOADING` | `.part` 下载中，支持续传 | 进度条、速度、剩余时间、暂停/继续 |
| `IMPORTING` | SAF 选择的文件流式拷贝进 `images/` | 进度条（按已拷贝字节） |
| `VERIFYING` | 流式 sha256 + manifest 校验 | 校验动画（与下载/导入同一遍读取完成，见 §6.3） |
| `INSTALLED` | 校验通过、记录写入 `.meta.json`，未激活 | 卡片"安装"按钮 |
| `ACTIVE` | 当前启动将使用的镜像 | 高亮"使用中" |
| `RESET_REQUIRED` | 目标镜像 `identity` ≠ 当前 `identity` | 阻断式对话框：需清空 `storage.img`（或高级：保留数据，风险自负） |
| `CORRUPT` | 校验失败 / 文件被截断 | 红色警示 + 重新下载 / 删除 |
| `STORAGE_FULL` | 空间不足 | 预检失败，下载前拦截 |

> **状态图与状态表的对应**：`VERIFYING` 是 `IMPORTING`/`DOWNLOADING` 的**内嵌阶段**
> （同一遍读取完成哈希与 footer 校验），不单列；`RESUMABLE` 属 `DOWNLOADING`；
> `STORAGE_FULL` 是**预检失败**而非持久状态。

### 5.2 identity（换镜像是否需要重置数据盘的判据）

这是本设计的关键语义，比上游"永远要求手动 Reset VM"更精确：

| 场景 | 判据 | 结果 |
|---|---|---|
| `system_version 33 → 34`（同发行版升级） | `debian:trixie` == `debian:trixie` | **无需重置**，overlay copy-up 平滑升级 |
| Alpine → Debian | `alpine:3.24` ≠ `debian:trixie` | **必须重置**（否则 Alpine copy-up 文件遮蔽 Debian，即 PLAN.md 中的坑） |
| 同一 `.img` 重复导入 | `rootfs_sha256` 相同 | 无需重置 |
| **内容相同、封装不同**（如官方 `.img` 覆盖手工封装的同源文件） | `new.rootfs_sha256 == active.rootfs_sha256` | **无需重置**（**内容优先于标识**） |
| init 体系变化（如 `debian:trixie` systemd → openrc） | identity 相同但 `distro.init` 变 | **必须重置**（硬判据，见下） |
| `contract.version` 变化 | 契约版本不同 | **必须重置**（硬判据） |

规则（按顺序判定；**`decision` 取值 = 本表产出，`image.log` 直接引用，勿自造**）：

| `decision` | 条件（按顺序取第一个匹配） | 是否 `RESET_REQUIRED` |
|---|---|---|
| `same` | `new.rootfs_sha256 == active.rootfs_sha256` | **否**（内容优先，永不重置） |
| `identity` | `new.identity != active.identity` | 是 |
| `contract` | `contract.version` 变化（硬判据） | 是 |
| `init` | `distro.init` 变化（硬判据，如 systemd → openrc） | 是 |
| `upgrade` | 以上全同但 `rootfs_sha256` 不同（**最常见：同发行版升级**，R3: A-R3-17） | **否** |

1. **内容优先**：`new.rootfs_sha256 == active.rootfs_sha256` → 视为同一系统，**永不**重置。
2. `identity = manifest.image.identity`（例如 `debian:trixie`，跨版本稳定）；
   **N4 后 identity 恒来自 manifest**（只收 `.img`），不再有 `sha256:` 形式。
3. **硬判据**：`contract.version` 或 `distro.init` 与当前不同 → 即使 identity 相同也进入 `RESET_REQUIRED`。
4. 激活时判定为需重置 → 进入 `RESET_REQUIRED`，用户确认后由应用**重建 `storage.img`**（§4.3 整文件清零），再激活。
5. 防御纵深（可选，M6）：`podroid.image_identity=` 加入内核 cmdline；guest 侧比对逻辑
   **由 Podroid-Debian 构建侧新增 `/etc/podroid/identity` + `podroid-migrate` 比对**
   （属镜像内容而非契约改动，故不违反 G4），VMDroid 只负责下发 cmdline。

### 5.3 激活与回滚

- `images/active.json` 记录 `{image_id, identity, rootfs_sha256, activated_at}`
  （字段名与 §2.3 一致；**唯一写者 = `SystemImageRepo`**，R2: A-R2-6/D-R2-7）。
- 允许保留多个已安装镜像（建议上限 2，超出时提示删除最旧的非激活镜像）。
- 回滚 = 激活另一张已安装镜像（同 identity 无需重置）。
- v1 不做 A/B 自动回滚（启动失败自动切回），列入 §15 开放问题。

### 5.4 导入格式白名单（N4：只支持 `.img`）

| 输入 | 识别方式 | 处理 |
|---|---|---|
| VMDroid `.img` | 尾部 footer `magic == "VMDIMG01"` | 完整 manifest 流程 |
| 裸 `.squashfs`（`hsqs` @0x0，无 footer） | 魔数正确但无 footer | **拒绝**：提示"非 VMDroid 系统镜像，请用 `mkimg.sh` 封装成 `.img`" |
| `.img` 但 footer 损坏 / `format_version` 过新 | footer 解析失败 | **拒绝**（`CORRUPT` 或"请升级应用"） |
| `arch != arm64`、`capabilities.ssh == false` | manifest 校验 | **拒绝**（违反 §4.8 / §4.6） |
| `.tar*` / `.zip` / 其他项目镜像 / 扩展名伪造 | 魔数不符 | **拒绝** |

规则：

- 文件选择器过滤 `application/octet-stream` + 扩展名 `img`（**不以扩展名为准，以 footer 魔数为准**）。
- 拒绝时 UI 给出**可执行的下一步**（封装命令 / 重新选择 / 升级应用），不只报错。
- 现有 `Podroid-Debian/out/debian-rootfs.squashfs` **不能直接导入**，须先
  `tools/mkimg.sh --manifest … --kernel … --initrd … -o debian.img`
  （秒级追加，完整接口见 IMAGE-FORMAT §6 / §11.2）。

---

## 6. 分发：目录、下载、导入

### 6.1 镜像目录（catalog）

托管在 GitHub Releases（默认订阅 `Podroid-Debian` 的 release），应用内可改订阅 URL。

```jsonc
// GET https://github.com/ltbkq/Podroid-Debian/releases/latest/download/catalog.json
{
  "schema": 1,
  "generated_at": "2026-10-07T12:00:00Z",
  "images": [
    {
      "image_id": "debian-minimal-arm64",
      "display_name": "Debian 13 (trixie) · 最小化",
      "identity": "debian:trixie",
      "variant": "minimal",
      "version": "2026.10.0-r1",
      "system_version": 34,
      "arch": "arm64",
      "channel": "stable",
      "url": "https://github.com/ltbkq/Podroid-Debian/releases/download/v34/debian.img",
      "size": 148951040,
      "sha256": "…64 hex…",
      "app_min_version_code": 1,
      "notes": "最小化：systemd + dropbear(root/123, ltbkq/123) + Xvnc/pulseaudio；无容器栈"
    }
  ]
}
```

> `image_id` 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`（**R1: B-R1-16**，防止 QEMU
> `-drive` 选项注入：路径中出现 `,` 会截断选项）。**导入/下载落盘路径一律由
> 校验通过的 `manifest.image.id` 决定，不使用源文件名**（R2: B-R2-5）。
> `size` 约束（R2: A-R2-2/B-R2-3）：`manifest_offset` 为 1 MiB 边界；
> `footer_offset = align4k(manifest_offset + manifest_size)`（**footer 仅 4 KiB 对齐**，
> 永远不是 1 MiB 边界）；`file_size = footer_offset + 4096`。
> 上例为合法可构造值，实际以 `mkimg` 实算为准（JSON 示例**不得**使用数字下划线，R2: A-R2-15）。
> `app_min_version_code` 与 manifest 内 `app.min_version_code` **同义**（catalog 扁平 / manifest 嵌套）。

- **签名（决策 2026-10-08，R2: RM-21 关闭）**：**ECDSA P-256 + SHA-256**
  （`SHA256withECDSA`），Android 系统 `Signature` **API 11+ 即支持，零依赖**
  （Ed25519 需 API 33 > minSdk 26，故不采用；R2 事实核查结论）。
  - **公钥内置 APK**（`BuildConfig.CATALOG_PUBKEY` 或 `res/raw/catalog_pub.pem`），
    **绝不放进被签内容**（原设计把 `ed25519_key` 写进 catalog 本身 = 自签失效）。
  - **签名对象**：`catalog.json` 的**原始字节**（不做 JSON 规范化重排 —— 规范化
    是签名兼容性的经典坑，签什么就验什么）。
  - **签名文件**：`catalog.json.sig`，detached，`base64(DER(ECDSA-Signature))`，
    与 `catalog.json` 同 Release 发布（minisign 风格，工具：`openssl dgst -sha256 -sign`）。
  - **校验失败** → 拒绝加载目录，UI 报"镜像目录签名无效"（不降级为裸 HTTPS，
    否则签名形同虚设）；**公钥轮换** = 发新 APK（附带更新内置公钥），v1 不做
    多密钥列表，列入 Q6。
  - **实现细节（R3: B-R3-21）**：
    ```sh
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out catalog.key   # CI secret
    openssl dgst -sha256 -sign catalog.key catalog.json | openssl base64 -A > catalog.json.sig
    ```
    `.sig` URL = 订阅 URL + `.sig`；Android 侧用 `Base64.getDecoder()`（**非 MIME**，
    换行会抛错）解码后 `Signature("SHA256withECDSA").verify()`；测试向量 =
    固定 key+message+sig 入库（正例互操作，§13.1）。
  - **HTTPS 仍是第一道防线**（TLS 证书链）；签名校验是防"Release 资产被替换/
    镜像站篡改"的第二道独立防线（防的是**内容**而非传输）。

### 6.2 下载（断点续传）

```
ImageDownloader
  1. 预检：可用空间 ≥ size + 余量（StorageManager），否则 STORAGE_FULL
  2. HEAD/Range: bytes=<downloaded>-   → 写 images/<id>.img.part
     - 服务端 206 → 续传；416/无 Range 支持 → 从 0 重来
     - ETag/Last-Changed 变化 → 丢弃 .part 重下
  3. 每块同时喂 sha256（无需第二遍读文件）
  3. 完成后 sha256 == catalog.sha256 **且 footer/manifest 解析校验通过**
     （§7.4 清单 3–9：magic/format_version/flags/image.id/arch/ssh/ssh_port/app 版本；
     **N4：footer 必需，缺失即拒**，R3: C-R3-2）→ 否：删除 .part，记 `verify_fail`（§16.5），可重试
  4. fsync + rename(.part → .img) + 写 .meta.json → INSTALLED
  5. 断电安全：.part 永不冒充成品（沿用上游"原子 rename"思想，见
     PodroidApplication.copyAssetAtomically, :219-236）
```

- 进度上报到通知栏 + UI；进程被杀后**下次进入应用自动续传**。
- 移动网络默认提示（可设置"仅 Wi-Fi 下载"）。

### 6.3 手动导入（SAF）

```
用户: 设置 → 系统镜像 → 从文件导入 → ACTION_OPEN_DOCUMENT(*/*)
  1. content:// URI 读取 size（未知则边读边扩展）
  2. 流式拷贝到 images/.import-<uuid>.img.part（**随机临时名**，R3: A-R3-14：
     此阶段尚读不到 manifest，不能用未校验的源文件名/`<name>`），
     同一遍计算 sha256 / 解析 footer
  3. 校验 footer magic + footer.manifest_sha256（**footer 必需，缺失一律拒绝**，
     N4，R3: C-R3-2 去掉"有 footer 时"条件残留）→ 解析校验（§7.4 清单 3–9）
  4. 空间预检（拷贝前先查 size，减少半途失败）
  5. 校验通过 → rename 为 images/<manifest.image.id>.img.part → 写 .meta.json
     → 原子 rename → INSTALLED（**落盘名恒由校验后的 image.id 决定**，§6.1）
```

- **不使用 `content://` 直挂**：QEMU/crosvm 需要真实可 seek 的文件路径，
  SAF URI 不可保证（且跨进程 FD 生命周期不可控）→ **导入即拷贝**到应用私有目录。
- 已在应用可读共享目录（如 `Download/`）的文件，v1 也走拷贝（一致性优先）；
  "直接引用外部路径"作为 §15 开放问题（需验证 scoped storage 下 QEMU 打开 `/storage/emulated/0/...` 的能力）。

### 6.4 校验记录（避免每次启动重算 300 MB）

`.meta.json = { sha256, size, mtime, verified_at, format_version }`

启动前置条件（`BootGuard`）：

- `.img` 存在且 `size`/`mtime` 与 `.meta.json` 一致 → 信任，直接启动（不重算 sha256）。
- 不一致 → 重新校验 → 失败则 `CORRUPT`，阻止启动。
- 设置页提供"立即校验"手动按钮（全量 sha256）。

---

## 7. 引擎改造点（具体到文件）

改动面刻意保持极小：**引擎只把"硬编码文件名"换成"镜像仓库查询"**。

### 7.1 QemuEngine（上游 `engine/QemuEngine.kt`）

```kotlin
// 现状 (QemuEngine.kt:575-582)
val rootfsImg = File(context.filesDir, "alpine-rootfs.squashfs")
if (rootfsImg.exists()) { ... args += "-drive" ... readonly=on ... }

// VMDroid
val rootfsImg = systemImageRepo.activeRootfsPath()   // null = 无镜像
    ?: return BootGuard.block("NO_SYSTEM_IMAGE")     // ★ 不再静默省略 drive2
args += "-drive"; args += "file=${rootfsImg},if=none,id=drive2,format=raw,readonly=on,..."
```

| 位置 | 改动 |
|---|---|
| `QemuEngine.kt:575` | 硬编码文件名 → `systemImageRepo.activeRootfsPath()` |
| `QemuEngine.kt:576` | `if (exists)` 静默跳过 → **无镜像直接 fail fast**（现状会启动一台 `init-podroid` 挂载失败的死机） |
| `QemuEngine.kt:558-573` | `storage.img` 逻辑 **不变**（恢复出厂=清零在镜像管理器里做，不进引擎） |
| `QemuEngine.kt:532-533, 552-556` | 内核/initrd **不变**（仍来自 APK 资产） |
| `QemuEngine.kt:537-547` | cmdline 不变（`console=ttyAMA0`、`podroid.*`）；可选追加 `podroid.image_identity=`（§5.2） |
| `QemuEngine.kt:585-621` | 9p 下载共享、SLIRP 端口转发 **不变** |

### 7.2 AvfEngine（上游 `engine/avf/AvfEngine.kt`）

| 位置 | 改动 |
|---|---|
| `AvfEngine.kt:1027-1028` | `File(filesDir,"alpine-rootfs.squashfs")` + `require(exists)` → `systemImageRepo.activeRootfsPath()`；错误转成 UI 可读状态而非 `IllegalArgumentException` |
| `AvfEngine.kt:1084-1085` | `addDisk(storage, writable=true)` / `addDisk(squashfs, writable=false)` 的路径来源改为仓库 |
| `AvfEngine.kt:929-965` | `ensureStorageImage()` **不变**（sparse 创建、只增不减、`resize2fs` 由 guest 做） |

### 7.3 PodroidApplication（上游 `:103-108`）

```kotlin
val tasks = listOf(
    { copyAssetDir("qemu", filesDir, forceCopy) },
    { copyAssetIfNeeded("vmlinuz-virt", File(filesDir, "vmlinuz-virt"), forceCopy) },
    { copyAssetIfNeeded("initrd.img",   File(filesDir, "initrd.img"),   forceCopy) },
    // ★ 删除: { copyAssetIfNeeded("alpine-rootfs.squashfs", ...) }
)
```

- `.assets_stamp` 机制、原子复制、线程池 **原样保留**。
- 启动前 `awaitAssetsReady()`（现在只等固件）+ 新增 `awaitImagesReady()`
  （等 `.meta.json` 校验判定完成），两者都完成后才允许 `start()`。

### 7.4 无镜像 / 异常态（BootGuard —— **校验清单权威定义**）

> **本表是 BootGuard 的唯一权威清单**（R3: A-R3-3 合并三处散落定义）。
> §9.1 时序图与 IMAGE-FORMAT §5 只是本表的**引用与解析实现**，发现不一致以本表为准。
> **调用点（R3: A-R3-2 统一）**：主判定在 `VmdroidService.start()` 内（启动前拦截，
> §9.1/§16.2）；引擎内 `BootGuard.block(...)`（§7.1）是**兜底 fail-fast**，两处共用本清单。

| # | 条件 | `reason` 枚举 | 行为 |
|---|---|---|---|
| 1 | 未安装镜像 | `NO_SYSTEM_IMAGE` | 引导页：**下载推荐镜像** / **从文件导入**；Home 显示 §8.2 空态块，**启动按钮隐藏** |
| 2 | `.meta.json` 缺失/损坏，或 size/mtime 不一致且全量重算失败 | `CORRUPT` | 阻止启动；UI：重新下载 / 删除重导 |
| 3 | footer magic 不符 / 裸 squashfs / 非本格式 | `NOT_AN_IMAGE` | 拒绝导入（§5.4 N4），给出 `mkimg.sh` 封装指引 |
| 4 | `format_version > 1` 或 `footer_size != 4096` 或未知 flags | `FORMAT_UNSUPPORTED` | 拒绝，提示升级应用 |
| 5 | `app.min_version_code > 当前 versionCode`（catalog 扁平字段 `app_min_version_code` 同义） | `APP_TOO_OLD` | 拒绝激活，提示升级应用 |
| 6 | `arch != arm64` | `ARCH_MISMATCH` | 拒绝激活 |
| 7 | `image.id` 不匹配 `^[a-z0-9][a-z0-9._-]{0,63}$` | `IMAGE_ID_INVALID` | 拒绝（防 `-drive` 选项注入） |
| 8 | `capabilities.ssh == false` | `SSH_CAPABILITY_MISSING` | 拒绝（§4.6 SSH 硬性要求） |
| 9 | `accounts.ssh_port != 22` | `SSH_PORT_INVALID` | 拒绝（§4.8 端口规范） |
| 10 | 激活时 identity/contract/init 判据不通过 | `RESET_REQUIRED` | §8.5 对话框，确认后重建 `storage.img` 再激活 |
| 11 | `storage.img` 缺失 | （非拒绝） | 由 `ensureStorageImage()` 正常创建（现状行为） |

每次拒绝必须写 `image.log` 的 `bootguard_reject`，`reason` 取本表枚举
（§16.5 引用本表，勿另造 `NO_IMAGE` 之类别名）。

---

## 8. UI / UX

### 8.0 UI 设计原则（美观性与合理性，需求 N2）

> 本节是**评审基准**：任何新增/修改页面都要逐条过一遍，R2 起由评审者对照验收。

**① 栅格与间距（8dp 基准，禁止散值）**

| 项 | 规定 |
|---|---|
| 设计系统 | **Material 3**（上游已用 Compose + M3，不引入第二套） |
| 间距序列 | 仅 `4 / 8 / 12 / 16 / 24 / 32 dp`（**12 专用于卡片间距**），禁止 13dp、17dp 等散值 |
| 页面水平内边距 | 16dp；卡片间距 12dp；区块标题↔内容 8dp |
| 触控目标 | ≥ 48×48dp（图标按钮用 `IconButton` 标准尺寸） |
| 列表行高 | 单行 56dp / 双行 72dp / 含进度条 88dp（统一，不逐页调） |

**② 信息层级（一屏一焦点）**

- 每屏只有 **1 个主操作**（`FilledButton`），其余降级为 `TextButton` / `IconButton`。
- 关键状态用**颜色 + 图标 + 文字三重编码**，不依赖单一颜色（可访问性、弱光可读）。
- 卡片内信息顺序恒定：**名称 → 版本/大小 → 状态 → 操作**，跨页面一致。

**③ 深浅色与字阶**

- light / dark **必须同时提供**；颜色取 M3 tonal palette，**禁止硬编码色值**。
- 语义色固定：`成功=primary`、`警告=tertiary`、`错误=error`、`进行中=secondary`。
- 字阶只用 M3 `titleLarge / titleMedium / bodyLarge / bodyMedium / labelLarge`，
  **不自定义字号**；中文正文 ≥ 14sp，行高 ≥ 1.4。

**④ 状态完备（每个控件四态）**

`正常 / 禁用 / 加载中 / 错误` **四态都要设计**，错误态必须给出**可执行的下一步**文案；
禁止裸 "Error"、禁止空白占位。

**⑤ 动效与破坏性操作**

- 页面切换 M3 标准 fade/slide，≤ 300ms；进度/校验用 `animateFloatAsState`。
- 破坏性操作（移除镜像、重置数据）**二次确认**；主按钮不放在误触区（右下/中下）。

**⑥ 中文排版**

- 中英文与数字间留半角空格；不混用中英文标点；长文件名中间截断 + 省略号。
- 全部文案走 `stringResource` + `values-zh`，**禁止硬编码**（沿用上游 i18n 规范）。

### 8.1 页面信息架构

```
setup ──▶ home ──┬─▶ terminal
                 ├─▶ x11
                 ├─▶ images     ★新增：系统镜像管理
                 ├─▶ settings
                 └─▶ ports / backup / about
```

新增两处：**Home 顶部的启动镜像选择控件**（§8.2）+ 独立"系统镜像"页（§8.3）。

### 8.2 ★ 启动镜像选择控件（需求 N3）

**位置**：Home 页顶部、启动按钮**上方**（先选系统、再启动，符合心智顺序）。
**形态**：Material 3 `ExposedDropdownMenuBox`（下拉选择器）——
高频操作、需"一眼看出当前跑哪个系统"，故不进二级页面、不用 Tab。

```
┌─ Home ───────────────────────────────────────┐
│  启动镜像                                     │
│  ┌────────────────────────────────────────┐  │
│  │ Debian 13 (trixie) · 最小化         ⌄ │  │  ← 选中态：名称 · 变体
│  │ debian-minimal-arm64 · 148 MB          │  │     + image_id + 体积
│  └────────────────────────────────────────┘  │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │            ▶  启动虚拟机                │  │  ← 唯一 FilledButton
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘

展开（下拉菜单项）：                空态（无已安装镜像）：
┌────────────────────────────┐   ┌────────────────────────────┐
│ ● Debian 13 · 最小化    ✓ │   │ ⚠ 尚未安装系统镜像          │
│   148 MB · debian:trixie  │   │                            │
│ ○ Debian 13 · 桌面版       │   │ [下载 debian.img]          │
│   312 MB · debian:trixie  │   │ [从文件导入 .img]          │
│ ─────────────────────────  │   └────────────────────────────┘
│ + 从文件导入 .img…         │
│ + 在线下载更多镜像…        │
└────────────────────────────┘
```

**行为规则**

| 场景 | 行为 |
|---|---|
| 虚拟机运行中 | **禁用**并说明"请先停止虚拟机"（v1 不做热切换） |
| 选中未激活镜像 · 判据相同 | 直接改写 `active.json`，提示"下次启动生效" |
| 选中未激活镜像 · 需重置 | 先弹 §8.5 确认框，确认后才切换 |
| 仅 1 个镜像 | **仍显示**选择器（布局稳定），下拉项含"导入/下载"入口 |
| 校验中 | 20dp `CircularProgressIndicator` + 文字"校验中"，控件禁用 |
| `CORRUPT` 项 | **颜色 + 图标 + 文字**三重编码：标红 + ⚠ + 文字"已损坏"；选中后主按钮变为"重新校验 / 重新下载" |
| 完全无镜像 | 整块替换为空态双入口（`[下载 debian.img（Filled）]`、`[从文件导入 .img（Text）]`）；**启动按钮隐藏**（不显示必然失败的按钮） |
| 加载失败（目录/列表异常） | 选择器保留上次值 + 顶部 inline error，不塌陷布局 |

**为什么放 Home**：换系统后用户要立即看到"现在跑的是哪个"；Home 是启动动作发生地，
选择与启动同视野（就近原则）；放设置页会造成状态不可见。

### 8.3 系统镜像管理页（Images）

```
┌─ 系统镜像 ────────────────────────────────┐
│ Debian 13 (trixie) · 最小化                │  ← ①名称
│ debian-minimal-arm64 · 148 MB              │  ← ②image_id · 体积
│ debian:trixie · system_version 34          │  ← ②版本信息
│ ● 使用中                                   │  ← ③状态（颜色+图标+文字三重编码）
│ [立即校验] [恢复出厂] [移除]                  │  ← ④操作（无 FilledButton：卡片内均 TextButton）
│                                            │
│ Debian 13 (trixie) · 桌面版                 │
│ debian-desktop-arm64 · 312 MB              │
│ ○ 已安装                                   │
│ [设为启动镜像（Filled）] [校验] [移除]       │  ← 本屏唯一主操作
│                                            │
│ ── 在线目录 · Podroid-Debian ──            │
│ Debian 13 (trixie) · 最小化    v34          │
│ 148 MB · sha256 ✓              [下载]      │
│ ▓▓▓▓▓▓▓░░░ 68% · 4.2 MB/s · 剩 12s        │
│                                            │
│ [从文件导入 .img…]    [刷新] [订阅设置]      │  ← 均 TextButton / 图标按钮
└────────────────────────────────────────────┘
```

**排版规则**：卡片 = 已安装镜像（可操作），列表行 = 在线目录（只读 + 下载）；
两类用**分区标题 + 分隔线**区分，不混排（避免"哪个已装哪个没装"歧义）。
**卡片信息顺序恒为 名称 → 版本/体积 → 状态 → 操作**（§8.0②），且**每屏只有一个
`FilledButton`**（上例 = "设为启动镜像"；使用中卡片无主按钮）。

**四态设计（§8.0④）**

| 控件 | 正常 | 禁用 | 加载中 | 错误 |
|---|---|---|---|---|
| `[下载]` | 可点 | 非 Wi-Fi 且设置要求时（文案"仅 Wi-Fi"） | 行内进度条 + %/速度/剩余 | "下载失败，点击重试"（可执行文案） |
| `[立即校验]` | 可点 | 镜像 `CORRUPT` 时 | 20dp 进度圈 + "校验中" | "校验失败，建议重新下载" |
| `[从文件导入…]` | 可点 | 无（始终可用） | 导入进度条 | "非 .img 格式，请用 mkimg.sh 封装" |
| `[刷新]`（离线） | 可点 | 断网时置灰 + "离线" | 顶栏小进度 | inline error，**列表不塌陷** |

### 8.4 首次启动（无镜像）

```
启动 App → BootGuard: ABSENT
  → Setup 第 2 步（复用上游 setup wizard 逻辑）
      ① 下载推荐镜像（默认选中，显示体积/网络提示）
      ② 从文件导入（**仅 `.img`**；其他格式提示用 `mkimg.sh` 封装）
      ③ 稍后再说（Home 可浏览，显示 §8.2 空态块，**启动按钮隐藏**）
  → 完成后进入正常 Home
```

### 8.5 激活冲突对话框（identity 变更）

```
┌─ 需要重置虚拟机数据 ──────────────────────────┐
│ 目标镜像与当前系统不同（alpine:3.24 →          │
│ debian:trixie）。为保证可启动，需要清空         │
│ 虚拟机数据盘 storage.img。                      │
│                                                │
│ 将删除：容器数据、apt/apk 已装包、/root 内容     │
│ 不会删除：已下载的系统镜像                        │
│                                                │
│ [重置并切换]   [取消]                           │
│  ▢ 高级：保留数据（可能导致系统无法启动）         │
└────────────────────────────────────────────────┘
```

### 8.6 其余页面

Home / Terminal / X11 / Settings / 端口转发 / 备份 **沿用上游**，
仅增加 §8.2 的启动镜像选择控件、§8.3 系统镜像入口与无镜像态提示；
所有改动须符合 §8.0 六条设计原则（R2 起作为评审验收基准）。

**新增：Settings → 诊断（§16.4）**

```
┌─ 诊断 ────────────────────────────────────┐
│ 最近启动                                    │
│ ● 10-08 14:22  12.0s  成功 (Ready)         │
│ ○ 10-08 13:51  90s    失败 @ Network found │  ← 失败条目置顶+error色
│ ○ 10-07 22:04  11.4s  成功                 │
│                                            │
│ 磁盘：boots 12 MB / image.log 1.2 MB       │
│ [导出诊断包]              [清空历史]        │  ← 单 FilledButton
└────────────────────────────────────────────┘
```

点击启动条目 → 展开 `meta.json` 关键字段 + `console.log` 末尾 4 KB。

---

## 9. 关键时序

### 9.1 首次启动下载

```
User → Home 启动镜像选择控件(§8.2) → 选中 debian-minimal-arm64
User → ImageDownloader: 下载 Debian
ImageCatalogRepo → HTTPS GET catalog.json + catalog.json.sig → ECDSA P-256 签名校验（无效即拒载）
                  → 结构/sha256 字段校验 → 展示列表
ImageDownloader:
   预检空间 → GET .part(0-) → [写盘 + sha256]n 块 → 完成
   → sha256 比对 → **footer/manifest 解析校验（§7.4 清单 3–9）** → fsync → rename → meta.json → INSTALLED
SystemImageRepo: 判据比对(§5.2 decision) → 激活 → ACTIVE（写 image.log）
User → Home: 启动虚拟机
BootGuard（VmdroidService.start() 内，§7.4 权威清单）→ PASS
VmdroidService → EngineHolder → QemuEngine.start()
   -kernel filesDir/vmlinuz-virt  -initrd filesDir/initrd.img
   -drive storage.img(vda,rw)     -drive images/debian-minimal-arm64.img(vdb,ro)
guest: init-podroid → overlay → systemd → markers → Ready!
BootStageDetector: "Ready!" → VmState.Running → 终端自动连接
   （旁路：VmdLog 打点写 boots/<boot_id>.meta.json，§16.4）
```

> 组件名与 §2.2 一致（R3: A-R3-2 修正）：`ImageCatalogRepo`/`ImageDownloader`/
> `SystemImageRepo`/`BootGuard`/`VmdLog`；**无 `ImageManager`/`CatalogRepo` 这两个类**。

### 9.2 应用升级（对比现状的收益）

```
现状: APK 升级 → lastUpdateTime 变 → 强制重拷 300 MB rootfs
VMDroid: APK 升级 → 只重拷 ~20 MB 固件；镜像 .img 不动 → 秒级完成
```

### 9.3 系统升级（同 identity）

```
目录出现新版 → 下载新 .img → 校验 → 激活(同 identity，无需重置)
→ 下次启动用新 lower，旧 upper copy-up 继续生效 → 版本号 system_version 更新
（旧镜像可保留作回滚）
```

---

## 10. 安全设计

| # | 措施 | 说明 |
|---|---|---|
| S1 | 全量 sha256 校验 | 下载与导入都必须校验；`.meta.json` 记录，启动前比对 size/mtime |
| S2 | TLS + 目录签名 | HTTPS（传输）+ **`catalog.json.sig` = ECDSA P-256/SHA-256 detached 签名，公钥内置 APK**（内容），签名无效即拒绝加载目录（§6.1） |
| S3 | 原子落盘 | `.part` → fsync → rename，断电不会留下"看起来完整"的镜像 |
| S4 | 应用私有目录 | `.img` 存于 `filesDir/images/`，受 Android FBE 保护，其他应用不可读 |
| S5 | 空间预检 | 下载/导入前检查，避免半成品占位 |
| S6 | 无镜像不启动 | 消除"启动一台挂载失败的死机"的现状缺陷（`QemuEngine.kt:576`） |
| S7 | SSH 转发默认仅回环 | 见 §10.1 |
| S8 | 镜像内容信任 | `.img` 内是完整 rootfs（root 权限在 guest 内），**只从可信目录/可信文件导入**；导入未知文件时 UI 明确警告 |

### 10.1 Guest 默认凭据（继承现状，按 §4.8 规范化）

现状（`Podroid-Debian`）：仅 `root / 123`，隐式转发 `9922 → guest:22`。
`docs/DELTAS.md` 已知缺口 #2 明确写着"安全性依赖于没有 LAN 转发"。

**端口规范**：SSH 端口 **永远是 guest 内的 22（系统默认）**，镜像与应用都
**不修改** guest 端口；`9922` 只是 adb/QEMU 在宿主侧的转发端口，
应用与文档统一表述为 `宿主 9922 → guest 22`。

**VMDroid 规范（§4.8，所有镜像强制）**：`root/123` 与 `ltbkq/123` 并存，
两者均可 SSH 登录；`ltbkq` 免密 sudo 作为日常账户。

VMDroid 应用侧配套（M7）：

1. QEMU 隐式 SSH 转发改为 `hostfwd=tcp:127.0.0.1:9922-:22`（仅回环）；
   设置里提供"允许局域网 SSH"开关（默认关）。
2. Home/镜像页常驻显示登录提示（数据来自 manifest `accounts`）：
   `ssh ltbkq@localhost -p 9922  (pw: 123)`。
3. **不动契约**：guest 侧 dropbear 行为、guest 端口 22 不变，只收紧 host 侧绑定。
4. **威胁模型（R1: B-R1-3，修正）**：Android **没有 per-app 网络命名空间**，
   回环端口对**同设备所有应用**开放 —— 仅回环绑定只能防**局域网/其他设备**，
   **不能防同机恶意应用**（它可直接 `ssh root@127.0.0.1 -p9922`）。
   补偿措施：① hostfwd 仅在 VM 运行期间存在；② "允许局域网 SSH" 默认关；
   ③ Settings 提供"修改 guest 密码"（调 `chpasswd`）并**强引导提示**；
   ④ 不强制首启改密（会破坏 boot-test 与用户预期）。

> **端口链路（三层 + 双后端，别写错）**：
> `PC:9922` —adb forward→ `手机:9922` → `guest:22`（dropbear）。
> 其中手机侧 `:9922` 的监听者：**QEMU = SLIRP `hostfwd`**；
> **AVF = 应用本地 TCP 监听 + vsock 桥**（`podroid-vsock-agent`，1:1 映射，
> 见 `forwards.conf`）。两种后端手机侧都有 `:9922`，**都没有** `:22`，
> 所以 `adb forward` 的目标端口必须是 **`tcp:9922`**（写成 `tcp:22` 会连到无人监听端口）。
>
> 改 `hostfwd` 绑定（0.0.0.0 → 127.0.0.1）不影响 adb forward（它连的是手机本地回环），
> 故 `Podroid-Debian/tools/boot-test.sh` 冒烟测试不受影响
> （该脚本需先参数化包名并自建 forward，见 §13.1 / R1: B-R1-8）。

---

## 11. 兼容性与迁移

### 11.1 从 Podroid 迁移

| 资产 | 可否自动迁移 | 说明 |
|---|---|---|
| `storage.img`（用户数据/容器） | ❌ 默认不可 | 应用私有目录跨包不可读；换包名 + 不同签名 → 必须卸载重装 |
| `alpine-rootfs.squashfs` | ❌ 不自动 | 须先 `mkimg.sh` 封装为 `.img` 再导入（N4：只收 `.img`） |
| 端口转发/设置 | ❌ | DataStore 私有 |
| 可行路径 | 手动 | ① 重新下载镜像 ② 用户从文件导入旧镜像 ③ 数据盘从零开始 |

> 迁移工具（若设备可 `adb`）：`adb shell run-as <pkg>` 仅 debug 包可用，
> release 包无法跨应用导出 `storage.img` → 文档明确"首次使用需重新准备系统镜像"。
> 若未来获得上游发布密钥或同签名升级路径，可改为原地升级（届时包名应保持 `com.excp.podroid`）。

### 11.2 与 Podroid-Debian 的分工

| 仓库 | 职责 | 产物 |
|---|---|---|
| `ltbkq/vmdroid`（本仓库） | VM 应用 + 镜像管理 | `vmdroid-<ver>.apk`（≈70 MB）、`VmdImageCodec`（解析/校验，**应用不写 `.img`**）、`tools/pc-run.sh`（用户入口，随 Release 分发） |
| `ltbkq/Podroid-Debian` | 构建 Guest rootfs、**固件**、打包 `.img` | `debian.img`、`catalog.json`、`vmlinuz-virt`/`initrd.img` Release 资产（§12.4）、`mkimg.sh`、`catalog.sh`、`pc-boot-smoke.sh`、首启 host key 逻辑 |
| `ExTV/Podroid` | 上游 VM 功能 + 内核构建脚本 | fork 源头；`build-all.sh kernel / initramfs / qemu` |

`Podroid-Debian` 侧需要新增（不改现有流水线）：

```sh
tools/mkimg.sh --rootfs out/debian-rootfs.squashfs \
               --manifest manifest.json \
               --kernel  out/vmlinuz-virt \    # ★ R-16 必需（完整接口见 IMAGE-FORMAT §6）
               --initrd  out/initrd.img \
               -o out/debian.img              # 发布名 debian.img（命名基线见 §2.3；v1 无 --seed）
# 生成目录 + detached 签名（CLI 定义，R2: RM-21 关闭；R3: A-R3-26）
tools/catalog.sh --release-dir out \
                 --base-url  https://github.com/ltbkq/Podroid-Debian/releases/download/<tag> \
                 --sign-key  keys/catalog_ecdsa_p256.pem \
                 -o catalog.json -o catalog.json.sig
#  签名：openssl dgst -sha256 -sign key catalog.json | openssl base64 -A > catalog.json.sig
#  私钥存 GitHub Actions secret（不进 VCS）；.sig 与 catalog.json 同 Release 发布（§6.1）
tools/boot-test.sh ${PKG:-io.github.ltbkq.vmdroid.debug}   # 冒烟（R1: B-R1-8 参数化）
```

> `--identity` / `--version` / `--system-version` 由 **manifest.json 提供**，
> 不再作为 CLI 参数（**R1: A-R1-7**，以 IMAGE-FORMAT §6 为唯一权威接口）。
> `mkimg` 只写入源文件 `[0, bytes_used)`：squashfs 实际文件按 4096 补齐，
> `bytes_used`(345,251,457) ≠ 文件大小(345,251,840)，尾部 383 B 补齐字节**丢弃**
> 再零填充至 1 MiB（**R1: B-R1-9**）。因此 `sha256sum <源文件> ≠ footer.rootfs_sha256` 属预期。
> 现有 `out/debian-rootfs.squashfs` 无需重新构建，`mkimg.sh` 只是**在尾部追加**，
> 秒级完成；`tools/graft.sh`（打进 APK 资产）**废除**（N4 + 软件系统分离后无用）。

### 11.3 Android / 后端矩阵

| 维度 | 要求 |
|---|---|
| minSdk 26 (Android 8) / targetSdk 36 | 与上游一致 |
| 架构 | arm64 only（上游 QEMU `aarch64-softmmu`、AVF 亦然） |
| QEMU/TCG 后端 | 默认，无需特殊权限 |
| AVF/pKVM 后端 | `pm grant MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE`；**需 Android 14+（API 34）且设备支持 AVF/pKVM**（`android.software.virtualization_framework`，Tensor/天玑9400/Exynos 2500 等）；不满足则仅 QEMU/TCG；**`.img` 直挂能力需真机验证**（P0） |
| 16KB page size | jniLibs 保持 `-Wl,-z,max-page-size=16384`（上游强制） |

### 11.4 `.img` 在 Linux PC 上启动（R-16 / G9）

**目标**：`debian.img` 单文件拷到任意 Linux PC，**不装 APK、不装 Android SDK**，
一条命令进入 guest。这是 `.img` 的一等验收项（R-16）。

**依赖**：`qemu-system-aarch64`（`apt install qemu-system-arm`，版本下限待 M0 实测）、
`python3`、`mkfs.ext4`（`e2fsprogs`）、`ssh`。

**归属（R2: C-R2-3/D-R2-2）**：`tools/pc-run.sh` 归 **vmdroid 仓库**（用户入口），
与 `.img` 一起**随 Podroid-Debian Release 分发**（Release 资产同时挂脚本，用户两条命令拿到全部）；
`tools/pc-boot-smoke.sh` 归 **Podroid-Debian CI**，是 `pc-run.sh --smoke --timeout <N>` 的同一实现
（提取与启动逻辑**单一来源**，不复制两份）。

**提供两种启动方式**（任选，等价）：

```sh
# 方式 A（推荐）：脚本 —— 校验 footer → 提取 kernel/initrd → 建 storage.img → 起 QEMU
tools/pc-run.sh debian.img

# 方式 B：手工（等价命令，用于文档自证）
#  1) vda 必须先格式化，否则 QEMU 建 0 字节文件 → init-podroid 挂载失败（R2: B-R2-16）
truncate -s 4G storage.img && mkfs.ext4 -F storage.img
#  2) 按 footer 偏移提取（★ 偏移随 seed 移除重编号：flags@120, kernel@124, initrd@172）
python3 - <<'EOF'
import struct, hashlib, sys, os
img = open("debian.img","rb"); img.seek(-4096, 2); ft = img.read(4096)
assert ft[0:8] == b"VMDIMG01" and ft[4088:4096] == b"VMDIMG01", "bad footer"
size, = struct.unpack_from("<Q", ft, 16)
assert size == os.path.getsize("debian.img"), "truncated"
flags, = struct.unpack_from("<I", ft, 120); assert flags & 0x3 == 0x3, "no kernel/initrd"
for off, sha, name in ((124,140,"vmlinuz"), (172,188,"initrd.img")):
    o, s = struct.unpack_from("<QQ", ft, off); h = ft[sha:sha+32]
    assert o and s and o + s <= size - 4096, f"{name} out of range"
    img.seek(o); data = img.read(s)
    assert hashlib.sha256(data).digest() == h, f"{name} sha256 mismatch"
    open(name, "wb").write(data)
EOF

#  3) 启动（★ 必须带 -serial 与 -display none，否则 Ready! 无处输出 / 无头机报错）
qemu-system-aarch64 \
  -M virt,gic-version=3 -cpu max -accel tcg,thread=multi \
  -display none -serial mon:stdio \
  -smp 4 -m 4096 \
  -kernel vmlinuz -initrd initrd.img \
  -append "console=ttyAMA0 mitigations=off androidip=10.0.2.15 podroid.x11.dpi=96" \
  -drive file=storage.img,if=none,id=drive1,format=raw,discard=unmap,detect-zeroes=unmap \
  -device virtio-blk-pci,drive=drive1 \
  -drive file=debian.img,if=none,id=drive2,format=raw,readonly=on \
  -device virtio-blk-pci,drive=drive2 \
  -netdev user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:9922-:22,hostfwd=tcp:127.0.0.1:5900-:5900,hostfwd=tcp:127.0.0.1:4713-:4713 \
  -device virtio-net-pci,netdev=net0
```

**要点**

| 项 | 说明 |
|---|---|
| 内核来源 | **`.img` 自带**（footer `kernel_*`）；与 APK 内置者同源同版本，产物流水线见 §12.4 |
| `vda` | 脚本首次 `truncate -s 4G + mkfs.ext4` 创建 `storage.img`（已存在则复用）；恢复出厂同样交给 `init-podroid` 自动 mkfs（§4.3） |
| 串口/显示 | `-display none -serial mon:stdio` 为**必选项**（R2: B-R2-7）：`ttyAMA0` 不接 chardev 则 `Ready!` 无输出，无头机缺显示后端会直接退出 |
| hvc0–2 | 上游 `-device virtio-serial-pci` + 三个 `virtconsole` 才有 `/dev/hvc0-2`。**PC 端默认补上**（脚本负责），否则 `podroid-getty@hvc0` 会无限重启空烧 vCPU（R2: B-R2-8）。控制台验收仍走 `ttyAMA0`（serial） |
| 启动契约 | 挂载与标记路径**相同**：`init-podroid` 挂 `vda`/`vdb` → overlay → `Ready!` |
| 架构 | x86_64 PC 走 **TCG**（慢但可用）；arm64 Linux 主机可加 `-accel kvm -cpu host` 提速 |
| 端口 | 只绑回环 `127.0.0.1`（9922/5900/4713 三者齐全，与 §4.8 一致）；`ssh -p 9922 ltbkq@localhost`（pw `123`） |
| 阈值 | **90s 内出现 `Ready!`**（与 `boot-test.sh BOOT_TIMEOUT=90` 对齐；原 60s 无实测依据，M0 spike 实测后可再收紧，R2: B-R2-9） |
| 不承诺 | PC 端**不做**图形前端、不做 USB/9p 透传；`:5900` 可连但需自备 viewer |

**验收（R-16）**：`tools/pc-run.sh debian.img` 在 x86_64 Ubuntu/Debian 上 **90 秒内**输出 `Ready!`，
且 `ssh -p 9922 ltbkq@127.0.0.1`（pw `123`）成功 —— 两条断言**都必须进 CI**（`pc-boot-smoke.sh`）。

### 11.5 `.img` 的可移植性（附带收益）

`.img` 就是一个带尾部元数据的 squashfs，因此：

- PC 上可 `sudo unsquashfs -s debian.img` 查看内容；
- 可直接当只读盘喂给 QEMU（配合 APK 里的 `vmlinuz-virt`/`initrd.img` 与
  `init-podroid` 契约）；
- 可整份拷贝备份，或放进网盘分发。

（文档列为**附带收益**：`unsquashfs -s debian.img` 可直接查看内容；PC 启动能力本身见 §11.4。）

---

## 12. 构建与发布

### 12.1 应用侧（本仓库）

| 项 | 决定 |
|---|---|
| 包名 | `io.github.ltbkq.vmdroid`（debug 加 `.debug` 后缀） |
| 应用名 | VMDroid（`strings.xml` 同步 `values-zh`） |
| 资产 | 只剩 `vmlinuz-virt`、`initrd.img`、`qemu/`（**移除 rootfs**） |
| Gradle | 移除 rootfs 打包/noCompress 相关配置；`versionCode` 与镜像解耦（应用版本 ≠ `system_version`） |
| 签名 | 复用 `Podroid-Debian/keys/` 的项目密钥思路：keystore 不进 VCS，密码经 `-P` 传入 |
| 上游同步 | 保留 `upstream` remote，便于 cherry-pick 上游 VM 功能修复 |

预期 APK：**367 MB → ≈ 70 MB**（-81%）。

### 12.2 镜像侧（Podroid-Debian）

- `build-rootfs.sh` 产出 `.squashfs`（不变）→ 新增 `mkimg.sh` 包装为 `.img`。
- CI（GitHub Actions，arm64 runner 或现有 local/docker 路径）：
  构建 rootfs → `mkimg`（`--kernel/--initrd`）→ 计算 sha256 → 上传 Release →
  生成 `catalog.json` → **ECDSA P-256 签名生成 `catalog.json.sig`** → 上传 `.json`+`.sig`。

### 12.3 发布物

| 产物 | 位置 | 说明 |
|---|---|---|
| `vmdroid-<ver>.apk` | 本仓库 Release | ≈70 MB |
| `debian.img`（首发最小化，**含 kernel/initrd payload**） | Podroid-Debian Release | **≤ 150 MB**（唯一阈值，含内核；见 G7/§4.5/§13.1），单文件系统，PC 可启动（R-16） |
| 后续 `debian-*.img` / `ubuntu.img` | 同上 | 按 §4.7 路线图逐个上架 |
| `catalog.json`(+ `.sig`) | 同上 `latest/download/` | 应用默认订阅 |
| 源码 | 两仓库 | GPLv2 §6/§63 合规（源码 + 构建说明） |

> 全量构建（345 MB / 329 MiB，含容器栈 + 桌面）仅作体积对比基线，非首发发布物。

### 12.4 固件产物流水线（kernel / initrd / qemu，R2: D-R2-1 Blocker）

**问题**：`vmlinuz-virt`、`initrd.img`、`qemu/*.so` 在上游是 **gitignored 的生成物**
（需 `./build-all.sh kernel|initramfs|qemu`，Docker 构建），两个仓库都没有源文件；
但 M1（APK 打包）、M2（`mkimg --kernel`）、R-16（PC 启动）都要用它们。

**决策**：

| 制品 | 构建方 | 分发方式 | 消费方 |
|---|---|---|---|
| `vmlinuz-virt` + `initrd.img` | **Podroid-Debian CI** 调用上游 `build-all.sh kernel initramfs`（Docker + binfmt），产物落 `out/` | ① 打进 `debian.img`（`mkimg --kernel/--initrd`）；② 作为 **Release 资产**单独上传（含 `sha256sums.txt`） | vmdroid APK `assets/`（CI 下载 + 校验 sha256）；`pc-run.sh` 从 `.img` 提取 |
| `qemu/*.so` + `jniLibs` | 同上（`build-all.sh qemu`） | Release 资产 + `sha256sums.txt` | vmdroid CI 下载入 `jniLibs/arm64-v8a/`（校验 16KB 对齐） |

- **同源保证**：APK 与 `.img` 的内核来自**同一次 CI 构建的同一批 sha256**；
  构建期把 `kernel_version` + `kernel_sha256` 写入 APK 的
  `assets/firmware.properties`，供 §4.4 比对 manifest `contract.kernel.image_sha256`。
- **M1 阻塞解除**：M1 可先用"从已装上游 APK 提取"的资产起步（同 PLAN.md 做法），
  M2 起改由 CI 供给。
- 上游内核源码见 `ExTV/Podroid`（GPLv2，`build-all.sh kernel`），须在 Release 中提供源码链接。
---

## 13. 测试与验收

### 13.1 自动化

| 层 | 内容 | 归属 |
|---|---|---|
| 构建产物 | **APK 体积 ≤ 80 MB（CI 硬断言；G1 目标 ≈70 MB，+10 MB 为余量，R3: A-R3-20/C-R3-11）**；资产清单仅 `vmlinuz-virt`/`initrd.img`/`qemu/`（≈20 MB）、**无 `*.squashfs`**（G1/R-01） | vmdroid CI |
| **PC 冒烟（R-16）** | `mkimg` 自检：提取 `.img` 内 kernel/initrd → 起 `qemu-system-aarch64` **90s** 内断言 `Ready!` **且** `ssh -p 9922` 可登录（`pc-boot-smoke.sh` = `pc-run.sh --smoke`） | Podroid-Debian CI |
| 单元（JVM） | `VmdImageCodec`：往返编解码、footer 定位、字段缺省、截断/损坏/错位/越界拒绝、`ssh_port≠22` 拒绝；identity 判据全表（§5.2 含内容优先与硬判据）；状态机迁移；流式 sha256 | vmdroid |
| 工具侧 | `mkimg.sh` ↔ Kotlin codec **互操作**；测试向量入库；catalog schema 前向兼容（未知字段/新 image 条目） | vmdroid + Podroid-Debian |
| 下载 | Range 续传（含 416 回退）、ETag 变化重下、`.part` 断点恢复、校验失败清理 | vmdroid |
| 镜像内容 | 镜像检查脚本：`getent passwd ltbkq`、`visudo -c`、dropbear 允许 root 与密码登录、**两账户凭密码 `123` 可认证**（`openssl passwd -6 123` 比对 shadow 或直接以登录成功为真值）、`ss -tln` 断言 guest **:22**、`/etc/dropbear/` **无** `*_host_key` 私钥 | Podroid-Debian CI |
| 最小化负面断言 | dpkg **不含** `docker.io/podman/lxc/xfce4/lightdm`；包数 **≤260**（目标 210–250）；`.img ≤ 150 MB` | Podroid-Debian CI |
| 契约指纹 | `.img` 内 `/usr/local/lib/podroid/*`、`podroid-*`、**契约 5 条标记**（IMAGE-FORMAT `contract.markers`，防契约漂移；完整 8 项检测列表见 §16.4） | Podroid-Debian CI |
| 回归（沿用） | `Podroid-Debian/tests/test_dns.sh`、`tools/boot-test.sh`（轮询 `Ready!`；**参数化 `PKG=${1:-io.github.ltbkq.vmdroid.debug}` 并自建 `adb forward tcp:9922 tcp:9922`**，R1: B-R1-8） | Podroid-Debian |
| **日志与诊断（§16）** | 轮转/`boot_id`/`meta.json` 序列化单测（含 `finished_at:null`、killed 补写、`stages==[]` 分支）；`image.log` JSONL 逐行可解析 + 轮转 reopen；90s 超时写 `fail_stage`；**轮转失败不阻断启动**；导出 zip 流式且 **`grep -E '"password"[[:space:]]*:[[:space:]]*"123"'` 命中 = 0**（R3: C-R3-4 —— 裸 `grep 123` 会因 sha256/时间戳误报） | vmdroid |
| **目录签名与门禁（§6.1/§7.4，R-14/R3: C-R3-3）** | ① 篡改 `catalog.json` 1 字节 → sig 失败拒载 ② 缺 `.sig`/公钥不匹配 → 拒载（不降级裸 HTTPS）③ **正例互操作**：openssl 生成 `.sig` → 应用 verify 通过（固定 key+message+sig 测试向量入库）④ `app.min_version_code > current` → `bootguard_reject=APP_TOO_OLD` | vmdroid CI |
| **首发口径（R-05，R3: C-R3-9）** | `catalog.json` 中 images 数组长度为 1 且 `image_id == "debian-minimal-arm64"`；Release 资产仅 1 个 `.img` | Podroid-Debian CI |
| **文档与上游对齐（N1/R-04，R3: C-R3-10/14）** | ① grep 断言 README.md/README.en.md/DESIGN.md 头部各含中英文"中文为准"声明 ② fork 后对 §3 标"原样保留"的文件 `git diff` 断言仅改名/包名、无逻辑改动 | vmdroid CI |
| **UI 基线（§8.0）** | `lintHardcodedText` 零告警；`values-en` 覆盖率 ≥ `values/`；每页四态 checklist 文档路径 | vmdroid |

### 13.2 真机冒烟矩阵

| 用例 | QEMU 后端 | AVF 后端 | 归属 |
|---|---|---|---|
| 无镜像启动 → 引导页（**不启动死机**） | ✅ 必测 | ✅ 必测 | vmdroid |
| **E2E 换系统**：装 A → 装 B（同 identity）→ 选择控件激活 B → 启动 `Ready!` 且数据保留；再切回 A（回滚）→ `Ready!` | ✅ 必测 | ✅ 必测 | vmdroid |
| **零应用发版**：staging catalog 新增**构建期未知** `image_id` → 同一 APK 拉取/下载/激活/启动（含未知字段前向兼容） | ✅ | ✅ | vmdroid |
| **删除镜像**：删除非激活镜像（文件 + `.meta.json` 清除、激活项不受影响） | ✅ | ✅ | vmdroid |
| 导入**非 `.img`**（裸 `.squashfs`/`.zip`/伪造扩展名）→ 拒绝并给出封装指引 | ✅ | ✅ | vmdroid |
| 导入合法 `.img` → 激活 → `Ready!` | ✅ | ✅（P0 spike） | vmdroid |
| 下载 60% 杀进程 → 续传 → 校验 → 激活 | ✅ | ✅ | vmdroid |
| **空间不足**（`size + 余量` > 可用）→ 下载/导入**写盘前**拦截为 `STORAGE_FULL`，不遗留 `.part` | ✅ | ✅ | vmdroid |
| 损坏镜像（`dd conv=notrunc` 改 1 字节，mtime 变）→ 启动前置重算 → `CORRUPT` 拒绝启动 | ✅ | ✅ | vmdroid |
| 同 identity 升级 → 数据保留 | ✅ | ✅ | vmdroid |
| 换 identity → 强制重置 → 正常启动 | ✅ | ✅ | vmdroid |
| **恢复出厂**：清零重建 `storage.img` → 开机由 `init-podroid` 自动 `mkfs.ext4` → `Ready!` 且持久数据回到全新状态 | ✅ | ✅ | vmdroid |
| APK 升级后镜像**不被**重拷（耗时 <5s） | ✅ | ✅ | vmdroid |
| **SSH 双账户登录（guest 端口 22，宿主 9922）**：`ssh root@localhost -p 9922` 与 `ssh ltbkq@localhost -p 9922`（密码 `123`）均成功；guest 内 `ss -tln` 断言 `:22` | ✅ | ✅ | Podroid-Debian + vmdroid |
| `ltbkq` 免密 sudo 可用（`sudo -n true`） | ✅ | ✅ | Podroid-Debian + vmdroid |
| **回环绑定（S7）**：手机侧 `ss -tln` 断言 9922/5900/4713 仅绑 `127.0.0.1`；打开"允许局域网 SSH"后变 `0.0.0.0`（R3: C-R3-16） | ✅ | n/a（QEMU hostfwd） | vmdroid |
| `debian.img`（Xvnc 有、容器无）→ 容器能力降级不报错；X11 连 `:5900` 成功 | ✅ | ✅ | Podroid-Debian + vmdroid |
| **启动镜像选择控件（§8.2，N3 要求 8 状态全覆盖）**：①运行中禁用+说明 ②判据相同直接切换+提示 ③需重置先弹 §8.5 ④仅 1 镜像仍显示选择器 ⑤校验中进度圈+禁用 ⑥`CORRUPT` 标红+⚠+文字 ⑦空态双入口（Filled/Text）+启动按钮隐藏 ⑧加载失败保留上次值+inline error 不塌陷 | ✅ | ✅ | vmdroid |
| **日志诊断（§16）**：连续 10 次启动（典型 console <1MB）→ `boots/` ≤10 次且**历史不丢**；**通过预检但 guest 起不来的镜像**（如去掉 `podroid-ready` 的 mkimg 产物）→ `meta.json` 有 `result=timeout`+`fail_stage`+`console_tail_b64`；**进程被杀** → 下次启动补写 `result=killed`；触发重置 → `image.log` 有 `activate.decision=identity` | ✅ | ✅ | vmdroid |
| **UI §8.0 抽查（①–⑥ 逐条）**：双主题 / 间距序列 / 四态 + 错误态可执行文案 / 卡片顺序+唯一 FilledButton / 破坏性二次确认 / **⑥ 中文排版（半角空格·标点不混用·长名截断）**（截图评审，R3: C-R3-18） | ✅ | ✅ | vmdroid |
| **R-16 PC 启动**：`tools/pc-run.sh debian.img`（x86_64 Linux, TCG）→ **90s** 内 `Ready!`，`ssh -p 9922 ltbkq@127.0.0.1`（pw `123`）成功 | n/a（PC 用例，见 §11.4/§13.1） | n/a | vmdroid（脚本）+ Podroid-Debian（镜像） |
| 终端 / VNC / 端口转发 / host bridge / USB | ✅ | ✅（USB QEMU 专属） | vmdroid |

### 13.3 P0 风险 spike（先于编码，M0 内）

1. **尾随数据直挂**：现有 `debian-rootfs.squashfs` 追加 4 KiB（含 `VMDIMG01` footer），
   真机 QEMU 后端挂 `/dev/vdb` → 能否正常 mount 并进入 `Ready!`。
2. 同上在 AVF 后端（crosvm）验证。
3. **截断回归**：同一镜像截断 1 字节 → 必须挂载失败（证明"尾随安全、截断危险"）。
4. 若 1 或 2 失败 → 启用 §4.3 `fallback-split` 分支；**同时决策 B-R1-17**（2× 占用 vs 删除 `.img`）。
   > 事实依据（R1: B-R1-21）：内核 `fs/squashfs/super.c` 唯一尺寸校验是
   > `bytes_used ≤ 设备大小`，不检查"设备大于文件系统"；`squashfs-tools` 无 fsck。
   > 已用 `mksquashfs` 产物 + 4 KiB 追加实测 `unsquashfs` 全通过 → **QEMU/AVF 块层接受度**是唯一未知数。

---

## 14. 里程碑

| 阶段 | 内容 | 退出标准 | 预估 |
|---|---|---|---|
| **M0** 规格冻结 | 本设计评审（R1–R3）+ P0 spike（§13.3）+ B-R1-17 占用决策 | spike 结论：direct 可行 / 需 fallback-split 及其占用方案 | 1–2 天 |
| **M1** 骨架 | fork 上游 → 改名/包名/品牌 → 移除 rootfs 资产 → §8.0 设计系统落点（主题/token/间距常量 + **Home 页四态**；全站四态审计归 M6）→ **§16 基础日志**（`VmdLog`：启动前轮转 `boots/` + `boot_id` + 阶段打点 + `meta.json`，L1/L2 修复） | APK ≈70 MB 可安装；`images/` 为空时进 Setup 占位 + 启动按钮隐藏（**BootGuard 简版：仅 ABSENT 判定**，完整校验归 M3，R2: D-R2-4）；**连续 2 次启动历史不丢** | 3–4 天 |
| **M2** 格式与工具 | **vmdroid**：`VmdImageCodec` + 测试向量消费；**Podroid-Debian**：`mkimg.sh`（`--kernel/--initrd`）、`manifest.json`、**`packages-minimal.list` + 首个 `out/debian.img`**、账户规范（§4.8 含 host key 首启生成）、`pc-run.sh`/`pc-boot-smoke.sh`、`graft.sh` 废除、`boot-test.sh` 参数化、**journald 64MB 限额 drop-in（§16.6）** | 互操作测试通过；**R-16 PC 冒烟 `Ready!` + ssh**；镜像 ≤150 MB、包数 ≤260、负面断言通过 | 4–5 天（R2: D-R2-3/D-R2-14 建议拆 M2a/M2b） |
| **M3** 镜像管理 | 导入/校验/激活/删除/BootGuard/identity 判据 + **§8.2 启动镜像选择控件** + **`image.log`（§16.5，L3 修复）** | §13.2 中"导入/E2E 换系统/损坏/identity"用例通过 + `activate.decision` 有记录 | 3–4 天 |
| **M4** 下载 | 目录 + 断点续传 + 通知进度 + §8.3 镜像页 + 下载事件入 `image.log` | 60% 杀进程续传用例通过 | 2–3 天 |
| **M5** 恢复出厂 | **清零重建** `storage.img`（guest 自动 mkfs，§4.3）；无需种子提取 | Reset 与"恢复出厂"行为正确、全零盘首启格式化成功 | 1 天 |
| **M6** 回归 | 真机双后端矩阵 + 契约指纹回归 + guest identity 守卫（可选）+ **诊断 UI + 导出 zip 扩展（§16.4/§16.8）** + 全站四态审计 | §13.2 全绿（含 §16 日志用例） | 3 天 |
| **M7** 发布 | CI、签名、Release、catalog + `catalog.json.sig`（ECDSA P-256）、公钥内置 APK、SSH 收紧、文档、license 合规 | 首个公开版本；**签名无效用例拒载通过** | 2 天 |

**合计 19–24 个工作日 ≈ 4–5 周**（不含 AVF 真机排期依赖；M2 另有拆分意见见 R2: D-R2-14）。

---

## 15. 开放问题

| # | 问题 | 倾向 |
|---|---|---|
| Q1 | `.img` 直挂（direct）在 AVF/crosvm 上是否可靠？ | P0 spike 决定；不可靠则走 fallback-split 降级 |
| Q2 | 是否允许镜像自带 `kernel`/`initrd` payload？ | **已决（R-16）**：必须自带，`mkimg --kernel/--initrd`，PC 端启动依赖它；Android 端仍用 APK 内置（同源） |
| Q3 | 外部路径直挂（不拷贝到私有目录）是否可行？ | 需验证 scoped storage 下 QEMU/crosvm 打开 `/storage/emulated/0/...` 的能力与性能 |
| Q4 | 已安装镜像数量上限 / 存储配额策略 | 建议 2 个，超出提示删除 |
| Q5 | 启动失败自动回滚（A/B） | v1 不做；先做"手动回滚到另一已装镜像" |
| Q6 | 目录签名公钥轮换策略 | **已决（2026-10-08）**：算法 = ECDSA P-256 + SHA-256，公钥内置 APK、detached `.sig` 覆盖原始字节（§6.1）；轮换 = 发新 APK，v1 不做多密钥列表 |
| Q7 | 包名是否沿用 `com.excp.podroid`（便于未来原地升级） | 当前建议新包名；若预期与上游合并需重新讨论 |
| Q8 | `.img` 是否需要加密（Android FBE 已保护私有目录） | 暂不额外加密 |
| Q9 | root 密码 `123` 是否强制首启改密 | **已决**：不强制（§4.8 规定 `root/123` + `ltbkq/123` 为镜像规范），改由回环转发 + Settings 修改入口保证安全 |

---

## 16. 日志与诊断（排查纠偏）

> **目标**：任何一次启动失败/镜像异常，事后都能从日志**定位到原因与阶段**，
> 不需要复现。三条原则：①**历史可追溯**（不覆盖上次）②**结构化可 grep**
> ③**一键导出**（继承上游 Export Log）。

### 16.1 现状问题（实测，2026-10-08）

| # | 问题 | 证据 |
|---|---|---|
| L1 | **`console.log` 每次启动被覆盖**，上一次启动为什么失败无从查起 | 上游 `QemuBootMonitor.kt:88` `FileOutputStream(consoleLog, false)` = truncate 模式（`:87` 先 `delete()`） |
| L2 | 启动阶段**无分阶段耗时**，无法判断"卡在哪个阶段/慢了多少"（上游仅记录**总**时长 `lastBootDurationMs`，见 `QemuEngine.persistBootDuration()`） | `BootStageDetector` 只设 `bootStage` flow，不打分阶段时间戳 |
| L3 | 镜像生命周期（下载/校验/激活/重置）**完全无日志** | 本设计 §5–§6 未定义任何日志 |
| L4 | guest journal **未配置显式 `SystemMaxUse`**（默认 = 文件系统 10%、**上限 4 GiB**；8 GB 盘 ≈ 800 MB），长期运行撑大 `storage.img` | 实测 `/var/log/journal` 存在（持久化），`journald.conf` 中 `#SystemMaxUse=`/`#MaxRetentionSec=0` **均为注释**（R3: B-R3-9 修正措辞） |
| L5 | 启动失败时**无结构化失败原因**（只有原始控制台流） | 无 `boot.meta.json` 概念 |

### 16.2 日志文件布局（`filesDir/`）

```
filesDir/
├── console.log                # ★ 契约路径不变（boot-test.sh 依赖）= 最近一次启动
├── boots/                     # ★ 新增：历史启动归档（启动前轮转，L1 修复）
│   ├── 20261008T142231-a1b2c3.console.log
│   ├── 20261008T142231-a1b2c3.meta.json
│   └── …                      # 保留最近 10 次或 50 MB（LRU 删除）
├── image.log                  # ★ 新增：镜像生命周期 JSONL（滚动 3×2 MB）
├── verbose.log                # ★ verbose 级明细（1×2 MB，仅开关开启时，§16.7/§16.9）
└── log.txt                    # 导出产物（上游既有，§16.8）
```

**轮转时序（R3: A-R3-5/B-R3-5 —— 归档用上一次的 boot_id，不是新 id）**：

```
VmdroidService.start()  （BootGuard 通过后、engine.start() 之前）
 ① 读 filesDir/.last_boot_id  → prev_id（不存在则跳过 ②）
 ② 既有 console.log（上一次启动的产物）→ 重命名为 boots/<prev_id>.console.log
 ③ 与已存在的 boots/<prev_id>.meta.json 合并：
      meta 缺失（上次进程被杀/崩溃）→ 写 {"boot_id":prev_id,"orphan":true,
                                          "result":"killed","finished_at":null}
    （半截 console.log **原样归档**，不修补）
 ④ 生成本次 boot_id → 写 boots/<new_id>.meta.json（finished_at:null, result:null）
    → 写 .last_boot_id = new_id
 ⑤ engine.start()   ← 引擎此时才以 truncate 模式新建 console.log（本次启动）
```

**后端差异（R3: B-R3-8，两后端 console.log 语义不同，不能按单一契约描述）**：

| 后端 | console.log 内容 | 落盘时机 | 影响 |
|---|---|---|---|
| QEMU | **boot 段 + 整段交互会话**（`QemuBootMonitor` 全程写，每 chunk flush） | VM 运行全程 | 导出含用户终端交互内容 → §16.8 脱敏须覆盖 |
| AVF | **仅 boot 段**（`AvfEngine` 隐私门：`if (state is Starting)` 才落盘，进 Running 即停） | 仅 Starting | 运行期崩溃取不到 `console_tail_b64`；归档/轮转逻辑两后端相同 |

`meta.json.backend` 字段记录后端，诊断 UI 按后端标注"日志范围"。

**轮转失败不阻断启动（R3: C-R3-6）**：rename/写 meta 任一失败 → logcat warning +
跳过本次归档，**绝不阻止 VM 启动**（日志是辅助手段，不能成为启动的前置依赖）。

### 16.3 boot_id、结构化格式与写入协议

- `boot_id` = `yyyyMMddTHHmmss-<6hex>`，**本次启动前**生成（④ 步），写入 meta.json 与
  `image.log` 各行的 `boot` 字段；`.last_boot_id` 只在 ④ 步更新。
- `console.log` 保持**原始字节流不加前缀**（契约：`BootStageDetector` 靠子串匹配，
  不能污染）；时间戳只进 `meta.json` 与 `image.log`。
- `image.log` 每行一个 JSON（JSONL）：
  ```jsonc
  {"ts":"2026-10-08T14:22:31.412+08:00","boot":"…a1b2c3","ev":"verify_ok",
   "image":"debian-minimal-arm64","sha256":"ab12…","src":"download","dur_ms":4120}
  ```

**写入协议（崩溃安全，R3: B-R3-6/C-R3-5）**

| 对象 | 协议 |
|---|---|
| `*.meta.json` | **启动时**写一次（temp + fsync + rename，`finished_at:null, result:null`）；`Ready!`/超时/停止时**再写一次**覆盖 finalize。任何时刻磁盘上都是完整 JSON |
| `image.log` | **单写线程** + `O_APPEND` 单次 `write` 整行（行不撕裂）；**轮转在同一线程内** rename → create+reopen（否则 fd 指向被滚动走的旧文件，新文件永远为空） |
| `result` 判定 | `ready` = detector 真实命中 `Ready!`；`timeout` = 90s 未命中；`failed` = 引擎 `VmState.Error`/非零退出；`killed` = 用户 Stop 或进程被回收（下次 ③ 步补写） |
| fsync | `meta.json` 每次写都 fsync；`image.log` 仅在 `activate`/`verify_fail`/`factory_reset` 关键行后 fsync（性能与证据可靠性折中，与 §6.2 对 `.part` 的 fsync 基线一致） |

### 16.4 启动阶段时间线（L2/L5 修复）

**打点算法（R3: B-R3-2 —— 上游 detector 每次 feed 只回报 1 个 marker，直接挂钩会漏记）**：

```
不要在 BootStageDetector 的单-marker 回调上打点（它 firstOrNull 只返回优先级最高的一项，
且 overlap 会让同一 marker 跨 feed 重复命中）。正确做法：
  在 detector 收到 chunk 时，对「本次 chunk + carry overlap 原文」独立做 8 个子串匹配：
    for each marker in MARKERS:  在原文中 find 所有出现位置
  按出现顺序记录，按 name 去重（保留首次），t_ms = now - boot_start
  ★ 只读原文、只加时间戳：不改 detector 的判定 / one-shot / overlap 语义（不违反 G4）
  ★ BootStageDetector 源码零改动；打点逻辑是旁路观察者
```

`stages[].name` 复用上游 `BootStageDetector.MARKERS` 的 8 个标签（实测自
`ExTV/Podroid@ce01218`），不新造词汇。**注意（R3: B-R3-11/A-R3-22）**：
`Booting kernel...` 无 guest 输出源（仅 UI 初值），单次启动通常命中 **6–7 项**，
故 `stages` 是**按命中顺序的子集**，示例取实际可得的 7 项：

```jsonc
// boots/<boot_id>.meta.json
{
  "boot_id": "20261008T142231-a1b2c3",
  "started_at": "…", "finished_at": "…",     // 被杀时 finished_at 可为 null（③ 步补写）
  "backend": "qemu",                          // qemu | avf  （决定 console.log 范围，见 §16.2）
  "image": "debian-minimal-arm64", "image_sha256": "ab12…",
  "app_version": "1.0.0", "system_version": 34,
  "t_ms_origin": "bootStartTime",             // t_ms 零点 = 上游 bootStartTime（R3: B-R3-14）
  // stages = 命中子集（8 个标签集合的 6–7 项）：
  "stages": [ {"name":"Mounting storage...",       "t_ms":1840},
              {"name":"Loading kernel modules...", "t_ms":2010},
              {"name":"Configuring containers...", "t_ms":6390},
              {"name":"Network found",             "t_ms":6420},
              {"name":"Starting SSH...",           "t_ms":11850},
              {"name":"Almost ready...",           "t_ms":11902},
              {"name":"Ready",                     "t_ms":12044} ],
  "result": "ready",           // ready | failed | timeout | killed
  "fail_stage": null,          // 见下（含 stages 为空的分支）
  "console_tail_b64": null,    // failed/timeout/killed 时 = console.log 末尾 4KB（gzip+base64）
  "console_tail_absent": false,// console.log 缺失（AVF 运行期事件/文件被清）→ true，tail=null
  "qemu_argv": null            // verbose 开启时附加：完整 QEMU 命令行（§16.7，R3: A-R3-38）
}
```

- **数据源（R3: B-R3-4）**：`result`/`stages` **只由 detector 的真实 marker 命中驱动**，
  忽略引擎的合成 `bootStage` —— 上游两后端都有 **120s 安全网**
  （`QemuEngine.BOOT_READY_SAFETY_MS=120_000`、`AvfEngine.BOOT_TIMEOUT_MS=120_000`，
  超时会**合成** `Ready` 并转 Running）。**meta 记 90s、引擎兜底 120s，职责不同**：
  meta 的 90s 超时是诊断判据（与 `boot-test.sh BOOT_TIMEOUT=90` 同源），
  超时后**不因引擎合成而改写 `result`**。
- **`fail_stage` 分支（R3: B-R3-3）**：
  - `stages` 非空 → `fail_stage` = 最后一项 `name`；
  - `stages` 为空（卡在极早期）→ `fail_stage = null`，证据靠 `console_tail_b64`；
  - `result=killed` → 同上规则，由 ③ 步补写。
- **UI**：Settings → 诊断 列表展示最近启动（耗时 / 结果 / 失败阶段），
  点击可看 `console.log` 尾部；**启动失败时自动置顶该条**。

### 16.5 镜像生命周期日志（L3 修复）

`image.log` 记录以下事件（每条含 `ts`/`image`/`src`/`dur_ms`）：

| 事件 | 触发点 | 关键字段 |
|---|---|---|
| `download_start` / `download_progress` / `download_done` / `download_fail` | §6.2 | `bytes`、`resume`（是否续传）、`err` |
| `import_start` / `import_done` / `import_fail` | §6.3 | `src_uri`、`bytes`、`err` |
| `verify_ok` / `verify_fail` | §6.4 | `sha256`、`fail`（footer/manifest/段 sha 之一） |
| `activate` | §5.3 | `from`、`to`、`decision`（枚举见 §5.2 表：`same`/`upgrade`/`identity`/`contract`/`init`）、`reset`（bool） |
| `factory_reset` | §4.3 | `old_size`、`new_size`、`method`（`whole-file-zero`） |
| `install_delete` | §5.3 | `image` |
| `bootguard_reject` | §7.4 | `reason`（**枚举 = §7.4 权威清单**：`NO_SYSTEM_IMAGE`/`CORRUPT`/`APP_TOO_OLD`/`ARCH`/`SSH_PORT`/…，R3: A-R3-16 统一） |

**节流**：`download_progress` 每 5% 或 4 MB 记一行（§16.9），其余事件必记。

**这组日志直接回答"换镜像后为什么起不来/为什么要求重置"** —— 每次 `RESET_REQUIRED`
必须留下 `activate.decision` 记录。

### 16.6 guest 侧日志（L4 修复）

- journal **已持久化**（`/var/log/journal`，写入走 overlay → `storage.img`）→ 跨重启可查，
  但**必须加上限**，由 `Podroid-Debian` 镜像内新增 drop-in：
  ```
  # /etc/systemd/journald.conf.d/vmdroid.conf  （镜像内容，不违反 G4）
  [Journal]
  SystemMaxUse=64M
  MaxRetentionSec=1month
  ```
- **获取路径（v1）**：`ssh -p 9922 ltbkq@127.0.0.1 'journalctl -b -1 -p err --no-pager'`
  （文档提供命令；应用内一键收集见 §16.12 LQ1）。
  ⚠️ **权限（R3: B-R3-10 待验证）**：实测镜像内 `systemd-journal` 组**无成员**，
  journal 文件 0640 + ACL 继承需开机确认；且 `ltbkq` 属 §4.8/M2 交付。
  **验收**：boot-test 通过后执行上述命令期望 `rc=0`；若 `Fail` →
  在 §4.8 账户规范把 `ltbkq` 加入 `systemd-journal` 组。
- guest 侧单元日志落点**沿用上游**：契约单元 `StandardOutput=journal+console`
  （同时进 `console.log`），非契约单元仅 journal。

### 16.7 日志级别

| 级别 | 内容 | 默认 |
|---|---|---|
| `info` | 上述结构化事件 + `logcat` info | ✅ |
| `verbose` | QEMU 命令行全文、QMP 交互、镜像校验逐段结果 | ❌（Settings 开关，复用上游 `avfVerboseLogging` 语义泛化） |

- verbose 时 QEMU 完整命令行（含所有 `-drive`/`-device` 参数）写入 `meta.json.qemu_argv`
  —— 排查"参数拼错"类问题的关键证据；QMP 交互与校验逐段明细写
  **`filesDir/verbose.log`（1 × 2 MB 滚动，进 §16.9 配额表）**，导出时仅在 verbose
  开启过才带上；logcat 等级用 `Log.isLoggable`/`setProp.log.tag.<TAG>` 提升
  （R3: B-R3-17 —— 此前只定义了落点之一）。

### 16.8 一键导出（继承上游 + 扩展）

上游 Settings → Diagnostics → **Export Log**（`SettingsViewModel.exportConsoleLogs()`，
构建 `log.txt` = 诊断头 + 引擎诊断 + 本进程 logcat + `console.log`，分享出去）——
**保留并扩展**为导出包（zip）：

```
vmdroid-diag-<ts>.zip
├── log.txt                 # 上游格式（诊断头 + logcat + 最近 console）
├── boots/ 最近 3 次 *.meta.json + *.console.log 尾部 64 KB
├── image.log               # 全量（≤6 MB）
├── active.json · <image>.img.meta.json   # 镜像状态
└── device.txt              # 机型/Android 版本/后端/权限（AVF 探测，沿用 AvfDiagnostics）
```

- **脱敏**：`accounts[].password`、下载 URL 中的 token 查询参数在导出前替换为 `***`
  —— 对**含密码字段的来源**（manifest/catalog 副本）生效；console 尾部按"交互内容"
  取舍：QEMU 后端只导出 **boot 段**（到 `Ready!` 为止）+ 明确提示"运行期交互内容
  不导出"，AVF 本就只有 boot 段（§16.2 后端差异，R3: B-R3-8）。
- **release 包不可 `run-as`**（上游已知事实）→ 导出是唯一主路径，必须可用。
- **实现**：**流式写入**（逐条 `FileInputStream` → `ZipEntry`，console 只读尾部 64 KB），
  按已写字节判定 40 MB 上限；**禁止**整包驻留内存（上游 `buildDiagnosticLog()` 已是
  `readText()+buildString`，扩展到 40 MB 规模会 OOM，R3: B-R3-19）。

### 16.9 配额与轮转（硬上限）

| 对象 | 上限 | 超限行为 |
|---|---|---|
| 单次 `console.log` | **归档后** 8 MB | 归档时截断**头部**保留尾部（失败信息在末尾）。⚠️ 运行期间由引擎自己写、无上限（R3: B-R3-7：引擎零改动 ⇒ 无法运行期截断，外部线程 truncate 会与 writer 偏移打架）；故 8 MB 是**归档后上限**，运行期可能瞬时更大 |
| `boots/` | **同时满足** `≤ 10 次启动` 且 `Σ ≤ 50 MB`（两条件取更严者） | 按 `boot_id` 时间前缀排序，**成对删除**（`*.console.log` + `*.meta.json` 一起） |
| `image.log` | 3 × 2 MB | 滚动（同一线程 rename→reopen，§16.3）；`download_progress` **每 5% 或每 4 MB 记一行**（取较稀者），避免挤掉 `activate`/`verify_fail` 关键证据（R3: B-R3-18） |
| `verbose.log` | 1 × 2 MB | 滚动（§16.7，仅 verbose 开启时存在） |
| guest journal | 64 MB（§16.6 drop-in） | journald 自动轮转 |
| 导出包 | ≤ 40 MB | 超限只带尾部并提示 |

### 16.10 测试与验收

| 层 | 用例 |
|---|---|
| 单元（vmdroid） | `boot_id` 生成/meta 序列化（`finished_at:null`、`orphan`、`stages==[]→fail_stage=null`）；**轮转边界** ≤10 次 ∧ ≤50 MB 双条件 + 成对删除 + 8MB×10→实留 6 条；`image.log` JSONL 可解析 + **轮转 rename→reopen 后新文件非空**；90s 超时写 `fail_stage`；`download_progress` 节流 |
| 配额 | 单次 console 归档后 >8MB → 头部截断保尾；`image.log` 滚动后关键行（`activate`/`verify_fail`）保留；导出超 40MB → 只带尾部并提示（R3: C-R3-7） |
| 真机 | **启动失败**（构造**通过预检但 guest 起不来**的镜像：footer/manifest/sha 全合法，但 rootfs 缺 `podroid-ready` —— 与上一行"损坏镜像→CORRUPT 拒启"不同，后者不进 boot 故无 meta，R3: A-R3-1/B-R3-3）→ `meta.json` 有 `result=timeout` + `fail_stage` + `console_tail_b64`；**进程被杀** → 下次启动 ③ 步补写 `result=killed` + `orphan`；**连续 10 次启动** → `boots/` ≤10 次启动条目；**换镜像触发重置** → `image.log` 有 `activate.decision=identity`；**断点下载** → `download_progress.resume=true`；**`boots/` 只读/无空间** → 启动仍 `Ready!` 且不崩溃（R3: C-R3-6） |
| 导出 | 导出 zip 含全部条目、**流式写入**不 OOM，且**按密码形态断言**：解包后 `grep -rE '"password"[[:space:]]*:[[:space:]]*"123"'` = 0（`accounts[].password` 已脱敏为 `***`；R3: C-R3-4/A-R3-33/B-R3-13 —— 裸 `grep 123` 必然因 sha256/时间戳误报） |
| guest | journal 达 64MB 后自动轮转，`storage.img` 不因日志持续增长 |

### 16.11 归属

| 交付物 | 仓库 |
|---|---|
| 轮转、`boot_id`、`meta.json`、阶段打点、`image.log`、导出扩展、诊断 UI | vmdroid |
| journald 限额 drop-in、（可选）guest 日志收集脚本 | Podroid-Debian |

### 16.12 开放项

| # | 问题 | 倾向 |
|---|---|---|
| LQ1 | 应用内一键拉取 guest journal（需 hostd 新命令 → 动 guest 契约二进制） | v1 不做（SSH 命令够用）；v2 评估 hostd 扩展的契约影响 |
| LQ2 | `console.log` 是否也按 `boot_id` 分文件而非轮转改名 | 现方案改动最小（引擎零改动），保持 |

---

## 附录 A：术语

| 术语 | 含义 |
|---|---|
| VM / 虚拟机 | 由 QEMU(TCG) 或 AVF(pKVM) 运行的 Guest Linux，内核+initrd 来自 APK |
| 系统镜像 `.img` | 单文件 Guest rootfs（squashfs 前置 + kernel/initrd + manifest/footer；**无种子段**） |
| vda | 可写持久盘 `storage.img`（ext4），承载 overlay upper 与容器数据 |
| vdb | 只读系统盘（`.img`），挂载为 `/mnt/lower` |
| 启动契约 | `init-podroid` 挂载顺序、控制台标记、tty 角色、端口转发、存储布局 |
| kernel/initrd payload | `.img` 内自带的 arm64 内核与 initrd 段（footer bit0/bit1），供 PC 端启动（R-16） |
| identity | 镜像的发行版标识，决定切换时是否必须重置数据盘 |
| 同步点 | `Ready!` 控制台标记 → `VmState.Running`（上游 `BootStageDetector`） |
| boot_id | 单次启动唯一标识（`yyyyMMddTHHmmss-6hex`），贯穿 meta.json 与日志行（§16.3） |
| console.log | 最近一次启动的 guest 控制台原始流（契约路径，`boot-test.sh` 依赖） |
| image.log | 镜像生命周期结构化日志（JSONL）：下载/导入/校验/激活/重置（§16.5） |

## 附录 B：参考

- 上游 VM 功能：`ExTV/Podroid`（`CLAUDE.md`、`engine/`）
  —— **行号引用已核对：`ExTV/Podroid` `main` @ `ce0121896b2895637ab1f656cbb0d75fa2874489`
  （2026-09-21），R2 由 D 评审者逐行复验通过（R1: B-R1-20 / R2: D-R2-30）**
- 启动契约：`ltbkq/Podroid-Debian/docs/COMPAT.md`
- Alpine→Debian 差异：`ltbkq/Podroid-Debian/docs/DELTAS.md`
- 阶段计划与 APK 重建记录：`ltbkq/Podroid-Debian/docs/PLAN.md`
- 镜像字节级规格：本仓库 [IMAGE-FORMAT.md](IMAGE-FORMAT.md)
- 评审规程与轮次记录：[REVIEW.md](REVIEW.md) · [reviews/R1.md](reviews/R1.md) · [reviews/R2.md](reviews/R2.md) · [reviews/R3.md](reviews/R3.md)（**冻结轮**）
