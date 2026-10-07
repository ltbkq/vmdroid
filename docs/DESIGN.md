# VMDroid 设计文档

**软件名：VMDroid** —— 一个独立的 Android 虚拟机应用 + 可插拔的系统镜像（`.img`）。

| | |
|---|---|
| 状态 | 草案 v0.1（2026-10-07） |
| 关联仓库 | 本仓库 `ltbkq/vmdroid`（应用） · [`ltbkq/Podroid-Debian`](https://github.com/ltbkq/Podroid-Debian)（系统镜像构建） · [`ExTV/Podroid`](https://github.com/ExTV/Podroid)（VM 功能上游，GPLv2） |
| 许可证 | GPL-2.0-or-later（继承上游） |
| 目标平台 | Android 8+（API 26+），arm64（aarch64） |
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
- 不改上游内核/initrd（v1 阶段随 APK 分发；格式已预留外置槽位，见 §4.4）。

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
│ 桥接/终端/VNC/USB │ 镜像管理器(新增)       │ ───▶ │ + 可选 persist 种子 (vda 初始) │
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
│     ├ ImageCatalogRepo   目录(JSON)拉取/缓存/签名校验(可选)              │
│     └ BootGuard          无镜像/损坏/需重置 时阻止启动并引导              │
│  firmware/       vmlinuz-virt · initrd.img · qemu/   (APK 资产, 保留)     │
└─────────────────────────────────────────────────────────────────────────┘
                      │ vdb = images/<id>.img   (只读, 零拷贝直挂)
                      │ vda = storage.img       (可写, 应用创建/种子恢复)
                      ▼
┌────────────── Guest（仍由 Podroid-Debian 构建，契约不变）────────────────┐
│ init-podroid: vda→/mnt/persist(ext4)  vdb→/mnt/lower(squashfs)          │
│              overlay → switch_root → /sbin/init(systemd)                │
│ 控制台标记: Loading kernel modules... / Network found /                 │
│            Starting SSH... / Almost ready... / **Ready!**               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.3 磁盘与文件布局

| 设备 | 文件 | 内容 | 所有者 | 生命周期 |
|---|---|---|---|---|
| `vda` | `filesDir/storage.img` | ext4，可写 overlay upper + `docker`/`containers`/`lxc` 绑定 | App 创建（sparse）或镜像种子恢复 | **跨镜像升级保留**；Reset VM / identity 变更时重建 |
| `vdb` | `filesDir/images/<image_id>.img` | `.img` 文件（squashfs 起始，可尾随种子）；**仅 `.img` 格式**（N4） | 镜像管理器 | 随镜像安装/删除/激活变化 |
| — | `filesDir/vmlinuz-virt`, `initrd.img`, `qemu/` | VM 固件 | APK 资产提取（3 项，不再有 rootfs） | 随 APK 升级 |

**命名基线（R1: A-R1-6，全仓统一）**

| 场合 | 取值 |
|---|---|
| 发布文件名（Release 资产 / 用户手上） | `debian.img` |
| `manifest.image.id` = catalog `image_id` | `debian-minimal-arm64`（**不含版本**，稳定标识） |
| 安装后路径 | `filesDir/images/debian-minimal-arm64.img` |
| 变体系列 | `debian-minimal-arm64` / `debian-desktop-arm64` / `debian-containers-arm64`（同 `identity=debian:trixie`） |

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
3. **必须支持可选的 vda 初始种子**（工厂预置 ext4），用于"恢复出厂镜像"。
4. **单文件、可断点、可流式校验**。
5. **PC 端自包含可启动（R-16/G9）**：`.img` 内必须含 **kernel + initrd payload**，
   Linux PC 上 `qemu-system-aarch64` 一条命令启动到 `Ready!`，不依赖 APK/Android。

### 4.2 选定方案：squashfs 前置 + 尾随段 + footer

```
┌────────────────────── .img 文件（单文件） ──────────────────────┐
│ 0x0                rootfs.squashfs (hsqs superblock 在 0x0)     │  ← 直接当 vdb 挂载
│ ...squashfs 结束                                                 │
│ [1 MiB 对齐]     persist 种子 ext4（可选，flags.bit0）            │  ← 恢复出厂时提取成 storage.img
│ [1 MiB 对齐]     kernel payload（arm64 Image，flags.bit1）★R-16  │  ← PC 端 -kernel 直接用
│ [1 MiB 对齐]     initrd payload（flags.bit2）★R-16               │  ← PC 端 -initrd 直接用
│ [1 MiB 对齐]     manifest JSON (UTF-8)                          │
│ 末尾 4096 B      footer（magic + 各段偏移/长度/sha256）           │
└─────────────────────────────────────────────────────────────────┘
```

**为什么可行**：squashfs 的 superblock 在文件 0x0，块表用绝对偏移，
内核只读到 `superblock.bytes_used` 为止，**尾随数据天然被忽略** —— 因此
`.img` 本身就是一个合法的 squashfs，可以零拷贝直挂为 `vdb`。

| 备选方案 | 结论 | 原因 |
|---|---|---|
| A. squashfs + 尾随段（选定） | ✅ | 零拷贝直挂；同时能携带种子与元数据；无需改 initramfs |
| B. 容器格式，安装时拆成两个文件 | ⚠️ 备用 | 需在安装时多写 ~300 MB、峰值双份占用；**作为 A 的降级路径保留**（见 §4.3） |
| C. GPT 分区裸盘（p1=ext4, p2=squashfs） | ❌ | `init-podroid` 直接 `mount /dev/vdb`，不认分区；改它违反 G4 |
| D. QEMU `-blockdev` slice（offset/size） | ⚠️ 后续优化 | QEMU 可行但 AVF/crosvm 无对应能力，两后端会不对称 |
| E. `.img` 就是裸 squashfs（无 footer） | ❌ **不支持** | 无 manifest 则无 identity/版本/校验，无法做升级与重置决策（**需求 N4：只收 `.img`**）；现有 `.squashfs` 产物须经 `mkimg.sh` 封装 |

### 4.3 两种消费模式

| 模式 | 触发条件 | 行为 |
|---|---|---|
| **direct**（默认，首选） | 文件是合法 `.img`（尾部 footer 校验通过） | 验证通过后 **原地挂载**，零额外写入 |
| **extract**（仅种子/降级） | 用户执行"恢复出厂"；或尾部 spike 验证失败 | 只提取 **persist 种子** → `storage.img`（稀疏写出）；`rootfs` 段仍在 `.img` 内直挂 |

> **N4 约束**：启动镜像**只支持 `.img` 格式**。无 footer 的裸 `.squashfs`、
> `.tar*`、其他项目镜像**一律拒绝**并提示用 `mkimg.sh` 封装。
> 这使 identity 恒有 manifest 来源，消灭了 §5.2 的"两套 identity"问题（R1: B-R1-5）。

应用在 P0 阶段做一次真机 spike 判定 `direct` 是否可用（QEMU + AVF 各验一次）；
不可用则落到 `extract` 模式：**`.img` 拆出 `rootfs` 独立文件 + 删除 `.img` 中冗余部分不可行**
→ 此时磁盘占用为 2×，须在 M0 一并决策（R1: B-R1-17）。

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

首发**只做一个**系统文件，命名固定为 `debian.img`（目录中的 `image_id`
建议 `debian-minimal-arm64`，`identity = debian:trixie`）。

**定位**：能开机、能进终端、能联网的**最小可用 Debian**，其余功能按需后加。

| 维度 | 首发 `debian.img` | 对比当前 `Podroid-Debian` 全量构建 |
|---|---|---|
| 包数量 | 目标 ~90–120（minbase + 契约组件） | 283 |
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
ltbkq  密码 123   # 日常账户，wheel/sudo 组，NOPASSWD
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
   # ★ host 私钥不得烘焙进镜像（R1: B-R1-2）：
   rm -f /etc/dropbear/dropbear_*_host_key*
   #   首次启动由 podroid-bootstrap 检测缺失并 dropbearkey -t ed25519,rsa 生成
   #   （/etc 走 overlay，copy-up 到 persist；或 dropbear 以 -R 延迟加载）
   ```
   写入点与现有 `build/rootfs-finalize.sh`（当前只设 root 密码）合并。
6. manifest 声明（应用据此在 UI 显示"SSH 登录帮助"，见 §8.3）：
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
| `ABSENT` | 未安装任何镜像 | Home 禁用启动按钮；进入引导页（下载/导入） |
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

规则（按顺序判定）：

1. **内容优先**：`new.rootfs_sha256 == active.rootfs_sha256` → 视为同一系统，**永不**重置。
2. `identity = manifest.image.identity`（例如 `debian:trixie`，跨版本稳定）；
   **N4 后 identity 恒来自 manifest**（只收 `.img`），不再有 `sha256:` 形式。
3. **硬判据**：`contract.version` 或 `distro.init` 与当前不同 → 即使 identity 相同也进入 `RESET_REQUIRED`。
4. 激活时判定为需重置 → 进入 `RESET_REQUIRED`，用户确认后由应用**重建 `storage.img`**，再激活。
5. 防御纵深（可选，M6）：`podroid.image_identity=` 加入内核 cmdline；guest 侧比对逻辑
   **由 Podroid-Debian 构建侧新增 `/etc/podroid/identity` + `podroid-migrate` 比对**
   （属镜像内容而非契约改动，故不违反 G4），VMDroid 只负责下发 cmdline。

### 5.3 激活与回滚

- `images/active.json` 记录 `{image_id, identity, sha256, activated_at}`。
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
  `tools/mkimg.sh --manifest … -o debian.img`（秒级追加，见 §11.2）。

---

## 6. 分发：目录、下载、导入

### 6.1 镜像目录（catalog）

托管在 GitHub Releases（默认订阅 `Podroid-Debian` 的 release），应用内可改订阅 URL。

```jsonc
// GET https://github.com/ltbkq/Podroid-Debian/releases/latest/download/catalog.json
{
  "schema": 1,
  "generated_at": "2026-10-07T12:00:00Z",
  "publisher": { "name": "Podroid-Debian", "ed25519_key": "base64..." },
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
      "size": 155_189_248,
      "sha256": "…64 hex…",
      "app_min_version_code": 1,
      "notes": "最小化：systemd + dropbear(root/123, ltbkq/123) + Xvnc/pulseaudio；无容器栈"
    }
  ]
}
```

> `image_id` 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`（**R1: B-R1-16**，防止 QEMU
> `-drive` 选项注入：路径中出现 `,` 会截断选项）。不匹配的条目在导入/下载落盘前拒绝。
> `size` 示例满足 footer 对齐：`file_size = footer_offset + 4096`，`footer_offset` 为 1 MiB 边界。
> `app_min_version_code` 与 manifest 内 `app.min_version_code` **同义**（catalog 扁平 / manifest 嵌套）。

- **信任模型 v1**：HTTPS + 目录内 `sha256`（下载后再校验一遍，防 CDN/传输篡改需目录签名）。
- **信任模型 v2（M7）**：`publisher.ed25519_key` 内置 APK，对目录 JSON 做
  Ed25519 detached signature（minisign 风格），签名不对则拒绝加载目录。

### 6.2 下载（断点续传）

```
ImageDownloader
  1. 预检：可用空间 ≥ size + 余量（StorageManager），否则 STORAGE_FULL
  2. HEAD/Range: bytes=<downloaded>-   → 写 images/<id>.img.part
     - 服务端 206 → 续传；416/无 Range 支持 → 从 0 重来
     - ETag/Last-Changed 变化 → 丢弃 .part 重下
  3. 每块同时喂 sha256（无需第二遍读文件）
  4. 完成后 sha256 == catalog.sha256？→ 否：删除 .part，标记 CORRUPT，可重试
  5. fsync + rename(.part → .img) + 写 .meta.json → INSTALLED
  6. 断电安全：.part 永不冒充成品（沿用上游"原子 rename"思想，见
     PodroidApplication.copyAssetAtomically, :219-236）
```

- 进度上报到通知栏 + UI；进程被杀后**下次进入应用自动续传**。
- 移动网络默认提示（可设置"仅 Wi-Fi 下载"）。

### 6.3 手动导入（SAF）

```
用户: 设置 → 系统镜像 → 从文件导入 → ACTION_OPEN_DOCUMENT(*/*)
  1. content:// URI 读取 size（未知则边读边扩展）
  2. 流式拷贝到 images/<name>.img.part，同一遍计算 sha256 / 解析 footer
  3. 校验 footer.manifest_sha256（有 footer 时）
  4. 空间预检（拷贝前先查 size，减少半途失败）
  5. 原子 rename → .meta.json → INSTALLED
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
| `QemuEngine.kt:558-573` | `storage.img` 逻辑 **不变**（种子恢复在镜像管理器里做，不进引擎） |
| `QemuEngine.kt:532-533, 552-556` | 内核/initrd **不变**（仍来自 APK 资产） |
| `QemuEngine.kt:537-547` | cmdline 不变（`console=ttyAMA0`、`podroid.*`）；可选追加 `podroid.image_identity=`（§5.2） |
| `QemuEngine.kt:585-620` | 9p 下载共享、SLIRP 端口转发 **不变** |

### 7.2 AvfEngine（上游 `engine/avf/AvfEngine.kt`）

| 位置 | 改动 |
|---|---|
| `AvfEngine.kt:1027-1028` | `File(filesDir,"alpine-rootfs.squashfs")` + `require(exists)` → `systemImageRepo.activeRootfsPath()`；错误转成 UI 可读状态而非 `IllegalArgumentException` |
| `AvfEngine.kt:1084-1085` | `addDisk(storage, writable=true)` / `addDisk(squashfs, writable=false)` 的路径来源改为仓库 |
| `AvfEngine.kt:929-962` | `ensureStorageImage()` **不变**（sparse 创建、只增不减、`resize2fs` 由 guest 做） |

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

### 7.4 无镜像 / 异常态（BootGuard）

| 条件 | 行为 |
|---|---|
| 未安装镜像 | 引导页：**下载推荐镜像** / **从文件导入**；Home 启动按钮禁用 |
| `.meta.json` 校验失败 | `CORRUPT`：重新下载 / 删除重导 |
| `app_min_version_code > 当前版本` | 拒绝激活，提示升级应用 |
| `arch != arm64` / `format_version` 过新 | 拒绝激活 |
| `storage.img` 缺失 | 由 `ensureStorageImage()` 正常创建（现状行为） |
| 激活时 identity 不匹配 | `RESET_REQUIRED` 对话框（§5.2） |

---

## 8. UI / UX

### 8.0 UI 设计原则（美观性与合理性，需求 N2）

> 本节是**评审基准**：任何新增/修改页面都要逐条过一遍，R2 起由评审者对照验收。

**① 栅格与间距（8dp 基准，禁止散值）**

| 项 | 规定 |
|---|---|
| 设计系统 | **Material 3**（上游已用 Compose + M3，不引入第二套） |
| 间距序列 | 仅 `4 / 8 / 16 / 24 / 32 dp`，禁止 13dp、17dp 等散值 |
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
| 仅 1 个镜像 | **仍显示**选择器（布局稳定），下拉内含"导入/下载"入口 |
| 校验中 | 20dp `CircularProgressIndicator` + "校验中"，控件禁用 |
| `CORRUPT` 项 | 标红 + ⚠；选中后主按钮变为"重新校验 / 重新下载" |
| 完全无镜像 | 整块替换为空态双入口；**启动按钮隐藏**（不显示必然失败的按钮） |
| 加载失败（目录/列表异常） | 选择器保留上次值 + 顶部 inline error，不塌陷布局 |

**为什么放 Home**：换系统后用户要立即看到"现在跑的是哪个"；Home 是启动动作发生地，
选择与启动同视野（就近原则）；放设置页会造成状态不可见。

### 8.3 系统镜像管理页（Images）

```
┌─ 系统镜像 ────────────────────────────────┐
│ ● 使用中  Debian 13 (trixie) · 最小化      │
│   debian-minimal-arm64 · 148 MB            │
│   debian:trixie · system_version 34        │
│   [立即校验] [恢复出厂(用种子)] [移除]       │
│                                            │
│ ○ 已安装  Debian 13 (trixie) · 桌面版       │
│   debian-desktop-arm64 · 312 MB            │
│   [设为启动镜像] [校验] [移除]               │
│                                            │
│ ── 在线目录 · Podroid-Debian ──            │
│   Debian 13 (trixie) · 最小化    v34        │
│   148 MB · sha256 ✓              [下载]    │
│   ▓▓▓▓▓▓▓░░░ 68% · 4.2 MB/s · 剩 12s      │
│                                            │
│ [从文件导入 .img…]    [刷新] [订阅设置]      │
└────────────────────────────────────────────┘
```

**排版规则**：卡片 = 已安装镜像（可操作），列表行 = 在线目录（只读 + 下载）；
两类用**分区标题 + 分隔线**区分，不混排（避免"哪个已装哪个没装"歧义）。

### 8.4 首次启动（无镜像）

```
启动 App → BootGuard: ABSENT
  → Setup 第 2 步（复用上游 setup wizard 逻辑）
      ① 下载推荐镜像（默认选中，显示体积/网络提示）
      ② 从文件导入（**仅 `.img`**；其他格式提示用 `mkimg.sh` 封装）
      ③ 稍后再说（Home 可浏览，启动按钮禁用）
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

---

## 9. 关键时序

### 9.1 首次启动下载

```
User → ImageManager: 下载 Debian
CatalogRepo → HTTPS GET catalog.json → 结构/sha256 校验（v2 起含 Ed25519 签名）→ 展示列表
ImageDownloader:
   预检空间 → GET .part(0-) → [写盘 + sha256]n 块 → 完成
   → sha256 比对 → fsync → rename → meta.json → INSTALLED
ImageManager: identity 比对(首个镜像=无冲突) → 激活 → ACTIVE
User → Home: 启动镜像选择控件(§8.2) → 选中 debian-minimal-arm64
BootGuard: .img 存在 + meta 一致 + arch/format/app 版本/ssh_port OK → PASS
VmdroidService → EngineHolder → QemuEngine.start()
   -kernel filesDir/vmlinuz-virt  -initrd filesDir/initrd.img
   -drive storage.img(vda,rw)     -drive images/debian-minimal-arm64.img(vdb,ro)
guest: init-podroid → overlay → systemd → markers → Ready!
BootStageDetector: "Ready!" → VmState.Running → 终端自动连接
```

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
| S2 | TLS + 目录签名 | v1 HTTPS+sha256；v2 目录 Ed25519 签名（公钥内置） |
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
| `ltbkq/vmdroid`（本仓库） | VM 应用 + 镜像管理 | `vmdroid-<ver>.apk`（≈70 MB）、镜像格式**解析/校验**（Kotlin `VmdImageCodec`，与 `mkimg.sh` 互操作） |
| `ltbkq/Podroid-Debian` | 构建 Guest rootfs、打包 `.img` | `*.img`、`catalog.json`、Release 资产 |
| `ExTV/Podroid` | 上游 VM 功能 | fork 源头 |

`Podroid-Debian` 侧需要新增（不改现有流水线）：

```sh
tools/mkimg.sh --rootfs out/debian-rootfs.squashfs \
               --manifest manifest.json \
               [--seed seed.ext4] \
               -o out/debian.img              # 发布名 debian.img（命名基线见 §2.3）
tools/catalog.sh ... > catalog.json           # 生成目录
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

**依赖**：`qemu-system-aarch64`（`apt install qemu-system-arm`）、`python3`（仅用于提取段）。

**提供两种启动方式**（任选，等价）：

```sh
# 方式 A（推荐）：随仓库脚本 —— 读 footer 提取 kernel/initrd 到临时目录再起 QEMU
tools/pc-run.sh debian.img

# 方式 B：手工（等价命令，也用于文档自证）
python3 - <<'EOF'   # 或用 dd 按 footer 偏移提取
import struct,sys
f=open("debian.img","rb"); f.seek(-4096,2); ft=f.read(4096)
ko,ks=struct.unpack_from("<QQ",ft,172); io_,isz=struct.unpack_from("<QQ",ft,220)
# 提取 kernel -> vmlinuz，initrd -> initrd.img
EOF

qemu-system-aarch64 \
  -M virt,gic-version=3 -cpu max -accel tcg,thread=multi \
  -smp 4 -m 4096 \
  -kernel vmlinuz -initrd initrd.img \
  -append "console=ttyAMA0 mitigations=off androidip=10.0.2.15 podroid.x11.dpi=96" \
  -drive file=storage.img,if=none,id=drive1,format=raw,discard=unmap,detect-zeroes=unmap \
  -device virtio-blk-pci,drive=drive1 \
  -drive file=debian.img,if=none,id=drive2,format=raw,readonly=on \
  -device virtio-blk-pci,drive=drive2 \
  -netdev user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:9922-:22,hostfwd=tcp:127.0.0.1:5900-:5900 \
  -device virtio-net-pci,netdev=net0
```

**要点**

| 项 | 说明 |
|---|---|
| 内核来源 | **`.img` 自带**（footer `kernel_*`），与 APK 内置者**同源同版本**（发布流水线一次构建两处使用） |
| `vda` | 脚本首次运行 `truncate + mkfs.ext4` 创建 `storage.img`（**不用** seed，seed 只给 Android 恢复出厂）；已存在则复用 |
| 启动契约 | 完全相同：`init-podroid` 挂 `vda`/`vdb` → overlay → `Ready!`（PC 端同样可用 `adb`-外的 `ssh -p 9922` 验证） |
| 架构 | x86_64 PC 走 **TCG**（慢但可用）；arm64 Linux 主机可加 `-accel kvm -cpu host` 提速 |
| 控制台 | `-serial mon:stdio` 或脚本默认落 `console.log`；`Ready!` 是验收断言 |
| 端口 | 只绑回环 `127.0.0.1`，与 §10.1 一致；SSH 仍 `ssh -p 9922 ltbkq@localhost`（pw `123`） |
| 不承诺 | PC 端**不做**图形前端、不做 USB/9p 透传；X11/VNC `:5900` 可连但需自行起 viewer |

**验收（R-16）**：`tools/pc-run.sh debian.img` 在 x86_64 Ubuntu/Debian 上
**60 秒内**输出 `Ready!`，且 `ssh -p 9922 ltbkq@127.0.0.1`（pw `123`）成功。

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
  构建 rootfs → `mkimg` → 计算 sha256 → 上传 Release → 生成 `catalog.json`。

### 12.3 发布物

| 产物 | 位置 | 说明 |
|---|---|---|
| `vmdroid-<ver>.apk` | 本仓库 Release | ≈70 MB |
| `debian.img`（首发最小化，**含 kernel/initrd payload**） | Podroid-Debian Release | **≤150 MB**（含内核 ~150–170 MB 上限），单文件系统，PC 可启动（R-16） |
| 后续 `debian-*.img` / `ubuntu.img` | 同上 | 按 §4.7 路线图逐个上架 |
| `catalog.json`(+ `.sig`) | 同上 `latest/download/` | 应用默认订阅 |
| 源码 | 两仓库 | GPLv2 §6/§63 合规（源码 + 构建说明） |

> 全量构建（345 MB / 329 MiB，含容器栈 + 桌面）仅作体积对比基线，非首发发布物。

---

## 13. 测试与验收

### 13.1 自动化

| 层 | 内容 | 归属 |
|---|---|---|
| 构建产物 | **APK 资产清单**：仅 `vmlinuz-virt`/`initrd.img`/`qemu/`，**无 `*.squashfs`**，体积 ≤ 80 MB（G1/R-01） | vmdroid CI |
| **PC 冒烟（R-16）** | `mkimg` 自检：提取 `.img` 内 kernel/initrd → 起 `qemu-system-aarch64` 60s 断言 `Ready!`（`pc-boot-smoke.sh`） | Podroid-Debian CI |
| 单元（JVM） | `VmdImageCodec`：往返编解码、footer 定位、字段缺省、截断/损坏/错位/越界拒绝、`ssh_port≠22` 拒绝；identity 判据全表（§5.2 含内容优先与硬判据）；状态机迁移；流式 sha256 | vmdroid |
| 工具侧 | `mkimg.sh` ↔ Kotlin codec **互操作**；测试向量入库；catalog schema 前向兼容（未知字段/新 image 条目） | vmdroid + Podroid-Debian |
| 下载 | Range 续传（含 416 回退）、ETag 变化重下、`.part` 断点恢复、校验失败清理 | vmdroid |
| 镜像内容 | 镜像检查脚本：`getent passwd ltbkq`、`visudo -c`、dropbear 允许 root 与密码登录、**两账户凭密码 `123` 可认证**（`openssl passwd -6 123` 比对 shadow 或直接以登录成功为真值）、`ss -tln` 断言 guest **:22**、`/etc/dropbear/` **无** `*_host_key` 私钥 | Podroid-Debian CI |
| 最小化负面断言 | dpkg **不含** `docker.io/podman/lxc/xfce4/lightdm`；包数 90–120；`.img ≤ 150 MB` | Podroid-Debian CI |
| 契约指纹 | `.img` 内 `/usr/local/lib/podroid/*`、`podroid-*`、控制台标记串清单比对（防契约漂移） | Podroid-Debian CI |
| 回归（沿用） | `Podroid-Debian/tests/test_dns.sh`、`tools/boot-test.sh`（轮询 `Ready!`；**参数化 `PKG=${1:-io.github.ltbkq.vmdroid.debug}` 并自建 `adb forward tcp:9922 tcp:9922`**，R1: B-R1-8） | Podroid-Debian |

### 13.2 真机冒烟矩阵

| 用例 | QEMU 后端 | AVF 后端 |
|---|---|---|
| 无镜像启动 → 引导页（**不启动死机**） | ✅ 必测 | ✅ 必测 |
| **E2E 换系统**：装 A → 装 B（同 identity）→ 选择控件激活 B → 启动 `Ready!` 且数据保留；再切回 A（回滚）→ `Ready!` | ✅ 必测 | ✅ 必测 |
| **零应用发版**：staging catalog 新增**构建期未知** `image_id` → 同一 APK 拉取/下载/激活/启动（含未知字段前向兼容） | ✅ | ✅ |
| **删除镜像**：删除非激活镜像（文件 + `.meta.json` 清除、激活项不受影响） | ✅ | ✅ |
| 导入**非 `.img`**（裸 `.squashfs`/`.zip`/伪造扩展名）→ 拒绝并给出封装指引 | ✅ | ✅ |
| 导入合法 `.img` → 激活 → `Ready!` | ✅ | ✅（P0 spike） |
| 下载 60% 杀进程 → 续传 → 校验 → 激活 | ✅ | ✅ |
| **空间不足**（`size + 余量` > 可用）→ 下载/导入**写盘前**拦截为 `STORAGE_FULL`，不遗留 `.part` | ✅ | ✅ |
| 损坏镜像（`dd conv=notrunc` 改 1 字节，mtime 变）→ 启动前置重算 → `CORRUPT` 拒绝启动 | ✅ | ✅ |
| 同 identity 升级 → 数据保留 | ✅ | ✅ |
| 换 identity → 强制重置 → 正常启动 | ✅ | ✅ |
| **恢复出厂**：从 `.img` 尾部**稀疏**提取种子 → `storage.img` → `Ready!` 且持久数据回到种子状态 | ✅ | ✅ |
| APK 升级后镜像**不被**重拷（耗时 <5s） | ✅ | ✅ |
| **SSH 双账户登录（guest 端口 22，宿主 9922）**：`ssh root@localhost -p 9922` 与 `ssh ltbkq@localhost -p 9922`（密码 `123`）均成功；guest 内 `ss -tln` 断言 `:22` | ✅ | ✅ |
| `ltbkq` 免密 sudo 可用（`sudo -n true`） | ✅ | ✅ |
| `debian.img`（Xvnc 有、容器无）→ 容器能力降级不报错；X11 连 `:5900` 成功 | ✅ | ✅ |
| **启动镜像选择控件（§8.2）**：运行中禁用 / 空态双入口 / `CORRUPT` 项标红 / 切换后下次启动生效 | ✅ | ✅ |
| **UI 设计原则（§8.0）**：light+dark 双主题、8dp 间距、四态控件、文案走 `values-zh` | ✅ | ✅ |
| **R-16 PC 启动**：`tools/pc-run.sh debian.img`（x86_64 Linux, TCG）→ 60s 内 `Ready!`，`ssh -p 9922 ltbkq@127.0.0.1`（pw `123`）成功 | ✅ 必测 | n/a（PC 用例） |
| 终端 / VNC / 端口转发 / host bridge / USB | ✅ | ✅（USB QEMU 专属） |

### 13.3 P0 风险 spike（先于编码，M0 内）

1. **尾随数据直挂**：现有 `debian-rootfs.squashfs` 追加 4 KiB（含 `VMDIMG01` footer），
   真机 QEMU 后端挂 `/dev/vdb` → 能否正常 mount 并进入 `Ready!`。
2. 同上在 AVF 后端（crosvm）验证。
3. **截断回归**：同一镜像截断 1 字节 → 必须挂载失败（证明"尾随安全、截断危险"）。
4. 若 1 或 2 失败 → 启用 §4.3 `extract` 模式；**同时决策 B-R1-17**（2× 占用 vs 删除 `.img`）。
   > 事实依据（R1: B-R1-21）：内核 `fs/squashfs/super.c` 唯一尺寸校验是
   > `bytes_used ≤ 设备大小`，不检查"设备大于文件系统"；`squashfs-tools` 无 fsck。
   > 已用 `mksquashfs` 产物 + 4 KiB 追加实测 `unsquashfs` 全通过 → **QEMU/AVF 块层接受度**是唯一未知数。

---

## 14. 里程碑

| 阶段 | 内容 | 退出标准 | 预估 |
|---|---|---|---|
| **M0** 规格冻结 | 本设计评审（R1–R3）+ P0 spike（§13.3）+ B-R1-17 占用决策 | spike 结论：direct 可行 / 需 extract 及其占用方案 | 1–2 天 |
| **M1** 骨架 | fork 上游 → 改名/包名/品牌 → 移除 rootfs 资产 → §8.0 UI 基线（双主题/8dp/四态） | APK ≈70 MB 可安装；无镜像时进引导页 | 3 天 |
| **M2** 格式与工具 | `VmdImageCodec` + `mkimg.sh`（含 `--kernel/--initrd`）+ `manifest.json` + `tools/pc-run.sh` + 测试向量 | 互操作测试通过；**R-16 PC 冒烟 `Ready!`**；账户规范（§4.8）在构建脚本落地 | 3 天 |
| **M3** 镜像管理 | 导入/校验/激活/删除/BootGuard/identity 判据 + **§8.2 启动镜像选择控件** | §13.2 中"导入/E2E 换系统/损坏/identity"用例通过 | 3–4 天 |
| **M4** 下载 | 目录 + 断点续传 + 通知进度 + §8.3 镜像页 | 60% 杀进程续传用例通过 | 2–3 天 |
| **M5** 恢复出厂 | **稀疏**种子提取应用到 `storage.img` | Reset 与"用种子恢复"行为正确 | 1–2 天 |
| **M6** 回归 | 真机双后端矩阵 + 契约指纹回归 + guest identity 守卫（可选） | §13.2 全绿 | 2–3 天 |
| **M7** 发布 | CI、签名、Release、catalog、目录 Ed25519 签名、SSH 收紧、文档、license 合规 | 首个公开版本 | 2 天 |

**合计 16–22 个工作日 ≈ 3.5–4.5 周**（不含 AVF 真机排期依赖）。

---

## 15. 开放问题

| # | 问题 | 倾向 |
|---|---|---|
| Q1 | `.img` 直挂（direct）在 AVF/crosvm 上是否可靠？ | P0 spike 决定；不可靠则走 extract 降级 |
| Q2 | 是否允许镜像自带 `kernel`/`initrd` payload？ | **已决（R-16）**：必须自带，`mkimg --kernel/--initrd`，PC 端启动依赖它；Android 端仍用 APK 内置（同源） |
| Q3 | 外部路径直挂（不拷贝到私有目录）是否可行？ | 需验证 scoped storage 下 QEMU/crosvm 打开 `/storage/emulated/0/...` 的能力与性能 |
| Q4 | 已安装镜像数量上限 / 存储配额策略 | 建议 2 个，超出提示删除 |
| Q5 | 启动失败自动回滚（A/B） | v1 不做；先做"手动回滚到另一已装镜像" |
| Q6 | 目录签名（Ed25519）何时启用 | M7 内完成，公钥内置 |
| Q7 | 包名是否沿用 `com.excp.podroid`（便于未来原地升级） | 当前建议新包名；若预期与上游合并需重新讨论 |
| Q8 | `.img` 是否需要加密（Android FBE 已保护私有目录） | 暂不额外加密 |
| Q9 | root 密码 `123` 是否强制首启改密 | **已决**：不强制（§4.8 规定 `root/123` + `ltbkq/123` 为镜像规范），改由回环转发 + Settings 修改入口保证安全 |

---

## 附录 A：术语

| 术语 | 含义 |
|---|---|
| VM / 虚拟机 | 由 QEMU(TCG) 或 AVF(pKVM) 运行的 Guest Linux，内核+initrd 来自 APK |
| 系统镜像 `.img` | 单文件 Guest rootfs（squashfs 前置 + 可选种子 + manifest/footer） |
| vda | 可写持久盘 `storage.img`（ext4），承载 overlay upper 与容器数据 |
| vdb | 只读系统盘（`.img`），挂载为 `/mnt/lower` |
| 启动契约 | `init-podroid` 挂载顺序、控制台标记、tty 角色、端口转发、存储布局 |
| kernel/initrd payload | `.img` 内自带的 arm64 内核与 initrd 段（footer bit1/bit2），供 PC 端启动（R-16） |
| identity | 镜像的发行版标识，决定切换时是否必须重置数据盘 |
| 同步点 | `Ready!` 控制台标记 → `VmState.Running`（上游 `BootStageDetector`） |

## 附录 B：参考

- 上游 VM 功能：`ExTV/Podroid`（`CLAUDE.md`、`engine/`）
  —— **行号引用（`QemuEngine.kt:575` 等）基于 2026-10-07 抓取的 `main` 分支，
  M1 导入上游后须逐条核对并补记 commit hash**（R1: B-R1-20，R2 由 D 评审者执行）
- 启动契约：`ltbkq/Podroid-Debian/docs/COMPAT.md`
- Alpine→Debian 差异：`ltbkq/Podroid-Debian/docs/DELTAS.md`
- 阶段计划与 APK 重建记录：`ltbkq/Podroid-Debian/docs/PLAN.md`
- 镜像字节级规格：本仓库 [IMAGE-FORMAT.md](IMAGE-FORMAT.md)
- 评审规程与轮次记录：[REVIEW.md](REVIEW.md) · [reviews/R1.md](reviews/R1.md)
