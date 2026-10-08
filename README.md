# VMDroid   (默认密码１２３)

**软件与系统分离的 Android 虚拟机** —— 虚拟机是独立软件，系统是一个可更换的 `.img` 文件。

> **语言说明 / Language**：本项目文档以**简体中文为准（主）**，
> 英文版 [README.en.md](README.en.md) 仅为**辅助翻译**，可能滞后；
> 两者若有歧义，**一律以中文版为准**。
> The Chinese docs are the **primary** source; the English README is a
> **secondary** translation and may lag. In case of conflict, the Chinese wins.

设计文档 [docs/DESIGN.md](docs/DESIGN.md) · 镜像格式 [docs/IMAGE-FORMAT.md](docs/IMAGE-FORMAT.md) · 评审规程 [docs/REVIEW.md](docs/REVIEW.md)

## 核心目的

1. **软件与系统分开** —— VMDroid APK（≈70 MB）只含虚拟机（引擎、内核、initrd、终端、VNC、桥接），
   **不含任何发行版 rootfs**。
2. **系统可以更换** —— 系统是一个独立的 `.img` 文件，换系统 = 换文件，
   不重装应用、不改应用代码。后续新系统**只上架镜像，应用零更新**。

```
┌────────── VMDroid APK (~70 MB) ──────────┐      ┌─── 系统镜像 .img (≤150 MB) ──┐
│ VM 引擎(QEMU/AVF) │ 内核+initrd │ UI      │      │ rootfs.squashfs  → vdb (只读) │
│ 终端/VNC/USB/桥接 │ ★镜像管理器            │ ───▶ │ + kernel/initrd → PC 可启动      │
└───────────────────────────────────────────┘      │ + manifest + 校验和 (尾部)     │
                                                   └──────────────────────────────┘
```

## 首发系统：`debian.img`（最小化）

只做一个系统文件，**Debian 13 (trixie) arm64 最小化安装**，目标 ≤ 150 MB。

| 项 | 规格 |
|---|---|
| 内容 | systemd + 网络 + SSH + **Xvnc/pulseaudio（X11 与音频契约）**；**不预装** 桌面环境 / 容器栈 |
| SSH | guest **默认端口 22**（链路 `adb forward tcp:9922 tcp:9922` → 手机 9922 → guest 22） |
| 账户 | `root` / 密码 `123`，**`ltbkq` / 密码 `123`（免密 sudo）** —— 两者均可 SSH 登录 |
| 验收 | `tools/boot-test.sh` 轮询 console 到 `Ready!`；双账户 SSH 登录成功 |
| 获取系统 | 应用内目录**下载**（断点续传 + sha256 校验）· 或**从文件导入**——**仅 `.img` 格式**（其他格式用 `mkimg.sh` 封装） |
| 首次运行 | 无镜像 → **引导页**（下载/导入），启动按钮隐藏，不会启动挂载失败的死机（[DESIGN §8.4](docs/DESIGN.md)） |
| **PC 启动** | `.img` 自带 kernel/initrd，Linux PC 上 `tools/pc-run.sh debian.img` 一条命令进入 `Ready!`（依赖 `apt install qemu-system-arm python3 e2fsprogs ssh sshpass`） |
| 启动镜像 | Home 页**启动镜像选择控件**，一键切换已安装的 `.img`（详见 [DESIGN §8.2](docs/DESIGN.md)） |
| 恢复出厂 | 清零数据盘 `storage.img`，guest 下次开机自动重建（无需种子，[DESIGN §4.3](docs/DESIGN.md)） |
| 日志诊断 | **历史启动不丢**（`boots/` 归档 + 阶段耗时 + 失败原因），一键导出诊断包（详见 [DESIGN §16](docs/DESIGN.md)） |
| 后续 | `debian-desktop.img` / `debian-containers.img` / `ubuntu.img` … 只加镜像，不改应用 |

同 `identity`（如 `debian:trixie`）的镜像之间切换**不重置数据盘**；
跨发行版切换时应用提示重置一次。

## 仓库结构

```
docs/
  DESIGN.md          详细设计文档（架构、镜像生命周期、下载/导入、能力协商、里程碑）
  IMAGE-FORMAT.md    .img 字节级格式规格（footer / manifest / 校验规则）
  REVIEW.md          多 AI 评审规程（角色、轮次、需求基线）
  reviews/           各轮问题单（R1.md、R2.md …）
```

本仓库当前处于**设计阶段**，尚未导入应用代码。

## 与上游的关系

- VM 功能 **1:1 参照 [ExTV/Podroid](https://github.com/ExTV/Podroid)**（GPLv2）：
  `VmEngine` / `QemuEngine` / `AvfEngine`、socket 布局、启动契约、host bridge、
  终端、VNC、USB 直通全部沿用，仅把"系统从哪来"改为镜像管理器。
- 系统镜像由 [`ltbkq/Podroid-Debian`](https://github.com/ltbkq/Podroid-Debian) 构建并发布为 Release 资产。
- 许可证：GPL-2.0-or-later（继承上游）。

## 状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 设计评审（R1–R4）+ P0 spike（尾随数据真机直挂 + PC 启动计时） | **评审完成 R1–R4 ✅**；P0 直挂已实测到 `Ready!`，SSH 登录待复验 |
| M1 | fork 上游 → 改名/包名 → 移除 rootfs 资产 | **完成 ✅**（`app` 分支 8052a9a；app 模块编译通过，382 单测全绿） |
| M2 | `.img` 编解码 + `mkimg.sh`（`--kernel/--initrd`）+ 首个 `debian.img` + `pc-run.sh` + 账户规范 | **工具链已提交**；`debian-minimal.img` 已构建，SSH 登录待复验 |
| M3–M7 | 镜像管理 + 启动镜像选择控件 / 下载 / 恢复出厂 / 回归 / 发布 | 未开始 |

详见 [docs/DESIGN.md §14](docs/DESIGN.md)。
