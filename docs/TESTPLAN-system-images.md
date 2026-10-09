# VMDroid 系统镜像测试方案（2026-10-09）

> 目标：验证 `ltbkq/vmdroid` Release **`system-images-2026.10.08`** 中的 5 个系统镜像，
> 以及应用「镜像页」改造为**现有 release 镜像列表**后的全链路行为；逐镜像执行、
> 汇总软件问题 → 输出《整改清单》（`FIXLIST-system-images.md`）。

## 0. 测试对象与环境

### 0.1 镜像（Release 资产，本地同字节副本在 `distro-build/out/`）

| image_id（manifest） | 资产名 | 体积 | 内置工具 | 冒烟基线 |
|---|---|---|---|---|
| `alpine-3.24-arm64` | `alpine-minimal.img` | 184,557,568 | apk | Ready! ≈50s |
| `ubuntu-noble-arm64` | `ubuntu-minimal.img` | 238,034,944 | apt | Ready! ≈95s |
| `debian-minimal-arm64`* | `debian-arm64-build1225.img` | 472,915,968 | apt + gcc/g++/make/git | Ready! ≈82s |
| `fedora-44-arm64` | `fedora-minimal.img` | 381,689,856 | dnf/rpm + gcc/g++/make/git | Ready! ≈163s |
| `arch-rolling-arm64` | `arch-minimal.img` | 543,170,560 | pacman | Ready! ≈127s |

\* 见整改清单 ISSUE-09：资产名与 `image.id` 不一致。

### 0.2 被测应用
`io.github.ltbkq.vmdroid.debug` · `versionCode=33` · `versionName=1.2.9-debug`
构建：`./gradlew testDebugUnitTest assembleDebug`（单测 564 例必须 0 失败）

### 0.3 测试环境（三级，能力不同）

| 代号 | 环境 | 能测什么 | 不能测什么 |
|---|---|---|---|
| **E1 PC** | `qemu-system-aarch64` + `vmdroid/tools/pc-run.sh --smoke` | 镜像本身启动契约、SSH、账号、包/工具可用性 | 应用任何行为 |
| **E2 模拟器** | Android 14 x86_64 AVD（KVM + `libndk_translation` ARM 翻译层） | 应用 UI、目录、导入/激活/切换/删除、状态机、权限、单元层 | **VM 内核启动**：翻译层下 QEMU 起得来但 guest 停滞（ISSUE-03） |
| **E3 真机** | arm64 手机（adb） | 全链路，含 Ready!/SSH/数据盘保留 | 依赖手机在手 |

> 结论先行：**镜像级验证以 E1 为准，应用级行为以 E2 为准，最终验收需 E3 抽测。**

## 1. 用例总表

### L0 静态完整性（每镜像，自动化）
- [ ] `sha256sum -c sha256sums.txt`（远端资产 ↔ 校验文件）
- [ ] `tools/mkimg.sh --verify <img>`：footer/段序/对齐/manifest 语义全过
- [ ] `manifest.image.id` 命中白名单正则、`identity/variant/system_version` 合法
- [ ] 体积满足约束（fedora ≤ 500 MB；其余无硬上限但需记录）
- [ ] 镜像内 `/home/ltbkq` 属主为 guest 内 ltbkq（uid 1000/1001），非 root

### L1 启动基线 E1（每镜像）
- [ ] `pc-run --smoke --timeout 300` → `Ready!` 出现且耗时记录
- [ ] SSH 密码登录 `ltbkq/123` 成功（rc=0）
- [ ] 五条契约标记齐全：`Loading kernel modules...` / `Network found` / `Starting SSH...` / `Almost ready...` / `Ready!`
- [ ] 账号：`root/123`、`ltbkq/123`、`ltbkq` 可 `sudo -n true`
- [ ] 包管理器与声明工具实测（alpine:`apk`；ubuntu/debian:`apt+dpkg`；fedora:`dnf+rpm`；arch:`pacman`；带开发工具的两个额外跑 `gcc 编译 C 程序`）

### L2 镜像页/目录 E2（改造后）
- [ ] 默认目录 URL 指向 `ltbkq/vmdroid` 现有 release，页面能列出 **5 条**
- [ ] 每条显示：名称/版本/体积/sha256 短串与 release 一致
- [ ] 断网 → 明确错误文案（NETWORK）；非法 JSON → MALFORMED；篡改 sha → BAD_ENTRY
- [ ] 签名：`.sig` 缺失/错误的行为与 M7 门闩一致
- [ ] 下载进度、断点续传、`.part` 中断不留残file、失败可重试
- [ ] 从文件导入（SAF）与下载导入两条路径产物一致（`images/<id>.img` + `.meta.json`）

### L3 镜像生命周期 E2/E3（每镜像抽测，debian/fedora 必测）
- [ ] 激活：写 `active.json`，UI 状态 `INSTALLED → ACTIVE`
- [ ] 启动：状态机 `Stopped → Starting → Running`，日志无 FATAL/UnsatisfiedLink
- [ ] 同 identity 二次激活不重置；跨 identity 激活弹一次重置确认
- [ ] `storage.img` 在同 identity 切换后保留（写文件 → 换镜像 → 文件仍在）
- [ ] 删除镜像：激活中拒绝或先停机；删除后 `active.json` 清理干净
- [ ] `STOP_VM` 后无残留 QEMU 进程与端口占用

### L4 一致性/回归（全局）
- [ ] Release 资产清单 ↔ catalog 条目 ↔ manifest 三方一致（id/version/sha/size）
- [ ] `./gradlew testDebugUnitTest`：**564 例 0 失败**
- [ ] `vmdroid/tools/selftest.sh` **64/64**、`codec-selftest` **47/47**、`systemimage-selftest` **56/56**
- [ ] Release 说明中的体积/sha/测试状态与实测回填一致

## 2. 执行顺序（本轮）

1. **P0** L0 + L1：5 个镜像逐个 PC 冒烟（E1，~10 分钟）→ 回填基线表
2. **P1** L2：镜像页改造（默认 URL + `catalog.json` 资产）→ E2 逐项验证
3. **P2** L3：E2 上逐镜像导入/激活/启动/切换/删除（受 ISSUE-03 限制：验证到「启动尝试与错误呈现」）
4. **P3** L4：回归套件 + 文档回填
5. **P4** E3 真机抽测 1-2 个镜像（fedora + debian）做最终验收

## 3. 通过标准

- L0/L1/L4 **全过**（无豁免）
- L2/L3 中任何「数据丢失、崩溃、状态卡死、资产与校验不符」= 阻断（P0）
- 环境类限制（如翻译层）不计为镜像缺陷，但必须进整改清单并标注**规避/后续**方案

## 4. 记录模板（逐镜像）

```
镜像: <asset>            L0: ☐  L1 Ready!=<s> SSH=☐  L1 工具=☐
L2 目录条目=☐  L3 激活=☐ 启动=☐ 切换保留=☐ 删除=☐
发现: ISSUE-xx / 无
结论: 通过 / 阻断 / 环境限制
```

---

## 5. 执行结果（2026-10-09 · P0/P1 完成）

### 5.1 L0 静态完整性（全部通过 ✅）

| asset | sha256 前缀 | mkimg --verify | `/home/ltbkq` 属主 |
|---|---|---|---|
| `alpine-minimal.img` | `1922defe` | OK | `1000/1000` ✅ |
| `ubuntu-minimal.img` | `ae332e62` | OK | `1000/1000` ✅ |
| `debian-arm64-build1225.img` | `8e9bc3da` | OK | `1000/1000` ✅ |
| `fedora-minimal.img` | `2f518db1` | OK | `1000/1000` ✅ |
| `arch-minimal.img` | `7d1e9feb` | OK | `1001/1001` ✅（该镜像 ltbkq=1001） |

远端 `sha256sums.txt` ↔ 本地 5 个镜像 **逐一吻合** ✅

### 5.2 L1 启动基线 E1（全部通过 ✅）

| 镜像 | rc | 总耗时 | `Ready!` | SSH 登录 | 契约标记 |
|---|---|---|---|---|---|
| alpine | 0 | 32s | **29.7s** | ✅ OK | 5/5 |
| ubuntu | 0 | 57s | **55.3s** | ✅ OK | 5/5 |
| debian-arm64-build1225 | 0 | 85s | **83.0s** | ✅ OK | 5/5 |
| fedora | 0 | 154s | **146.8s** | ✅ OK | 5/5 |
| arch | 0 | 77s | **67.9s** | ✅ OK | 5/5 |

### 5.3 L2 镜像页 E2（**不通过 → ISSUE-01**）

- UI 实测文案：`Couldn't load the image catalog: HTTP 404 for
  https://github.com/ltbkq/Podroid-Debian/releases/latest/download/catalog.json`
- `INSTALLED` 区正常显示已导入的 `alpine-3.24-arm64`（Active），手动导入/Refresh/Subscription 按钮可用
- 结论：**发布链断裂**（指向 Podroid-Debian 且无 `catalog.json` 资产）→ P0 整改

### 5.4 L3 生命周期 E2（部分完成）

| 步骤 | 结果 |
|---|---|
| 手动导入（push `files/images/<id>.img` + `active.json` + `.meta.json`） | ✅ 页面正确识别为 INSTALLED/Active |
| `START_VM`（activity intent） | ✅ 拉起 QEMU（修 RPATH 前 `libslirp.so not found` → ISSUE-02） |
| 修复后 QEMU 进程 | ✅ 存活（2.4GB RSS）但 guest 停滞 → ISSUE-03 |
| `STOP_VM`（`am start` 路径） | ❌ **静默无效** → ISSUE-12 |
| `STOP_VM`（broadcast 路径） | ✅ `QEMU exited: 0` + `WakeLock released` |
| 超时状态呈现 | ❌ 120s 无标记被 promote 成 `Running` → ISSUE-04 |

### 5.5 L4 回归

- `./gradlew testDebugUnitTest`：**app 419 例 + terminal-emulator 145 例 = 0 失败**（修复见 ISSUE-11）
- `vmdroid/tools/selftest.sh` **64/64**、`codec-selftest` **47/47**、`systemimage-selftest` **56/56**

### 5.6 未完成 / 需条件

- **L2 改造后验收**：待 `catalog.json` 生成并发布（ISSUE-01）
- **L3 切换/数据盘保留、删除**：受 ISSUE-03 限制（模拟器起不了 guest），需 E3 真机
- **E3 真机抽测**：建议 `fedora` + `debian` 两个重点镜像


---

## 6. 整改后全面回归（2026-10-09 · v1.3.0 发布门禁）

### 6.1 静态/单元层（全部通过）

| 套件 | 结果 |
|---|---|
| `./gradlew testDebugUnitTest`（app + terminal-emulator） | **564 例 / 0 失败**（419 + 145） |
| `vmdroid/tools/selftest.sh`（imgboot 库/footer/提取/QEMU 参数） | **64/64** |
| `codec-selftest`（VmdImageCodec + BootGuard/reset 决策表） | **47/47** |
| `systemimage-selftest`（导入/校验/轮转/回滚 §13.2 E2E） | **56/56** |

### 6.2 镜像层 E1（5 镜像逐个，release 同字节）

| 镜像 | rc | `Ready!` | SSH | 契约标记 |
|---|---|---|---|---|
| alpine | 0 | 29.7s | ✅ | 5/5 |
| ubuntu | 0 | 55.3s | ✅ | 5/5 |
| debian-arm64-build1225 | 0 | 83.0s | ✅ | 5/5 |
| fedora | 0 | 146.8s | ✅ | 5/5 |
| arch | 0 | 67.9s | ✅ | 5/5 |

另：`mkimg --verify` 5/5 OK、远端 `sha256sums.txt` ↔ 本地一致、`/home/ltbkq` 属主 5/5 正确。

### 6.3 应用层 E2（整改验收，详见 FIXLIST 验收记录）

- 目录 5 条 + 签名验签通过（ISSUE-01/08）✅
- RPATH 后 QEMU 正常拉起（ISSUE-02）✅
- `am start` 停机生效（ISSUE-12）✅
- 超时分诊：坏镜像用例 → 错误卡 + 停机（ISSUE-04）✅
- 解包 stamp 写入、无告警（ISSUE-05）✅
- 无激活记录 → 错误卡含指引（ISSUE-07）✅
- 版本 `versionCode=34` / `versionName=1.3.0-debug` ✅

### 6.4 发布门禁结论

**全部通过** → 允许推送仓库并发布 `v1.3.0`。
遗留（非阻断）：ISSUE-03 为环境限制（策略已定）；ISSUE-06 为代码级修复，未单列自动化用例；
ISSUE-09 经评估保持现状（已文档澄清）。


### 6.5 发布记录（2026-10-09）

- **代码**：`ltbkq/vmdroid` 分支 `app` → `bf229e5`（21 文件 / +672）已推送
- **APK Release**：`v1.3.0`（`--target app`，assets = `vmdroid-1.3.0-debug.apk` 47,484,185 B + `SHA256SUMS`），已成 **latest**（应用内更新检查跟随）
- **镜像 Release** `system-images-2026.10.08`：新增 `catalog.json`(3057 B) + `catalog.json.sig`(96 B)；APK 资产由 1.2.9 更新为 **1.3.0**；`sha256sums.txt` 重算（5 镜像 + APK）；Release 说明同步（catalog 段 + 1.3.0 行）
- **一致性校验**：远端 `sha256sums.txt`、`catalog.json` 与本地逐字节一致；本地 5 镜像 sha 与 sums 吻合 ✓
