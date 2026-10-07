# VMDroid

**An Android VM with the software and the OS separated** — the VM is a standalone app,
the system is a swappable `.img` file.

English | [简体中文](README.md) | Design: [docs/DESIGN.md](docs/DESIGN.md) · Image format: [docs/IMAGE-FORMAT.md](docs/IMAGE-FORMAT.md)

## Purpose

1. **Software and system separated** — the VMDroid APK (~70 MB) contains only the VM
   (engines, kernel, initrd, terminal, VNC, bridge) and **no distro rootfs at all**.
2. **The system is swappable** — the OS is one standalone `.img` file. Changing the OS
   means changing the file: no app reinstall, no code change. New systems are shipped
   as **images only; the app never needs an update**.

```
┌────────── VMDroid APK (~70 MB) ──────────┐      ┌─── system image .img (~150 MB) ─┐
│ VM engines (QEMU/AVF) │ kernel+initrd │UI │      │ rootfs.squashfs     → vdb (ro)   │
│ terminal/VNC/USB/bridge │ ★image manager  │ ───▶ │ optional ext4 seed  → vda (fact) │
└───────────────────────────────────────────┘      │ manifest + checksums (trailer)   │
                                                   └──────────────────────────────────┘
```

## First release: `debian.img` (minimal)

A single system file — **Debian 13 (trixie) arm64 minimal install**, target ≤ 150 MB.

| Item | Spec |
|---|---|
| Contents | systemd + networking + SSH + boot-contract scripts; **no** desktop / Xvnc / container stack preinstalled |
| SSH | guest listens on **default port 22** (host: `adb forward tcp:9922 tcp:22`) |
| Accounts | `root` / pw `123`, **`ltbkq` / pw `123` (passwordless sudo)** — both can log in over SSH |
| Acceptance | `tools/boot-test.sh` polls console for `Ready!`; both accounts SSH in successfully |
| Later | `debian-desktop.img` / `debian-containers.img` / `ubuntu.img` … images only, no app update |

Images sharing an `identity` (e.g. `debian:trixie`) switch **without** resetting the data disk;
cross-distro switches ask for a one-time reset.

## Repository layout

```
docs/
  DESIGN.md          Full design document (architecture, image lifecycle, download/import,
                     capability negotiation, milestones)
  IMAGE-FORMAT.md    Byte-level .img spec (footer / manifest / verification rules)
```

This repository is currently at the **design stage**; application code is not imported yet.

## Relationship to upstream

- VM functionality **mirrors [ExTV/Podroid](https://github.com/ExTV/Podroid)** (GPLv2) 1:1:
  `VmEngine` / `QemuEngine` / `AvfEngine`, socket layout, boot contract, host bridge,
  terminal, VNC, USB passthrough are all kept; only "where the OS comes from" becomes
  an image manager.
- System images are built and published by
  [`ltbkq/Podroid-Debian`](https://github.com/ltbkq/Podroid-Debian) as Release assets.
- License: GPL-2.0-or-later (inherited from upstream).

## Status

| Phase | Content | Status |
|---|---|---|
| M0 | Design review + P0 spike (trailing-data direct mount on real QEMU/AVF) | **In progress** |
| M1 | Fork upstream → rename/package → strip rootfs asset | Not started |
| M2 | `.img` codec + `mkimg.sh` + account spec | Not started |
| M3–M7 | Image management / download / regression / release | Not started |

See [docs/DESIGN.md §14](docs/DESIGN.md).
