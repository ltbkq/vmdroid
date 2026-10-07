# VMDroid

**软件与系统分离的 Android 虚拟机** —— 虚拟机是独立软件，系统是一个可更换的 `.img` 文件。

[English](README.en.md) | **简体中文** | 设计文档 [docs/DESIGN.md](docs/DESIGN.md) · 镜像格式 [docs/IMAGE-FORMAT.md](docs/IMAGE-FORMAT.md)

## 核心目的

1. **软件与系统分开** —— VMDroid APK（≈70 MB）只含虚拟机（引擎、内核、initrd、终端、VNC、桥接），
   **不含任何发行版 rootfs**。
2. **系统可以更换** —— 系统是一个独立的 `.img` 文件，换系统 = 换文件，
   不重装应用、不改应用代码。后续新系统**只上架镜像，应用零更新**。

```
┌────────── VMDroid APK (~70 MB) ──────────┐      ┌─── 系统镜像 .img (~150 MB) ──┐
│ VM 引擎(QEMU/AVF) │ 内核+initrd │ UI      │      │ rootfs.squashfs  → vdb (只读) │
│ 终端/VNC/USB/桥接 │ ★镜像管理器            │ ───▶ │ + 可选 ext4 种子 → vda (出厂)  │
└───────────────────────────────────────────┘      │ + manifest + 校验和 (尾部)     │
                                                   └──────────────────────────────┘
```

## 首发系统：`debian.img`（最小化）

只做一个系统文件，**Debian 13 (trixie) arm64 最小化安装**，目标 ≤ 150 MB。

| 项 | 规格 |
|---|---|
| 内容 | systemd + 网络 + SSH + 启动契约脚本；**不预装** 桌面 / Xvnc / 容器栈 |
| SSH | guest **默认端口 22**（宿主 `adb forward tcp:9922 tcp:22`） |
| 账户 | `root` / 密码 `123`，**`ltbkq` / 密码 `123`（免密 sudo）** —— 两者均可 SSH 登录 |
| 验收 | `tools/boot-test.sh` 轮询 console 到 `Ready!`；双账户 SSH 登录成功 |
| 后续 | `debian-desktop.img` / `debian-containers.img` / `ubuntu.img` … 只加镜像，不改应用 |

同 `identity`（如 `debian:trixie`）的镜像之间切换**不重置数据盘**；
跨发行版切换时应用提示重置一次。

## 仓库结构

```
docs/
  DESIGN.md          详细设计文档（架构、镜像生命周期、下载/导入、能力协商、里程碑）
  IMAGE-FORMAT.md    .img 字节级格式规格（footer / manifest / 校验规则）
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
| M0 | 设计评审 + P0 spike（尾随数据直挂 QEMU/AVF 真机验证） | **进行中** |
| M1 | fork 上游 → 改名/包名 → 移除 rootfs 资产 | 未开始 |
| M2 | `.img` 编解码 + `mkimg.sh` + 账户规范落地 | 未开始 |
| M3–M7 | 镜像管理 / 下载 / 回归 / 发布 | 未开始 |

详见 [docs/DESIGN.md §14](docs/DESIGN.md)。
