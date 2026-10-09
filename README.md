# VMDroid  ㊣永远的骆驼 (默认密码 123)

**软件与系统分离的 Android 虚拟机** —— 虚拟机是独立软件，系统是一个可更换的 `.img` 文件。

> **语言说明 / Language**：本项目文档以**简体中文为准（主）**，
> 英文版 [README.en.md](README.en.md) 仅为**辅助翻译**，可能滞后；
> 两者若有歧义，**一律以中文版为准**。
> The Chinese docs are the **primary** source; the English README is a
> **secondary** translation and may lag. In case of conflict, the Chinese wins.

规格与设计 [docs/DESIGN.md](docs/DESIGN.md) · 镜像格式 [docs/IMAGE-FORMAT.md](docs/IMAGE-FORMAT.md) · 评审规程 [docs/REVIEW.md](docs/REVIEW.md) ·
测试与整改 [docs/TESTPLAN-system-images.md](docs/TESTPLAN-system-images.md) / [docs/FIXLIST-system-images.md](docs/FIXLIST-system-images.md)

**下载**：[APK Release `v1.3.0`](https://github.com/ltbkq/vmdroid/releases/tag/v1.3.0) · [系统镜像 Release `system-images-2026.10.08`](https://github.com/ltbkq/vmdroid/releases/tag/system-images-2026.10.08)

## 核心目的

1. **软件与系统分开** —— VMDroid APK（**v1.3.0**，≈47 MB）只含虚拟机（引擎、内核、initrd、终端、VNC、桥接），
   **不含任何发行版 rootfs**。
2. **系统可以更换** —— 系统是一个独立的 `.img` 文件，换系统 = 换文件，
   不重装应用、不改应用代码。新系统**只上架镜像，应用零更新**。

```
┌────────── VMDroid APK (v1.3.0 · ≈47 MB) ────┐      ┌─── 系统镜像 .img (185–543 MB) ─┐
│ VM 引擎(QEMU/AVF) │ 内核+initrd │ UI         │      │ rootfs.squashfs  → vdb (只读)   │
│ 终端/VNC/USB/桥接 │ ★镜像管理器 + 签名目录     │ ───▶ │ + kernel/initrd → PC 可启动      │
└──────────────────────────────────────────────┘      │ + manifest + 校验和 (尾部)       │
                                                      └──────────────────────────────────┘
```

## 快速开始

1. 装 APK：Release [`v1.3.0`](https://github.com/ltbkq/vmdroid/releases/tag/v1.3.0) → `vmdroid-1.3.0-debug.apk`
   （包名 `io.github.ltbkq.vmdroid.debug`，`versionCode 34`，内置 QEMU 11.0.4）。
2. 打开应用的 **镜像页**：目录自动列出下方 5 个镜像（`catalog.json` + `catalog.json.sig`，
   **ECDSA P-256 验签，失败即拒载**）→ 下载或「从文件导入 `.img`」→ 一键激活。
3. **Start VM** → 等 `Ready!` → 用内置终端或 SSH：`root` / `123`、`ltbkq` / `123`（免密 sudo）；
   guest 端口 22，宿主 9922。
4. 不想装机先验镜像：Linux PC 上 `tools/pc-run.sh <img>` 一条命令进 `Ready!`。

## 已发布系统镜像（Release `system-images-2026.10.08`）

| `image_id` | 资产 | 体积 | 包管理 | 内置开发工具 | PC `Ready!` |
|---|---|---|---|---|---|
| `alpine-3.24-arm64` | [`alpine-minimal.img`](https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/alpine-minimal.img) | 184,557,568 B | apk | — | 29.7s |
| `ubuntu-noble-arm64` | [`ubuntu-minimal.img`](https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/ubuntu-minimal.img) | 238,034,944 B | apt/dpkg | — | 55.3s |
| `debian-minimal-arm64` | [`debian-arm64-build1225.img`](https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/debian-arm64-build1225.img) | 472,915,968 B | apt/dpkg | gcc/g++/make/git | 83.0s |
| `fedora-44-arm64` | [`fedora-minimal.img`](https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/fedora-minimal.img) | 381,689,856 B | dnf/rpm | gcc/g++/make/git | 146.8s |
| `arch-rolling-arm64` | [`arch-minimal.img`](https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/arch-minimal.img) | 543,170,560 B | pacman | — | 67.9s |

> 时间为 PC（`qemu-system-aarch64`）实测到 `Ready!`；五个镜像的 PC 冒烟、SSH 登录与契约标记全部通过。

| 项 | 规格 |
|---|---|
| 内容 | systemd/OpenRC + 网络 + SSH + **Xvnc/pulseaudio（X11 与音频契约）**；**不预装** 桌面环境 / 容器栈（debian、fedora 另带开发工具，其余按需 `apt/dnf/pacman/apk install`） |
| SSH | guest **默认端口 22**（链路 `adb forward tcp:9922 tcp:9922` → 手机 9922 → guest 22） |
| 账户 | `root` / 密码 `123`，**`ltbkq` / 密码 `123`（免密 sudo）** —— 两者均可 SSH 登录 |
| 验收 | `tools/pc-run.sh --smoke <img>` 轮询 console 到 `Ready!` + 双账户 SSH 登录成功；真机用 `tools/boot-test.sh` |
| 获取系统 | 应用内目录**下载**（断点续传 + sha256 校验）· 或**从文件导入**——**仅 `.img` 格式**（其他格式用 `mkimg.sh` 封装） |
| 首次运行 | 无镜像 → **引导页**（下载/导入），启动按钮隐藏，不会启动挂载失败的死机（[DESIGN §8.4](docs/DESIGN.md)） |
| **PC 启动** | `.img` 自带 kernel/initrd，Linux PC 上 `tools/pc-run.sh <img>` 一条命令进入 `Ready!`（依赖 `apt install qemu-system-arm python3 e2fsprogs ssh sshpass`） |
| 启动镜像 | Home 页**启动镜像选择控件**，一键切换已安装的 `.img`（[DESIGN §8.2](docs/DESIGN.md)） |
| 恢复出厂 | 清零数据盘 `storage.img`，guest 下次开机自动重建（无需种子，[DESIGN §4.3](docs/DESIGN.md)） |
| 日志诊断 | **历史启动不丢**（`boots/` 归档 + 阶段耗时 + 失败原因），一键导出诊断包（[DESIGN §16](docs/DESIGN.md)） |

同 `identity`（如 `debian:trixie`）的镜像之间切换**不重置数据盘**；
跨发行版切换时应用提示重置一次。

**目录与签名**：`catalog.json` + `catalog.json.sig`（ECDSA P-256 + SHA-256，签名对象为原始字节），
公钥内置 APK（`res/raw/catalog_pub.pem`），验签失败一律拒绝加载；重新生成用 `distro-build/mkcatalog.sh`。
**体积**：2026-10-08 起不再受 ≤150 MB 约束（见 `docs/reviews/IMPLEMENTATION-NOTES.md`），
仅 `fedora-minimal.img` 保留 **≤500 MB** 硬校验。

## 构建与测试

```sh
git clone https://github.com/ltbkq/vmdroid.git && cd vmdroid
./build-all.sh apk        # 内核/initrd/QEMU/APK 全量构建（需 Docker + Android SDK/NDK）
./local-test.sh smoke     # 本地闭环：构建 → 装机 → 启动 → 截图与日志取证（需模拟器或真机）
```

回归套件（发布门禁）：

| 套件 | 结果 |
|---|---|
| `./gradlew testDebugUnitTest` | 564 例 / 0 失败 |
| `java -jar codec-selftest/out/selftest.jar` | 47/47 |
| `java -jar systemimage-selftest/out/selftest.jar` | 56/56 |
| `tools/selftest.sh`（imgboot 启动库） | 64/64 |
| 5 镜像 PC 冒烟 `tools/pc-run.sh --smoke <img>` | 5/5 通过 |

## 仓库结构

单一分支 `master`（2026-10-09 由原「文档」`master` 分支与「代码」`app` 分支合并而来）：

```
app/                    应用模块（Kotlin/Compose）
terminal-emulator/      终端引擎（vendored Termux）
terminal-view/          终端 UI
docs/                   DESIGN · IMAGE-FORMAT · REVIEW · reviews/ · TESTPLAN · FIXLIST · guide/ · screenshots/
tools/                  pc-run.sh（PC 启动）· selftest.sh（启动库自测）· lib/
codec-selftest/         镜像编解码 + BootGuard/reset 决策表自测
systemimage-selftest/   导入/校验/轮转/回滚 E2E 自测
build-all.sh            内核 / initrd / QEMU / APK 全量构建
local-test.sh           本地「构建 → 装机 → 启动 → 取证」脚本
```

镜像本体由本地 `distro-build/` 流水线构建（不入库），以 Release 资产发布。

## 与上游的关系

- VM 功能 **1:1 参照 [ExTV/Podroid](https://github.com/ExTV/Podroid)**（GPLv2）：
  `VmEngine` / `QemuEngine` / `AvfEngine`、socket 布局、启动契约、host bridge、
  终端、VNC、USB 直通全部沿用，仅把"系统从哪来"改为镜像管理器。
- 系统镜像由本地流水线构建并发布在**本仓库 Releases**；
  [`ltbkq/Podroid-Debian`](https://github.com/ltbkq/Podroid-Debian) 保留作 Debian 镜像的构建参考。
- 许可证：GPL-2.0-or-later（继承上游）。

## 状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 设计评审（R1–R4）+ P0 spike（尾随数据真机直挂 + PC 启动计时） | ✅ 完成 |
| M1 | fork 上游 → 改名/包名 → 移除 rootfs 资产 | ✅ 完成（可安装，Home 四态 + `§16` 日志） |
| M2 | `.img` 编解码 + `mkimg.sh` + `pc-run.sh` + 账户规范 | ✅ 完成（5 个镜像全部构建并通过 PC 冒烟） |
| M3 | 镜像管理：导入/校验/激活/删除/BootGuard + 启动镜像选择控件 | ✅ 完成（`systemimage-selftest` 56/56 覆盖 §13.2） |
| M4 | 镜像目录 + 断点续传 + 通知进度 + 镜像页 | ✅ 完成（目录签名链 2026-10-09 闭环） |
| M5 | 恢复出厂（清零重建 `storage.img`） | ✅ 已实现（镜像页 Factory reset），专项回归待跑 |
| M6 | 真机双后端矩阵回归 + 诊断 UI + 全站四态审计 | 🔄 进行中（本地回归套件全绿，真机矩阵待跑） |
| M7 | Release、签名、catalog + `.sig`、文档 | ✅ 基本完成（`v1.3.0` 已发布；CI 待补） |

详见 [docs/DESIGN.md §14](docs/DESIGN.md)。
