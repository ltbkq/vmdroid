# VMDroid v2 磁盘镜像测试计划（TESTPLAN-disk-image-v2）

**状态：草案 v0.1**（随 M1–M3 实测滚动更新；数据截至 2026-10-12）
**上游**：[DESIGN-disk-image-v2.md](DESIGN-disk-image-v2.md) §12 · [IMAGE-FORMAT-v2.md](IMAGE-FORMAT-v2.md) §9（L0 权威向量清单）· [M0-runbook.md](spikes/M0-runbook.md)（实测记录与 harness 规范）

分层：L0 静态格式 → L1 构建自检 → L2 应用回归 → L3 PC 引导回归 → L4 真机 → L5 体积/性能。
原则：**每一层都是上一层的闸门**；L0–L3 全绿才允许真机构建（L4/L5）。

---

## L0 静态格式（无引导、纯字节）

| 项 | 内容 | 状态 |
|---|---|---|
| 夹具集 | `codec-selftest/fixtures/v2/`：25 张（3 ok / 22 reject）+ `fixtures-v2.json`（oracle 实录），生成器 `gen_fixtures_v2.py` 幂等 | ✅ |
| 四方对账 | 冻结规格 ↔ Python 参照实现(mkdisk --verify) ↔ Kotlin 独立实现(VmdDiskCodec) ↔ oracle 夹具 → 25/25 一致 | ✅ |
| reason 分布 | CORRUPT=12、FORMAT_UNSUPPORTED=2、LEGACY_FORMAT=2、ARCH/SSH_CAP/SSH_PORT/IMAGE_ID/BOOT_MODE/CONTRACT 各1 | ✅ |
| IMP-C01 语义 | ESP 内容破坏 → 读取方**必须接受**（夹具 esp-bootaa64-flip = ok） | ✅ |
| 诊断阶梯 | 尾 `VMDIMG01`/裸 `hsqs` → `LEGACY_FORMAT`；非本格式 → `NOT_AN_IMAGE` | ✅ |
| 自测入口 | `java -jar systemimage-selftest/out/selftest-v2.jar`（含合成阶梯/GUID 往返/FAT32 单元冒烟/夹具对账/可选冒烟模式） | ✅ **34 passed / 0 failed / 1 skipped** |

## L1 构建自检（mkdisk / 打包管线）

| 项 | 内容 | 状态 |
|---|---|---|
| mkdisk 自检 | build 后同 parser 回读（GPT crc/备份表/双魔数/manifest sha/root 全量 sha） | ✅ VERIFY_OK |
| 生成侧断言 | rootfs 用量 ≤0.8S、manifest ≤8044B、ESP 五件齐备、root 恒最后分区、content_id 确定性 | ✅ |
| 真实 rootfs 端到端 | `pack-v2.sh`（真实 Ubuntu squashfs 582MiB → v2 .img+.zst）+ `--verify --full` + 目录/squashfs 双输入 content_id 一致 | ✅ |
| 严格 manifest | `pack-v2 --strict-manifest` + 5 份权威 v2 manifest 零补齐通过；`check-manifest-v2.py` 7 类负例 | ✅ |
| catalog 生产 | `mkcatalog SCHEMA=2`（双 sha 实算一致、签名用应用公钥 `Verified OK`、`SCHEMA=1` 零差异回归、6 类 fail-closed） | ✅（schema2 真资产上 release 前再跑一次） |
| 冒烟 | `pc-boot-smoke-v2.sh --stage bootchain`（双盘拓扑，UEFI→GRUB→内核→initramfs） | ✅ 17.0s |

## L2 应用回归（JVM 侧）

| 项 | 基线 | 状态 |
|---|---|---|
| `compileDebugKotlin` | EXIT=0 | ✅ |
| `assembleDebug` | 出 APK（含 v2 全部改动） | ✅ |
| 全量单测 | 435 tests / **0 failed**（含 ImageCatalogV2Test 15、迁移后的 Catalog/Downloader 测试） | ✅ |
| v1 selftest（兼容） | `out/selftest.jar` **56 passed / 0 failed**（Store/BootGuard 改动后零断言修改） | ✅ |
| Store E2E（临时 JVM main） | 稀疏安装（4% 占用）/verify/activate/dirty 归因两分支/LEGACY_FORMAT/扩容容忍/两级恢复出厂/markBooted 11 项 | ✅ |
| 引擎参数复刻 | QemuEngine v2 参数序列逐条复刻实跑 → podroid-init @13.6s | ✅ |

## L3 PC 引导回归（QEMU 真实启动）

| 项 | 内容 | 状态 |
|---|---|---|
| M2 harness | `/tmp/opencode/m2`（run-all/run-case/assert，**25 断言**；使用规范见 runbook 三铁律） | ✅ **25/25** |
| 正例 | 双盘（vda storage + vdb 镜像）UEFI 全链：PARTUUID 校验 → root 挂载 → /home bind → skel 种子 → switch_root → 假 init PID1 | ✅ |
| 扩容（R7） | 宿主 truncate +512M → guest GPT 迁移 + 内核 reread + resize2fs + **meta 双字段回写** → 扩容后 `mkdisk --verify` = **VERIFY_OK** | ✅ |
| 负例 A/B | meta 魔数破坏、root PARTUUID 错盘 → fail-closed（FATAL、不挂载、不 switch_root） | ✅ |
| AVF 方案 B | ESP 提取 kernel/initrd（sha 对账 R6 语义）+ 直启链路 —— JVM 侧已验；**真机 AVF 启动**归 L4 | ⏳ 待设备 |

## L4 真机（QEMU 手机端 + AVF，**待设备**）

1. P0-1：`tools/m0/p0-1-run.sh` → 方案 A/B/C 裁决（影响 AVF 路径与时延档）
2. 首启到 `Ready!`（真 rootfs 镜像）+ SSH 9922 登录（root/ltbkq）
3. **重启保留**：guest `apt install` 一棵树 → 重启仍在（dirty 状态）
4. 启动可视化：点启动 ≤1s 日志滚动（固件→GRUB→内核→initramfs→Ready!），Running 后收起（双后端）
5. 两级恢复出厂：清数据（storage 清零，.img 不动）/ 重装系统（cache zst 还原 pristine）
6. 扩容：设置页扩系统盘 → 停机 setLength → 下次开机自动扩容 + `Root meta updated`
7. 切换镜像：同 identity 免重置 / 跨 identity 确认清数据 / dirty 镜像切换提示
8. 旧格式：v1 .img 导入 → `LEGACY_FORMAT` 提示重新获取
9. D10 时延档回填：手机 QEMU(TCG) ≤ +5s、AVF(KVM) ≤ +2s（超标回 §0.2 D10 重议）

## L5 体积/性能（P0-4，**待真实构建**）

| 指标 | 采集 | 目标 |
|---|---|---|
| `.img.zst` vs v1 各镜像 | `stat` 对照 `distro-build/out/*.img` | ≤ v1 × 1.5 |
| `.img` apparent / 实际占用 | `stat` vs `du`（稀疏） | 实际 ≈ rootfs 用量 ×1.15 |
| 安装耗时（PC/手机） | pack/解压实测 | PC ≤ 90s；手机待测 |
| 压缩比 | `zstd -l` | 记录即数据 |
| Ready! 时延 | 冒烟计时 | 对照 D10 分档 |

---

## 复跑手册（关键命令）

```sh
# L0：夹具 × codec
K=/media/ltbkq/mydata/tools
$K/kotlinc/bin/kotlinc app/src/main/java/io/github/ltbkq/vmdroid/systemimage/{VmdDiskCodec,EspFat32,MiniJson}.kt \
  systemimage-selftest/SelfTestV2.kt -jvm-target 17 -include-runtime -d systemimage-selftest/out/selftest-v2.jar
java -jar systemimage-selftest/out/selftest-v2.jar

# L1：打包 + 校验 + 冒烟
build-tools/mkdisk.sh --rootfs-dir <dir> --manifest <json> --kernel distro-build/payload/vmlinuz \
  --initrd distro-build/payload/initrd.img --grub-platform vmdroid-app/tools/m0/cache/grub-arm64/usr/lib/grub/arm64-efi \
  --root-size 2048MiB --zstd --no-smoke -o out.img
build-tools/mkdisk.sh --verify --full out.img
build-tools/pc-boot-smoke-v2.sh --stage bootchain out.img

# L2
./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
java -jar systemimage-selftest/out/selftest.jar     # v1 兼容

# L3（harness 使用规范见 runbook：--owner=0:0 / 清理白名单 / 磁盘预算）
cd /tmp/opencode/m2 && bash run-all.sh && bash assert.sh
/media/ltbkq/mydata/vmdroid-app/build-tools/mkdisk.sh --verify grown.img   # R7 闭环

# L4
tools/m0/p0-1-run.sh                                # 设备接入后
```

## 未覆盖 / 环境依赖

- 真 rootfs `--stage ready` 全绿：需一次 sudo（`unsquashfs` 属主无损 → `pack-v2` → smoke ready）
- L4/L5 全部：待设备 / 真实 distro 构建
- ✅ 已落地（2.0.1）：zstd 随 jniLibs 分发（`jniLibs/arm64-v8a/libzstdbin.so`）；Android W^X
  真机实证 error=13 → 按 DESIGN §7.2 T-1 回退方案迁移，`assets/bin/zstd` 已移除；
  glibc 静态 → NDK bionic 静态重编（真机应用域 seccomp SIGSYS/159 实证）
- TBD-1 方案 b 落地后的用户参数生效回归（dpi/bandwidth/dns/userKernelExtras）
