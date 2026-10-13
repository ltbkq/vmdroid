# M0 Spike Runbook（v2 磁盘镜像设计的前置验证）

**上游设计**：[DESIGN-disk-image-v2.md §11 风险表](../DESIGN-disk-image-v2.md) · **规格**：[IMAGE-FORMAT-v2.md](../IMAGE-FORMAT-v2.md)
**执行环境**：PC = Ubuntu 24.04 x86-64（`qemu-system-aarch64`、AAVMF、mtools、sgdisk 齐备）；
Android 设备 = **未连接**（P0-1 待执行）。

| Spike | 状态 | 结论所在 |
|---|---|---|
| P0-1 AVF 固件/磁盘引导能力 | ⏳ 待设备 —— **一键脚本就绪**：`tools/m0/p0-1-run.sh`（插上设备一条命令出 VERDICT + 方案映射） | §P0-1 |
| P0-2 UEFI+GRUB 链时延 | ✅ **完成（六轮）**：功能全通；最优配置 `-bios 2MB 固件 + 未压缩 Image + gzio`；时延 +9.13s（PC/TCG）→ **目标修订 M-d 待拍板**，真机 M-c 待设备 | §P0-2 |
| P0-5 固件体积 | ✅ **定案**：`-bios QEMU_EFI.fd` = **+2 MB**（AAVMF 64MiB×2 放弃，A/B 无时延差异） | §P0-5 |
| P0-3 guest contract v2 全通 | ⛔ 阻塞于 M1/M2（需 `mkdisk.sh` + `init-podroid v2`） | — |
| P0-4 体积/压缩率 | ⛔ 阻塞于 M1（量测方法见 §P0-4） | — |
| P0-6 脏模型用户认知 | ⏳ 文案评审（非技术） | — |
| P0-7 运行时参数通道 | ✅ **已完成（源码判定）** | §P0-7 |
| P0-8 ext4 碎片/trim 长跑 | ⛔ 阻塞于 M3 | — |

---

## P0-1 —— AVF 是否支持"无 kernel 的固件启动"（最高优先）

**要回答**：方案 A（AVF 固件启动）可行，还是必须走方案 B（从 ESP 提取 kernel/initrd 直启）。

**探测器**：`app/src/androidTest/.../avf/AvfFirmwareProbeTest.kt`
（纯反射面 dump + 配置层 build 尝试，**不创建 VM、不改系统状态**）

```bash
# 一键（推荐）：环境预检 + 旁证 + 跑探测 + 采集 + VERDICT 映射，产物落 tools/m0/out/
tools/m0/p0-1-run.sh
```

```bash
# 1. 连接支持 AVF 的设备（Pixel 8+/Android 16，adb 可见）
adb devices
# 2. 跑探测（需已加 androidTest 依赖：androidx.test.ext:junit + runner）
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ltbkq.vmdroid.engine.avf.AvfFirmwareProbeTest
# 3. 读结论
adb logcat -d -s AvfFirmwareProbe | tee tools/m0/out/p0-1-$(date +%Y%m%d).log
```

**决策树**（读 `VERDICT=` 行）：

| VERDICT | 含义 | 行动 |
|---|---|---|
| `SIGNAL-A` / `SIGNAL-A(no-api-but-accepted)` | AVF 配置层接受无 kernel 的 config | **续做启动级验证**：手工造 v2 镜像 → `addDisk(img, rw)` → `run()` → 看能否过 UEFI 到 `Ready!`；过了则方案 A 成立（QEMU/AVF 同构，最干净） |
| `SIGNAL-A(part)` | 有固件类 API 但 build 仍失败 | 读失败原文，可能差一步（如需 `setFirmwarePath` + 固件文件落地），补一次探测 |
| `SIGNAL-B` | 无固件 API 且无 kernel build 被拒 | **方案 B 必做**（设计文档默认路径）：实现 FAT32 最小读取器，从 ESP 取 `/vmlinuz` `/initrd.img` → `setKernelPath/setInitrdPath` + `addParams(grub.cfg 基础串 + vmd.* )` |
| `SIGNAL-C` | 设备无 AVF | 该设备走 QEMU 后端；不阻塞 |

**补测（任一结论下都值得做）**：`adb shell cmd -l` 找 `virtual_service`，尝试
`adb shell cmd virtual_service list`（若存在，可作为不装 APK 的快速能力旁证）。

---

## P0-2 —— UEFI+GRUB 引导链与时延（脚本已就绪，可重复执行）

```bash
tools/m0/p0-2-uefi-chain.sh          # 产物：/tmp/opencode/m0-p02/{p0-2-results.md,*.ts.log}
```

脚本做三件事：
1. 免 root 取 `grub-efi-arm64-bin`（注意：**2.12 起 arm64 GRUB 来自源包 `grub2-unsigned`**，
   且要到 ports 归档找）→ `dpkg-deb -x` 解出 `arm64-efi` 平台模块；
2. 按 `IMAGE-FORMAT-v2.md §2` 造最小磁盘（GPT + ESP + 空 ext4 root；**偏差**：无 meta 内容、
   `grub.cfg` 加了 echo 打点行）；
3. 两个用例打点计时：
   - `uefi`：AAVMF pflash + 磁盘（**无 `-kernel/-initrd`**）
   - `direct`：v1 风格 `-kernel/-initrd` 基线

**判定**：内核接管（`Booting Linux`/`Linux version`）时延增量 ≤ **+3s**（设计 §11 P0-2）。
> 注：现成 payload initrd 是 v1 的（会在 initramfs 内 mount squashfs 失败 → FATAL）——
> 这不影响 P0-2 的测量目标（到内核接管/initramfs 执行），全链 `Ready!` 属 P0-3。

### P0-2 第三轮（2026-10-12，正式数字：`gzio` + 模块版本对齐 + Ubuntu stub 内核）

`tools/m0/cache/p0-2-results` 见 `/tmp/opencode/m0-p02/p0-2-results.md`：

| 里程碑 | UEFI+GRUB 链 | v1 直启基线 | 差值 |
|---|---|---|---|
| GRUB 开始加载内核 | 5.963s | — | — |
| 内核接管（Booting Linux） | **11.379s** | 1.830s | **+9.549s** |
| initramfs 执行（podroid-init） | **24.745s** | 14.768s | **+9.977s** |

**✅ 功能结论：v2 引导链（AAVMF → GRUB → ESP kernel/initrd → initramfs）在 QEMU virt 上全通。**
**❌ 时延结论：+9.5s ≫ 目标 +3s（设计 §11 P0-2 判定不通过）。**

**时延分解（可优化点定位）**：

| 阶段 | 耗时 | 占比 | 说明 |
|---|---|---|---|
| EDK2 固件 banner → BdsDxe 启动引导器 | ≈ 5.9s | ~62% | DXE 阶段，TCG 单核；最大单项 |
| GRUB cfg 执行 → 内核入口 | ≈ 5.4s | ~57% | GRUB `gzio` 解压 18MB→~55MB + UEFI FAT 块 I/O（TCG） |
| （合计到内核接管） | +9.5s | 100% | |

> 两段相加 >100% 因基线也有 1.8s；净增量 +9.5s。

**缓解手段（按预期收益排序，进入设计评审）**：

| # | 手段 | 预期 | 成本 |
|---|---|---|---|
| M-a | **ESP 存未压缩 `Image`**（免 GRUB 端 inflate） | −3~4s | ESP 256MiB 够（Image ~55MB）；分发压缩不受影响（zstd 管整盘） |
| M-b | 换 `-bios QEMU_EFI.fd` 单文件固件 A/B | 待测（可能 ±1s） | 顺带解决 P0-5 体积问题 |
| M-c | **真机数据**：手机 QEMU(TCG, arm64 同构) 与 AVF(KVM，若 P0-1 允许固件) | KVM 下固件段预估 <1s | 需设备（P0-1 同批执行） |
| M-d | 接受时延并修订目标 | ✅ **已拍板（2026-10-12，设计文档 §0.2 D10）**：PC/TCG ≤ +10s（实测 +9.13s 达标）；手机 QEMU ≤ +5s、AVF/KVM ≤ +2s 待真机回填 |

### P0-2 第五轮（M-a 生效验证：ESP 存未压缩 Image）

| 里程碑 | gzip（第四轮，同内核） | **未压缩（第五轮）** | 收益 |
|---|---|---|---|
| 内核接管 | 12.367s | **10.275s** | **−2.09s** |
| initramfs | 18.426s | **16.528s** | −1.90s |
| vs 直启基线 | +11.141s | **+9.058s** | |

→ **M-a 采纳**：ESP 存未压缩 `Image`（省 GRUB 端 gzio inflate ~2s；ESP 256MiB 足够
`Image` ~60MB + initrd 42MB）。`IMAGE-FORMAT-v2 §2.3` 的 `/vmlinuz` 形态按此定稿
（gzip 仍可用于 `.img.zst` 整盘压缩，分发体积不受影响）。
→ 剩余大头：**EDK2 固件 5.93s + UEFI 侧装载 4.35s** → 转 M-b（第六轮，`-bios QEMU_EFI.fd`）。

### F1 隔离复测（第四轮：现网 payload 内核 + gzio）—— ✅ 结果：**F1 不成立**

> 第四次执行（前三中一次因"运行中改脚本"作废）：`M0_TIMEOUT=60`，2026-10-12 11:53。
>
> | 里程碑 | UEFI+GRUB（现网内核+gzio） | 直启基线 | 差值 |
> |---|---|---|---|
> | 内核接管 | **12.367s** | 1.226s | +11.141s |
> | initramfs（podroid-init） | **18.426s** | 8.542s | +9.884s |
>
> **判定：现网 `linux-7.1.5` 内核可直接经 UEFI+GRUB 加载（EFI stub 已具备）**，
> 第一轮报错 100% 归因于脚本缺 `gzio`。→ 设计文档 §4.4-5 已撤回 `CONFIG_EFI_STUB`
> 需求；`podroid_kernel.config` 不动；唯一硬要求 = `grub-mkimage` 模块含 `gzio`。
> （+11.1s vs 第三轮 +9.5s 的差异 = 内核体积 20.9MB vs 18.6MB 的解压成本 + TCG 波动。）

### P0-2 第六轮（M-b A/B：`-bios QEMU_EFI.fd` 2 MB 单文件固件 + 未压缩 Image）

| 里程碑 | pflash AAVMF（第五轮） | **`-bios QEMU_EFI.fd`（第六轮）** | 结论 |
|---|---|---|---|
| GRUB 开始加载内核 | 5.930s | 5.992s | **固件段无差异**（EDK2 ~6s 是 TCG 固有成本） |
| 内核接管 | 10.275s | **10.341s** | 等同（噪声内） |
| initramfs | 16.528s | 16.545s | 等同 |
| vs 直启基线 | +9.058s | **+9.128s** | |

→ **M-b 采纳**：`-bios QEMU_EFI.fd`（2 MB，无 VARS、无可写 UEFI 变量）**功能与时延双通过**，
P0-5 定案（APK 固件增量 +2 MB）。

### P0-2 最终结论（2026-10-12，六轮）

| 项 | 结论 |
|---|---|
| **功能** | ✅ `QEMU_EFI.fd(-bios) → GRUB(ESP) → kernel/initrd → initramfs` 全链稳定通过（第 3–6 轮，两种内核、两种固件路径） |
| **最优配置** | `-bios QEMU_EFI.fd` + **ESP 存未压缩 `Image`** + GRUB 模块含 `gzio`（initrd 解压仍需） |
| **时延** | 最优 **+9.13s**（PC/x86/TCG），固件段 6.0s + UEFI 装载 4.3s —— **原目标 ≤3s 不可达**（无进一步 PC 侧杠杆：两条固件路径无差异、解压已省） |
| **后续杠杆** | M-c：真机（手机 TCG 同构 + AVF/KVM，若 P0-1 允许固件 → KVM 下固件段预估 <1s）；M-d **已拍板**（设计文档 §0.2 D10 分档目标） |
| **副作用** | 无：`-bios` 仅牺牲可写 UEFI 变量（GRUB `timeout=0` 不依赖）；`-kernel` 直启基线仍正常（第 4–6 轮 direct case）→ AVF 方案 B 回退路径不受影响 |

### P0-2 第一/二轮过程发现（含已推翻的归因，保留做教训）

| # | 发现 | 结论/行动 |
|---|---|---|
| F1 | ~~**GRUB 拒载现网内核**~~：`error: plain image kernel not supported`（6.085s 处） | ❌ **已推翻（第四轮隔离复测）**：现网内核 + gzio 直接通过 → 报错与内核无关，纯脚本 bug（见 F1b）；`CONFIG_EFI_STUB` 需求撤回 |
| F1b | **真凶（大概率）**：`grub-mkimage` 模块清单**缺 `gzio`** —— 两个内核都是 gzip(`Image.gz`)，无 gzio 时 GRUB 读到压缩流头而非 PE/EFI-stub 头 → 报同样的 stub 错误（误导性）；另发现宿主 grub-mkimage 2.12 配了 2.14 模块的版本错配 | 修正：模块清单加 `gzio`；arm64 模块版本与宿主 `grub-mkimage` 对齐（2.12/2.12，缓存 `tools/m0/cache/grub-arm64`）；**生成侧硬要求已写入 IMAGE-FORMAT-v2 §6 步骤 5 模块清单** |
| F2 | UEFI 链本身可通：固件 banner 0.293s → **BdsDxe 启动引导器 6.055s → GRUB cfg 执行 6.084s**（EDK2 DXE 阶段占 ~5.7s，TCG 单进程） | 链路 OK；**固件初始化是时延大头** |
| F3 | 直启基线：内核接管 1.214s、initramfs 8.529s | 对比基准 |
| F4 | 失败后 GRUB 落入 rescue shell（`timeout=0` 但 menuitem 失败 → 继续 error→shell） | 生产 `grub.cfg` 应加 `set -e`/错误时 reboot 或停在菜单，避免挂死占串口（写入 IMAGE-FORMAT-v2 §2.4 待办） |

---

## P0-7 —— 运行时参数通道（✅ 2026-10-12 完成，源码判定）

判定材料：`Podroid-Debian/docs/DELTAS.md:52`、`rootfs/etc/systemd/system/podroid-*.service`、
`rootfs/usr/local/lib/podroid/{podroid-network,podroid-xvnc,podroid-bootstrap,podroid-getty}`。

| 发现 | 影响 |
|---|---|
| `ssh=1`、`androidip=` **无人消费**（上游死参数） | v2 直接删除，不需要传递通道 |
| `podroid-hostd.service` 的 `After=` 含 `podroid-network.service` | **"参数走 host-bridge"对 QEMU 不成立**（network 早于 hostd）→ 设计 §3.4 方案 a 降级为 AVF-only |
| `podroid.backend=avf` / `podroid.epoch=` / `podroid.tty=` 只被 AVF 场景消费 | AVF 走 §3.2 方案 B 的 `addParams`，天然满足 |
| `podroid.dns=`/`bandwidth=`（network）、`podroid.x11.dpi=`（xvnc）是**引导期**参数 | QEMU UEFI 路径需方案 b（ESP `user.cfg` + GRUB `source`）或 c（fw_cfg + 开 `CONFIG_FW_CFG_SYSFS`）；**建议 b** |

→ 设计文档 §3.4/TBD-1/§11 已按此修订（P0-7 关闭）。

---

## M1 进展（2026-10-12）：`mkdisk.sh` 打包器落地并交叉验证

**交付**：`Podroid-Debian/tools/mkdisk.sh`（build + `--verify`，**构建与校验共用单一 parser**，
沿用 v1 `mkimg.sh` 约定）+ `tools/mkdisk-content-id.py`（§4.3 content_id 参考实现）。

**验证结果**（合成 rootfs + 现网 kernel/initrd + grub 2.12 平台模块）：
- build + 自检（parser 回读 + root 全量 sha 重算）✅；`--zstd` 产出 60.3MB（2.3GB 稀疏镜像）
- `content_id`：确定性 ✅ / 变更敏感 ✅ / 还原一致 ✅（排除白名单生效）
- 负向向量：截断、尾追加、meta 魔数覆写、manifest 翻转、root 翻转(full)、裸 hsqs→`LEGACY_FORMAT`、
  v1 footer→`LEGACY_FORMAT` 全部按规格拒绝；**ESP 破坏 → 接受**（IMP-C01"MUST NOT reject"）✅
- 引导：单盘 ✅ 且 **vda+vdb 双盘拓扑 ✅**（UEFI→GRUB→内核 10.2s→initramfs→
  预期停在 `FATAL: squashfs /dev/vdb` —— v2 与 v1 initramfs 的已知分界点，M2 换 `init-podroid v2` 即过）

**实现教训（已全部回填 IMAGE-FORMAT-v2 §2.3/§2.4）**：
| # | 教训 | 修正 |
|---|---|---|
| L1 | `sgdisk -q/--quick` 对 GUID 修改**跳过写回**（meta/root GUID 静默失效） | 去 `-q`，静默重定向；verify 的"固定 meta GUID"检查抓住了它 |
| L2 | GPT 表项 GUID 是 **mixed-endian**（不能拿裸 hex 比） | `uuid.UUID(bytes_le=…)` |
| L3 | `search --fs-uuid` 要 **`XXXX-XXXX` 横线形式**；裸 hex → `no such device` → 退化 prefix 兜底 | `mkfs.vfat -i` + 横线形式；记入 `disk.esp.fat_uuid` |
| L4 | 烘焙 prefix `(hd0,gpt1)` 在 vda+vdb 双盘下指错盘 → GRUB 掉 rescue | **cfg 用 `grub-mkimage -c` 嵌入镜像**（自包含、盘序无关） |
| L5 | `menuitem` 非 GRUB 命令（应为 `menuentry`）；菜单写错=内核永不加载 | v2 不用菜单：单内核顶层命令 + `timeout=0` |

**下一步（M1 剩余）**：真实 distro rootfs 切换（`distro-build/*.sh` → mke2fs -d 流水线）、
`tools/pc-boot-smoke-v2.sh`（`--smoke` 依赖它，当前按 IMP-C03 warn/skip）、
`codec-selftest` v2 夹具生成器、catalog schema v2 落地。

### M1 多代理流水线（2026-10-12 下午，四线并行）—— 已全部落地并集成对账

| 线 | 交付 | 验证 |
|---|---|---|
| A 冒烟器 | `Podroid-Debian/tools/pc-boot-smoke-v2.sh`（双盘拓扑，`bootchain`/`ready` 两级，4 类退出码；`mkdisk.sh` 透传 `MKDISK_SMOKE_ARGS`） | bootchain 正例 17.0s PASS；破坏 `BOOTAA64.EFI` → exit 1。**发现**：EDK2 FAT 驱动不校验 VBR 跳转指令（改 ESP 首字节不影响引导） |
| B 夹具 | `codec-selftest/gen_fixtures_v2.py` + 25 张夹具 + `fixtures-v2.json`（oracle 实录）+ README §5 | 生成器自断言全过；幂等重生成 70s；**交叉验证抓到参照实现 bug**（见 L6）与 spec 歧义 #2（见 L7） |
| C 管线 | `distro-build/pack-v2.sh` + `repack-homefix.sh --v2`（v1 路径不动）+ `README-v2.md` | 9 项自测全绿；**真实 Ubuntu squashfs(582MiB) → v2 `.img`+`.zst` 端到端**（`--verify --full` rc=0 + bootchain 17.7s PASS）；目录/squashfs 双输入 `content_id` 一致 |
| D codec | `VmdDiskCodec.kt`(57.6KB) + `EspFat32.kt`(21.7KB) + `SelfTestV2.kt` | gradle 编译 EXIT=0；kotlinc 自测 **34 passed / 0 failed / 1 skipped** |
| **四方对账** | 冻结规格 ↔ Python 参照实现(mkdisk) ↔ Kotlin 独立实现(VmdDiskCodec) ↔ oracle 夹具 | **25/25 夹具期望一致**（3 ok / 22 reject；REJECT-REASONS：CORRUPT=11、FORMAT_UNSUPPORTED=2、LEGACY_FORMAT=2、其余各1）—— M1 交叉验证闭环成立 |

**流水线期新增教训（回填规格/实现）**：

| # | 教训 | 处置 |
|---|---|---|
| L6 | `mkdisk-content-id.py` EXACT 排除集带前导 `/`，`/etc/machine-id`、`/root/.bash_history` **从未被排除**（B 线交叉验证抓到） | 已修（去前导斜杠）+ 实测回归（改 machine-id → content_id 不变） |
| L7 | spec「无 `app` 块 → `min_version_code=0` 不拒绝」与生成侧 oracle 冲突 | 拍板修订 spec §4.2：v2 读取侧**必需**（无块或缺键都拒），两侧从严一致；D 线实现与 B 线 oracle 均已对齐 |
| L8 | EDK2 FAT 驱动宽容（VBR 跳转指令不校验）→ "改 ESP 首字节"不是有效负例 | 冒烟器反例改用破坏 `BOOTAA64.EFI`（引导必然失败） |

**M1 剩余（下一步）**：catalog schema v2（`mkcatalog.sh` + `ImageCatalog.kt`）、
manifest 权威 v2 化（`payload/manifest-*.json` 仍是 v1）、M2 guest（`init-podroid v2` 换掉
`FATAL: squashfs /dev/vdb` 分界点 → `--stage ready` 全绿）。

### M2 guest 侧：`init-podroid v2` —— ✅ 全套 25/25 通过（2026-10-12 17:00）

**交付**：`vmdroid-app/init-podroid-v2`（POSIX sh，与 v1 同结构；v1 原件不动）。
能力：按 cmdline `vmd.root_partuuid=` + GPT 表项（mixed-endian）校验挂 **p3 root ext4**、
p2 meta 魔数健康检查（`VMDIMG02`）、`/home` bind 到 vda（含 skel 首启种子）、
扩容（§6.5：宿主扩文件 → guest `sfdisk -N 3` + **内核分区表 reread** + `resize2fs`）、
无 overlay（契约 v2 标记：保留 `Mounting storage/system...`，删 `Stacking overlay...`）、
所有失败路径 fail-closed（绝不挂错盘/绝不自动 mkfs root）。

**验证harness**（G 线遗留，纳入资产）：`/tmp/opencode/m2/`（`run-all.sh`/`run-case.sh`/
`assert.sh`，25 条断言 + 改造 initrd 的重打包流程 + 假 `/sbin/init` 打 `M2_FAKE_INIT_RAN` 标记）。

| 用例 | 结果 |
|---|---|
| 正例（vda+vdb 双盘，UEFI→GRUB→内核→initramfs→switch_root→假 init PID1） | ✅ 9/9（含 partuuid verified、`/home` bind、skel 种子、无 `FATAL: squashfs` 分界点） |
| 扩容（宿主 truncate +512M → guest 自动 GPT 迁移 + resize2fs） | ✅ 5/5 ——**修复 2 处**：①`--no-reread` 致内核分区视图不刷新 → resize2fs 必失败（我改为"先带 reread 写入 → 退 `--no-reread`+`blockdev --rereadpt` → 校验尺寸增长才 resize2fs，成功才打 expanded"）；②harness 的 neg-a 偏移错（269537280 应为 269484032）致负例假绿 |
| 负例 A（meta 魔数破坏） | ✅ 4/4：`FATAL: meta magic … got '58585858…'`，不挂载、不 switch_root |
| 负例 B（root PARTUUID 错盘） | ✅ 4/4：`FATAL: root PARTUUID mismatch … refusing to mount the wrong disk` |

**M2 遗留（非阻塞）**：真 rootfs 的 `--stage ready` 全绿需一次 sudo（`unsquashfs` 属主无损
展开 → `pack-v2.sh` → `pc-boot-smoke-v2.sh --stage ready`），环境无 sudo 密码故挂 TODO。

**R7 缺口闭环（2026-10-12 晚，I 线 E2E + mkdisk 复检共同发现）**：
扩容（§6.5）会让 `meta.file_size` 与 `meta.root_size` 永久陈旧 → codec 结构校验永久 CORRUPT。
**裁决**：guest 扩容流程原位回写两个 u64 LE（meta+16 / meta+80；meta 分区在 root 之前，
扩容不移动它）；`manifest.disk.file_size` 为构建期快照，读取方以 meta 为唯一真值 MUST NOT 拒绝
（两份规格已加 R7 注记）。**实测闭环**：`Root meta updated (file_size=2963275776,
root_size=2685386240)` → 扩容后 `mkdisk.sh --verify grown.img` = **VERIFY_OK**；
`root_sha256` 扩容后失效属 dirty 语义（仅 full 校验受影响，规格已注明）。

**R8 定稿（2026-10-12，QEMU 运行时参数通道 = ESP `/grub/user.cfg`）**：
- 产出形态：mkdisk 与嵌入式 cfg **同构建**预置定长 4096B user.cfg（首行 `#VMDU2`，`\n` 填充禁 NUL）；
  嵌入式 cfg 以 `source /grub/user.cfg` + `linux … $podroid_params` 消费（`source` 前）。
- **4 条 GRUB 实证**（救援 shell 交互 + 真机引导）：`source` 共享变量 ✓；`configfile` 不回传变量 ❌；
  `test -f &&` 守卫在脚本上下文静默 false ❌；`set` 无引号截断到首个空格（引号必需，且保留在
  值中 → cmdline 带包裹引号，guest 子串捕获不受影响）。
- E2E（真 cmdline 实录）：
  `Kernel command line: … vmd.root_partuuid=… "podroid.x11.dpi=160 r8param=hello"`（引号多参数）；
  `Kernel command line: … podroid.x11.dpi=240`（无引号截断实证）。
- cfg 变更后 M2 全套回归：**25/25 + R7 回写完好**（run-all8）。
- 应用侧实现：`EspUserCfgWriter`（定长覆盖写 + 回读回滚 + 注入白名单 fail-closed），
  失败语义 = 默认参数照常启动（绝不阻断）。
- **应用侧 E2E 闭环（O 线，2026-10-12）**：真实 `EspUserCfgWriter` 写 `podroid.x11.dpi=170`
  （目录项/大小/首簇不变、回读 4096 全对、无 NUL）→ 引导 11.0s 命中，
  cmdline 实录：`… vmd.root_partuuid=… podroid.x11.dpi=170`；EspFat32 增受控写入口
  `overwriteFileData`（仅覆写既有簇数据；链非法先校验零写出；调用方备份+回滚）；
  单测 450/0（新增 15，含 `R8_IMAGE` 集成用例）；v2 selftest 重编后 34/0。
  两条新观察：① sanitize 拒绝时落地为"写空参数版本"（清陈值、默认值启动，非完全不写）；
  ② 单参数形态下包裹引号可能被 GRUB `linux` 拆参剥离（规格已补注，两种形态均兼容）。

**M2 harness 使用规范（三次事故的教训，务必遵守）**：
1. **initrd 重打包必须 `cpio -o -H newc --owner=0:0`**：initramfs 里 `bin/mount` 是
   setuid 位文件，非 root 打包若不强制 owner，属主变 uid1000 → **setuid 使 euid=1000 →
   mount(2) EPERM → init exit32 → kernel panic**（"修了没重建镜像"还会造成二次假象；
   二分定位法：v1 initrd 同盘对照 → v1 树+v2 脚本对照 → 逐文件哈希比对）。
2. **清理白名单**：`probe/`（busybox 素材）、`storage.img`（QEMU 必需 vda）、
   `initrd-v2-tree/`、`*.sh/*.py` 为运行时依赖，不可删；只清理 `*.img` 镜像与 `*.ts.log`。
3. **磁盘占用**：mkdisk 的 dd 会把稀疏文件**稠密化**（一轮 4 镜像 ≈10GB）；跑前 `df` 检查，
   根分区满会造成"半成品镜像 + 旧日志假绿"三层排障（已实吃一堑）。
4. **assert 前提**：`assert.sh` 读的是当前 `*.ts.log`——run-case 未完成/日志为空时结果无效，
   先 `wc -l *.ts.log` 确认非空。
5. **串口采集勿管道给 head/grep**：管道下游提前关管会让 stamp.py 收 SIGPIPE 退出而**不杀 qemu**，
   孤儿 qemu 持有 `storage.img` 写锁 → 后续所有引导静默零输出（`Failed to get "write" lock`）。
   正确姿势：先 `> file` 落盘，再对文件 grep；清理用 `pkill -f "[q]emu-system-aarch64"`（括号防自杀）。
6. **mkdisk 的 `grub_cfg_body` 是 bash 双引号串**：任何要进入 GRUB 的 `$`（如 `$podroid_params`）
   必须写 `\$`，否则 bash 在构建期展开（`set -u` 下直接 `unbound variable` 打死构建）。

---

### 第二批流水线（2026-10-12 傍晚）进度账

| 线 | 交付 | 状态 |
|---|---|---|
| E catalog 生产侧 | `mkcatalog.sh` schema2（`SCHEMA=1` 保底、`OUT_SUFFIX`、`--asset`；数据源=`out/<id>.manifest.json` + 现场双 sha）+ README §8 | ✅ **完成**：v1 零差异回归（diff 实证）、v2 四字段实算一致、签名用应用公钥 `Verified OK`、6 类负向 fail-closed。**契约注记**：资产名强制 == `image.id`（`.img.zst`）；`SCHEMA=1` 分支除 `CATALOG_OUT` 变量化外逐字节不变 |
| F manifest 权威 v2 | `payload/v2/` 5 份 + `check-manifest-v2.py` + README | ✅ **完成并复核**：5 份 OK；`drives.vdb` 拍板对齐 §4.1（`self(rw,gpt)`）；v1 遗留（debian 三处陈旧摘要、键名不一致、fedora 尾换行）记录在案，v2 侧已清理 |
| G init-podroid v2 | `init-podroid-v2` 16.4KB + initrd 改造 | 🔄 活跃（16:06 落盘） |
| H 启动可视化 | Home 日志面板 + AVF consoleText 对齐 | 🔄 施工中 |
| I Store v2 | zst 安装流/状态机/两级恢复出厂/扩容/LEGACY_FORMAT | 🔄 刚开工 |
| K catalog 消费侧 | ImageCatalog schema2 + 下载校验 + 单测 | 🔄 刚开工 |

**E→K 交接契约**（集成时校验）：catalog.json `{schema:2, generated_at, assets:[15 字段]}`，
签名对象=原始字节（base64 DER 单行，公钥 `res/raw/catalog_pub.pem`）；
url=`BASE_URL/<image.id>.img.zst`；`size/sha256` 对 zst、`image_size/image_sha256` 对 img。

---

## P0-4 —— 体积/压缩率/安装耗时（阻塞于 M1，方法先冻结）

| 指标 | 采集方法 | 目标 |
|---|---|---|
| `.img` apparent / 实际占用 | `stat -c '%s %b*512'` | `= 266 MiB + S`；实际 ≈ rootfs 用量 ×1.15 |
| `.img.zst` 体积 | `ls -l` + 对比 v1 `distro-build/out/*.img` | ≤ v1 对应镜像 ×1.5 |
| zstd 压缩比 | `zstd -l` | 记录即可 |
| 安装耗时 | `mkdisk.sh` 端到端 + 应用侧解压实测 | PC ≤ 90 s；手机待 M3 |
| rootfs 用量 | `debugfs -R 'stats'` 或 `mke2fs` 输出 | 断言 ≤ S×0.8 |

---

## P0-5 —— 固件资产体积（✅ 完成：静态实测 + 第六轮 A/B）

| 文件 | 表观 | 非零字节 | 备注 |
|---|---|---|---|
| `/usr/share/AAVMF/AAVMF_CODE.fd`（→ no-secboot 符号链） | **64 MiB** | ≈ **2.04 MB** | 大量 FV 填充零；**不能直接截断**（FvLength=64MiB 头字段会与文件长度不一致） |
| `/usr/share/AAVMF/AAVMF_VARS.fd` | 64 MiB | 模板 | 只需首次复制为可写副本 |
| `/usr/share/qemu-efi-aarch64/QEMU_EFI.fd` | **2 MB** | ≈ 2.04 MB | Debian 系 `-bios` 单文件固件，**无 VARS、无可写变量** |

→ **结论（2026-10-12）**：
1. **首选 `-bios QEMU_EFI.fd`**：APK 增量仅 **+2 MB**，且启动参数更简单（一条 `-bios`）；
   功能/时延 A/B 由 `M0_FW=bios` 实测（P0-2 第六轮）。
2. 若必须 pflash 可写变量（大概率不需要 —— GRUB `timeout=0` 不写变量）：
   AAVMF CODE/VARS **在 APK 内 zstd 存储**（≈2MB/份，落地解压），不入 raw 64MiB。
3. 代价记录：`-bios` 下 UEFI 变量只读（NVRAM 写入丢失）——影响面仅引导顺序/Boot#### 项。

---

## P0-1 之外的设备侧检查清单（一次性）

```bash
adb shell getprop ro.build.version.release        # 需 ≥ 16（AVF 公开 API）
adb shell cmd -l | grep -i virtual                # 有无 virtual_service
adb shell pm path io.github.ltbkq.vmdroid         # 调试包已装
adb shell dumpsys virtualization 2>/dev/null | head -50
```

---

## 发布记录（2026-10-12，v2 首发）

**Release**: https://github.com/ltbkq/vmdroid/releases/tag/system-images-v2-2026.10.12 （prerelease）
**7 资产**：`{alpine-3.24, ubuntu-noble, debian-minimal, fedora-44, arch-rolling}-arm64.img.zst`
（162/206/398/317/442 MB）+ `catalog.json`（schema2）+ `catalog.json.sig`（ECDSA P-256）。

**测试全量（发布闸门，全 PASS）**：
| 层 | 结果 |
|---|---|
| L1 `mkdisk --verify --full` | 5/5 |
| L2 UEFI bootchain 冒烟 | 5/5 |
| L3 保真抽检（losetup -P 分区挂载） | 32/32（shadow uid0、setuid、file caps、OpenRC/systemd 双路径 podroid-ready、ESP 六件、user.cfg=4096） |
| L4 **Ready! 全链（真 rootfs）** | **5/5** —— 五品类均到 `Ready!` |
| catalog 双验签 + 消费侧互操作 | Verified OK ×2 + INTEROP 5/5（应用 ImageCatalog 解析） |

**过程中修复**：R9（content_id 排除 `/dev/**` + socket/fifo/device 跳过，arch 的
`S.gpg-agent` 触发）· L3 挂载方式（GPT 整盘需 `losetup -P`）· alpine OpenRC 路径判定 ·
publish 未传 `TAG` 致 URL 指向 v1 旧 tag（dry-run 拦截）。

**工具迁移**：`mkdisk.sh` / `mkdisk-content-id.py` / `pc-boot-smoke-v2.sh` →
`vmdroid-app/build-tools/`（原 `Podroid-Debian/tools`）；distro-build 脚本、
gen_fixtures_v2、docs/TESTPLAN、VmdDiskCodec 头注释等引用已切换；
**Podroid-Debian 远程仓库经用户授权删除**（本地目录保留为历史，v1 mkimg.sh 不再维护）。
7. **发布物测试必须在拷贝上引导（2026-10-12 事故复盘）**：v2 镜像是可写运行盘，
   L4/Ready 冒烟直接引导发布用 `.img` 会把 guest 写入（machine-id/host key/journal）
   灌进去 → 事后 `--verify --full` 报 root_sha 失败，**且在脏文件上生成 catalog 会让
   `image_sha256` 与已上传的 `.img.zst` 不一致 → 应用安装必失败**。
   已修：`test-v2-release.sh` L4 改为 `cp --sparse` 拷贝引导；发布事故处置实录 =
   从 `.img.zst` 无损恢复 `.img` → 重生成/重签 catalog → `--clobber` 覆盖远端 catalog/sig →
   远端验签 + 资产↔目录 sha 对账全通（本节上方发布记录的 catalog 即修复版）。

## 真机实测记录（2026-10-13，v2.0.1 三连修 · OPPO PKX110 / Android 16 / arm64）

设备首装 `vmdroid-2.0.0-debug.apk` 后按"下载镜像 → 安装 → 启动"全链实测，连环暴露三处
仅真机出现的问题（PC 侧 L1–L4 全 PASS 不覆盖应用运行时）：

| # | 现象（用户可见） | 根因 | 修复（均随 2.0.1） |
|---|---|---|---|
| 1 | `cannot start '…files/bin/zstd -dc': error=13` | **W^X**（DESIGN §7.2 T-1 预警）：untrusted_app 域禁 exec 应用可写目录内文件（同一文件在 run-as 域可 exec → SELinux 域差异实证） | zstd 迁 `jniLibs/arm64-v8a/libzstdbin.so`（`useLegacyPackaging=true` → nativeLibraryDir 解包 0755，与 libqemu 同机制）；`ensureZstdBinary()` 直返该路径并清理 filesDir 残留；资产 `assets/bin/zstd` 移除 |
| 2 | `zstd exited with 159`（= 128+31 **SIGSYS**），下载成功但解压中断 | glibc 静态 zstd 调用应用 seccomp 白名单外系统调用 → 内核杀进程（run-as 走 shell seccomp 宽松策略，自检**假通过**） | NDK r28 clang 静态**重编 bionic 版**（syscall 全在应用白名单内）；NOTICE 同步构建命令与真机验证记录；退出码消息区分 signal/exit 并标注 SIGSYS |
| 3 | 镜像已装好，点启动 → `NO_UEFI_FIRMWARE` | `extractAssets` 用 `copyAssetDir("qemu", filesDir)` 把资产摊平到根，引擎按 P0-5 冻结规格查 `filesDir/qemu/QEMU_EFI.fd`；v2 真机首启才暴露 | 提取改 `copyAssetDir("qemu", filesDir/qemu)` 保子目录 + 清根目录三份遗留（NIC `romfile=` 置空，ROM 无运行时消费方） |

**验证结果（全 PASS）**：
- 安装链：目录下载 206 MB（缓存复用，未重下）→ bionic zstd 流式解压 → 2.4 GB 稀疏落盘 →
  `sha256(.img.zst)` + `image_sha256` 双校验 → 结构校验 → **`已安装` 徽标** ✓
- 启动链：`启动虚拟机` → qemu 进程起 → **UEFI → GRUB → 内核 → 运行中（60s）** →
  宿主 `tcp:9922 → guest:22` **SSH 端口通** ✓ → 终端页实况：guest 内 `apt update`
  走 mirrors.aliyun.com 正常拉源（网络/时钟/磁盘/输入全正常）✓
- 门禁：compile 0 · 单测 **487/0** · assemble 0 · APK `36 / 2.0.1-debug` +
  `lib/arm64-v8a/libzstdbin.so` + `extractNativeLibs=true` ✓

**教训（harness 新铁律）**：**PC 级镜像测试全绿 ≠ 应用真机链路**——execve/资产布局/平台策略
类差异必须真机打桩；**run-as 自检会因 SELinux/seccomp 域不同而假通过**，不可替代应用域实测。

## 发布记录（2026-10-13，v2.0.2 启动即终端）

**Release**: https://github.com/ltbkq/vmdroid/releases/tag/v2.0.2（latest，非 prerelease）
**APK**: `vmdroid-2.0.2-debug.apk`（versionCode 37，49,143,886 B）+ `SHA256SUMS`

**特性**：点"启动虚拟机"（含出错重试）**直达终端页**；Starting 分支由静态占位改为
`BootConsoleSurface`——实时消费 `engine.consoleText`（serial.sock 尾缓冲 64 KB、每次
启动清零）滚动直播 UEFI→GRUB→内核→init 原始输出，顶部"启动中 · 阶段"同步 `bootStage`；
Running 后外层 when 原地切 `TerminalSurface` 交互 shell，全程无需离开终端页。

**真机时间线**（OPPO PKX110）：点火 → 自动跳终端 → ~8 s 滚动至 Configuring
containers（64 KB 缓冲到顶）→ ~16 s 原地切 `podroid login:` 交互会话；单测 487/0。

**过程修正**：首发误传 `SHA256SUMS-202` 资产 → 删旧传新恢复规范名 `SHA256SUMS`
（应用自动更新校验与文档 `sha256sum -c SHA256SUMS` 依赖精确命名）；README 全量
版本引用同步 v2.0.2 / versionCode 37（下载头、核心目的、架构图、M4/M7 状态行）。
