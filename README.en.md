# VMDroid

**An Android VM with the software and the OS separated** — the VM is a standalone app,
the system is a swappable `.img` file.

> **Language**: the **Chinese** documentation ([README.md](README.md)) is the
> **primary** source. This English README is a **secondary** translation and may
> lag behind. If the two ever disagree, **the Chinese version wins**.

Design: [docs/DESIGN.md](docs/DESIGN.md) · Image format: [docs/IMAGE-FORMAT.md](docs/IMAGE-FORMAT.md) · Review protocol: [docs/REVIEW.md](docs/REVIEW.md)

## Purpose

1. **Software and system separated** — the VMDroid APK (~70 MB) contains only the VM
   (engines, kernel, initrd, terminal, VNC, bridge) and **no distro rootfs at all**.
2. **The system is swappable** — the OS is one standalone `.img` file. Changing the OS
   means changing the file: no app reinstall, no code change. New systems are shipped
   as **images only; the app never needs an update**.

```
┌────────── VMDroid APK (~70 MB) ──────────┐      ┌─── system image .img (≤150 MB) ─┐
│ VM engines (QEMU/AVF) │ kernel+initrd │UI │      │ rootfs.squashfs     → vdb (ro)   │
│ terminal/VNC/USB/bridge │ ★image manager  │ ───▶ │ + kernel/initrd → boots on PC   │
└───────────────────────────────────────────┘      │ manifest + checksums (trailer)   │
                                                   └──────────────────────────────────┘
```

## First release: `debian.img` (minimal)

A single system file — **Debian 13 (trixie) arm64 minimal install**, target ≤ 150 MB.

| Item | Spec |
|---|---|
| Contents | systemd + networking + SSH + **Xvnc/pulseaudio (X11 & audio contract)**; **no** desktop environment / container stack preinstalled |
| SSH | guest listens on **default port 22** (chain: `adb forward tcp:9922 tcp:9922` → phone `:9922` → guest `:22`) |
| Accounts | `root` / pw `123`, **`ltbkq` / pw `123` (passwordless sudo)** — both can log in over SSH |
| Acceptance | `tools/boot-test.sh` polls console for `Ready!`; both accounts SSH in successfully |
| Get the OS | in-app catalog **download** (resume + sha256) · or **import from file** — **`.img` only** (wrap others with `mkimg.sh`) |
| First run | No image → **setup wizard** (download/import), start button hidden; never boots a mount-failed zombie (see [DESIGN §8.4](docs/DESIGN.md)) |
| **PC boot** | `.img` ships kernel+initrd; on Linux PC `tools/pc-run.sh debian.img` reaches `Ready!` in one command |
| **PC boot** | `.img` ships kernel+initrd; on Linux PC `tools/pc-run.sh debian.img` reaches `Ready!` in one command (deps: `apt install qemu-system-arm python3 e2fsprogs`) |
| Boot image | a **boot-image selector** on the Home screen switches installed `.img` files (see [DESIGN §8.2](docs/DESIGN.md)) |
| Factory reset | Zeros `storage.img`; guest re-creates it on next boot (no seed needed, [DESIGN §4.3](docs/DESIGN.md)) |
| Diagnostics | **boot history is preserved** (`boots/` archive + stage timings + failure reason), one-tap diagnostic export (see [DESIGN §16](docs/DESIGN.md)) |
| Later | `debian-desktop.img` / `debian-containers.img` / `ubuntu.img` … images only, no app update |

Images sharing an `identity` (e.g. `debian:trixie`) switch **without** resetting the data disk;
cross-distro switches ask for a one-time reset.

## Repository layout

```
docs/
  DESIGN.md          Full design document (architecture, image lifecycle, download/import,
                     capability negotiation, milestones)
  IMAGE-FORMAT.md    Byte-level .img spec (footer / manifest / verification rules)
  REVIEW.md          Multi-AI review protocol (roles, rounds, requirements baseline)
  reviews/           Per-round issue logs (R1.md, R2.md …)
```

> This English README is a **secondary translation**; update `README.md` first,
> then sync this file or note `last-synced` here.

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
| M0 | Design review (R1–R3) + P0 spike (trailing-data direct mount on real QEMU/AVF + PC boot timing) | **Review R1–R3 done ✅, P0 spike pending** |
| M1 | Fork upstream → rename/package → strip rootfs asset | Not started |
| M2 | `.img` codec + `mkimg.sh` (`--kernel/--initrd`) + first `debian.img` + `pc-run.sh` + account spec | Not started |
| M3–M7 | Image management + boot-image selector / download / factory reset / regression / release | Not started |

See [docs/DESIGN.md §14](docs/DESIGN.md).
