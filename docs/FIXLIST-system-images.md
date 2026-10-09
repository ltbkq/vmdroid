# VMDroid 系统镜像 · 整改清单（2026-10-09）

> 来源：《TESTPLAN-system-images.md》P0-P2 执行过程中的实测发现。
> 状态：`未修` / `实验验证`（方案已验证但未进构建链）/ `已修`（本轮已落地）
> 优先级：P0 阻断用户主流程 · P1 功能错误/误报 · P2 体验与健壮性 · P3 一致性

## 汇总

| # | 优先级 | 问题 | 状态 | 归属 |
|---|---|---|---|---|
| 01 | **P0** | 镜像页目录指向 `Podroid-Debian`，现有 release 无 `catalog.json` | **已修·已验收** | 应用 + 发布链 |
| 02 | **P0** | NDK 翻译层下 `LD_LIBRARY_PATH` 失效 → QEMU 无法链接 | **已修·已验收** | 应用构建链 |
| 03 | P1 | 翻译层下 guest 启动**慢且边缘**（110–125s，时而停滞） | 已降级·策略已定 | 测试策略 + 长期方案 |
| 04 | P1 | `Ready!` 120s 超时被 **promote 成 Running**（状态误报） | **已修·已验收** | 应用 QemuEngine |
| 05 | P2 | 首启 `asset extraction incomplete` 警告，每次启动重解 | **已修·已验收** | 应用 |
| 06 | P2 | 未授权通知 → 前台服务通知不可见，无 VM 停止入口 | **已修**（代码，未单列用例） | 应用 |
| 07 | P2 | 手动放置镜像缺 `active.json`/`meta.json` 时无自愈与指引 | **已修·已验收** | 应用镜像页 |
| 08 | P2 | 目录签名链（M7）未闭环：无 `catalog_pub.pem` → 裸 HTTPS | **已修·已验收** | 应用 + 发布链 |
| 09 | P3 | `debian-arm64-build1225.img` 的 `image.id` 仍为 `debian-minimal-arm64` | 已澄清·保持现状 | 镜像 manifest |
| 12 | P2 | `am start -a STOP_VM` 被静默吞掉（STOP 只有 broadcast 路径） | **已修·已验收** | 应用 manifest/自动化 |
| 10 | — | `-all-root` 打包导致 `/home/ltbkq` 属主错误（6 镜像） | **已修** | 构建脚本 |
| 11 | — | `terminal-emulator` 单测缺 junit + SystemClock 未 mock（145 例不可跑） | **已修** | 应用构建 |

---

## ISSUE-01（P0）镜像页数据源与现有 release 不符

**现象**：镜像页拿不到任何镜像条目。
**证据**
- `ImageCatalogRepository.DEFAULT_URL = https://github.com/ltbkq/Podroid-Debian/releases/latest/download/catalog.json`
- `HomeImageSelection.CATALOG_RELEASES_URL = https://github.com/ltbkq/Podroid-Debian/releases/latest`
- 而镜像发布在 `ltbkq/vmdroid` 的 `system-images-2026.10.08`，该 release **没有 `catalog.json` 资产**（`gh release view` 实测）
- 对照：应用更新检查已正确指向 `api.github.com/repos/ltbkq/vmdroid/releases/latest`

**影响**：用户打开镜像页 = 空列表/网络错误，无法从发布渠道获得镜像（发布链断裂）。

**整改**
1. 生成 `catalog.json`：5 条目（`image_id/display_name/identity/variant/version/system_version/arch/url/size/sha256/app_min_version_code/notes`），`url` 指向 `https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/<asset>`
2. 作为资产发布到同一 release（`latest/download/catalog.json` 才能被跟随；必要时该 release 设为 latest 或改用固定 tag URL）
3. 应用默认 URL 与浏览器链接改指 `ltbkq/vmdroid`
4. 回归用例：L2 全项

## ISSUE-02（P0）翻译层下 `LD_LIBRARY_PATH` 失效 → QEMU 起不来

**现象**：应用内启动 VM，`F linker: CANNOT LINK EXECUTABLE ".../lib/arm64/libqemu-system-aarch64.so": library "libslirp.so" not found` → `QEMU died during startup, exit code: 1`
**证据**
- `libslirp.so` 确实存在于同一目录（`adb ls /data/app/.../lib/arm64/` 共 7 个 so）
- 手动 `run-as … LD_LIBRARY_PATH=<libdir> <qemu.so> --version` **同样失败**
- 环境：Android 14 x86_64 + `ro.dalvik.vm.native.bridge=libndk_translation.so`，arm64 ELF 经 binfmt `arm64_exe` → `/system/bin/ndk_translation_program_runner_binfmt_misc_arm64`
- **实验验证**：`patchelf --set-rpath '$ORIGIN'` 后同命令输出 `QEMU emulator version 11.0.4` ✅

**影响**：任何 x86 Android（模拟器、部分平板/未来 x86 设备）无法启动 VM；arm64 真机不受影响。

**整改**
- 首选：QEMU 构建链（`build-all.sh qemu` / `Android.mk` 链接步骤）加 `-Wl,-rpath,'$ORIGIN'`，让依赖解析不依赖环境变量
- 备选：`QemuEngine` 改为 `dlopen` 方式加载（不 exec），或 launcher 内部二次 exec 时显式传 env
- 回归：E2 上 `START_VM` 后不再出现 `died`

## ISSUE-03（P1）翻译层下 guest 停滞，本地模拟器无法做 VM 端到端

**现象**：RPATH 修复后 QEMU 正常拉起（`sockets ready after 401ms`、2.4GB RSS），首轮观察 10 分钟无 `Ready!`
**证据**：进程 `State=S`、**仅 7 线程**、CPU 0~4%；同一镜像在 E1 PC 上 30–50s 内 `Ready!`
**后续实测更新（2026-10-09 12:13）**：同一镜像在模拟器上**确实能启动完成**，console 依次出现
`Starting SSH... / Almost ready... / Ready!`，耗时 ≈**110–125s**，且与 120s 超时阈值**边缘交错**
（12:09 那次 120s 时有 2058 字符 console 输出但未见标记 → 走了超时分支；12:13 那次在阈值内到达 Ready!）。
**判断**：翻译层下 **TCG 变慢 ~3–4 倍且波动大**，不是必然死锁 —— 属**环境限制 + 阈值边缘**，非镜像缺陷。

**整改**
- 测试策略：镜像启动验收固定在 E1（PC）与 E3（真机），E2 只测应用层行为（本方案 L2/L3 前半段）
- 长期：`build-all.sh` 支持出 `x86_64` ABI 的 qemu so，模拟器上仅一层 TCG（≈真机性能）
- 应用侧：见 ISSUE-04，停滞时状态必须可见

## ISSUE-04（P1）`Ready!` 超时被 promote 成 Running（状态误报）

**现象**：`W QemuEngine: Ready! not detected within 120s - promoting to Running (boot detection may have missed the marker)`
**证据**：logcat 10:17:24；此时 guest 实际停滞（ISSUE-03），UI 却显示 Running。
**影响**：启动失败被掩盖成「运行中」，用户与自动化都无法判断真伪。

**整改**：超时应进入 `Starting(timeout)` / `Error`，附控制台摘要与「查看日志」入口；只有拿到契约标记才置 `Running`。若要保留容错，至少区分「有 console 输出但无标记」与「完全无输出」。

## ISSUE-05（P2）首启 asset 解包不完整

**证据**：`W VmdroidApp: asset extraction incomplete — leaving stamp stale to force re-extract next launch`
**影响**：每次启动重复解包（IO/耗时），且真正失败原因被吞。
**整改**：记录缺哪个 asset、失败步骤；连续 2 次失败给用户可见提示；校验解包后 sha。

## ISSUE-06（P2）通知未授权 → 前台服务通知不可见

**证据**：`W VmdroidService: POST_NOTIFICATIONS not granted; foreground notification and its Stop action will be invisible while the VM holds the WakeLock`
**影响**：VM 持锁运行时用户没有通知栏停止入口（onboarding 里有 Grant 卡片但非强制）。
**整改**：`START_VM` 前检查权限，未授权则内联请求/引导；或在镜像页常驻「停止 VM」入口。

## ISSUE-07（P2）手动放置镜像缺激活记录时无自愈/指引

**现象**：镜像推到 `files/images/*.img` 后启动被拒：`no activation record (images/active.json) — import or download an image first`
**排查成本**：需自行推导 `active.json` 四字段（`image_id/identity/rootfs_sha256/activated_at`）与 `*.meta.json`（`sha256/size/mtime(ms)/verified_at/format_version`，`mtime` 必须与文件 `lastModified` 毫秒一致），本次靠读 `SystemImageStore.kt` 才复现。
**整改**：镜像页增加「扫描 images 目录 → 补 meta → 引导激活」；错误文案给出可执行步骤。

## ISSUE-08（P2）目录签名链未闭环（M7）

**证据**：仓库无 `res/raw/catalog_pub.pem` → 按 `ImageCatalogRepository` 注释跳过验签，目录仅靠裸 HTTPS。
**影响**：与 §6.1 决策（不降级裸 HTTPS）相悖，下载 URL/sha 可被篡改。
**整改**：生成 ECDSA P-256 密钥对 → 公钥内置 → 每次发布 `catalog.json` 同发 `.sig`（`base64(DER)` 非 MIME、签名对象=原始字节）。

## ISSUE-09（P3）镜像 id 与资产名不一致

**证据**：`debian-arm64-build1225.img` 内 `manifest.image.id = debian-minimal-arm64`、`display_name` 已改为「开发」但 `variant` 仍 `minimal`。
**影响**：页面显示与文件名不一致；与历史 `debian-minimal-arm64` 资产重名，可能与用户旧安装混淆。
**整改**：统一（推荐保留 `image.id` 不变以维持 `debian:trixie` 数据盘，改资产名；或反之，需评估已装用户）。

## ISSUE-12（P2）`STOP_VM` 的 activity 路径被静默吞掉

**现象**：`adb shell am start -a ...action.STOP_VM -n <pkg>/MainActivity` 返回
`Activity not started, intent has been delivered to currently running top-most instance`，
**但 QEMU 继续运行**（进程 `ETIME` 持续增长、首页仍显示 `Running`、镜像页横幅
`Stop the VM first` 与 `Verify now`/`Factory reset` 持续禁用）。
**证据**
- `AndroidManifest.xml`：`MainActivity` 的 intent-filter **只有 `START_VM`**（行 106）；
  `VmControlReceiver` 才同时有 `START_VM` + `STOP_VM`（行 157-158）
- `MainActivity.onNewIntent` 只处理 `ACTION_START_VM`
- 正确路径实测有效：`am broadcast -a ...STOP_VM -n <pkg>/.service.VmControlReceiver`
  → `ACTION_STOP: VM active — deferring teardown to state observer` → `QEMU exited: 0` → `WakeLock released`

**影响**：Tasker/自动化/CI 若按 START 的同款写法停机 → 静默失败，VM 持续占内存与 WakeLock；
测试脚本也容易误判（本轮即踩坑一次：以为已停机，实则仍在运行）。

**整改**
1. `MainActivity.onNewIntent` 同样处理 `ACTION_STOP_VM`（或显式拒绝并 `Log.w` + 返回结果）
2. 或在 manifest 上给 activity 也挂 `STOP_VM`，保持两个 action 对称
3. README/自动化文档写明停止路径；`shell` 层建议统一走 broadcast

> 备注（排除误报）：镜像页 `Stop the VM first` 横幅与按钮禁用在 VM 运行期间是**正确**状态，
> 不作为缺陷；只有在 VM 已停后仍不刷新才算问题（本轮未观察到）。

## ISSUE-10（已修）`-all-root` 打包把家目录属主改成 root

**现象**：`Failed chdir '/home/ltbkq': Permission denied`、`.bashrc` 不可读。
**根因**：`mksquashfs -all-root`；而 overlay 由宿主 `cp -a` 带入的 uid 1000 目录本需修正。
**修复**：只把 uid=1000 的 overlay 文件改回 `root:root`，排除 `/home`、mail；打包后 `unsquashfs -lln` 自检属主。6 个镜像全部重打包并回归通过。

## ISSUE-11（已修）`terminal-emulator` 单测不可运行

**现象**：`程序包junit.framework不存在`（100 编译错误）→ 修依赖后 108/145 失败于 `SystemClock.uptimeMillis not mocked`。
**修复**：`testImplementation(libs.junit)` + `testOptions.unitTests.isReturnDefaultValues = true` → **145/145 通过**（app 模块 419 例亦 0 失败）。


---

## 整改验收记录（2026-10-09，模拟器 E2 + PC E1）

| ISSUE | 验收方式 | 结果 |
|---|---|---|
| 01 | 镜像页实测：目录 5 条（Alpine/Ubuntu/Debian/Fedora/Arch）+ `sha256 ✓` + 无 404 | ✅ 5s 内就绪 |
| 02 | `START_VM` 后 `ps` 见 QEMU、logcat 无 `libslirp.so not found`；`patchelf --print-rpath` = `$ORIGIN`，LOAD 对齐 `0x4000/0x10000` | ✅ |
| 03 | 见上文实测更新；策略：VM 级验收以 E1/E3 为准，E2 仅做应用层 | ✅ 策略落文档 |
| 04 | 确定性用例（2MiB 截断内核的坏镜像）：`boot timeout: no console output after 120s — stopping QEMU` → UI 错误卡「启动超时：…（客户机未启动）」→ QEMU 已停 | ✅ |
| 05 | 删 `.assets_stamp` 后重启：无 `asset extraction incomplete`、stamp 写入、`files/efi-virtio.rom` + `files/keymaps/`(34 项) 解出 | ✅ |
| 06 | 代码：`holdPermissionsCard = isRunning||isStarting||isStopping` 让权限卡运行期不可收起 | ✅（代码评审级） |
| 07 | 改名 `active.json` 后启动：logcat `QemuEngine: start blocked: … open System images, import a .img file…` + Home 错误卡同文案 | ✅ |
| 08 | `mkcatalog.sh` 生成 5 条 + 签名，远端 `catalog.json`/`.sig` 与本地一致，应用内验签通过（无 `SIGNATURE_*` 文案） | ✅ |
| 09 | 保持现状：资产名不受 §2.3 约束（安装路径取 `image.id`），catalog 已用 `image.id` | ✅ 文档澄清 |
| 10 | `unsquashfs -lln` 打包后自检：alpine/ubuntu/debian/fedora=`1000/1000`、arch=`1001/1001` | ✅ 6 镜像 |
| 11 | `./gradlew testDebugUnitTest`：app **419** + terminal-emulator **145** = **564 例 0 失败** | ✅ |
| 12 | `am start -a …STOP_VM` → `QEMU exited: 0` + `WakeLock released` | ✅ |

**本轮整改引入的代码位置**：`MainActivity`（STOP 对称）、`AndroidManifest`（intent-filter）、`QemuEngine`
（超时分诊 + deferredError）、`EngineHolder`（转发 + 归一化放行）、`VmEngine`（`reportStartBlocked`）、
`AvfEngine`、`VmdroidService`（拒绝上报）、`VmdroidApplication`（删过期解包任务）、`BootGuard`（可操作文案）、
`HomeScreen`（超时警告行 + 权限卡锁定）、`ImageCatalogRepository`/`HomeImageSelection`（目录地址）、
`res/raw/catalog_pub.pem`（公钥）、`Dockerfile`+`build-all.sh`（RPATH）、
`terminal-emulator/build.gradle.kts`（JUnit + mock）。

---

## 待办分派建议

| 负责域 | 事项 |
|---|---|
| 发布链 | ISSUE-01 catalog.json 生成/上传、ISSUE-08 签名链 |
| 应用构建 | ISSUE-02 RPATH 进构建、（可选）x86_64 ABI |
| 应用运行时 | ISSUE-04 状态机、ISSUE-05 解包、ISSUE-06 权限、ISSUE-07 引导 |
| 镜像 | ISSUE-09 命名统一（需决策） |
| 测试 | E1/E3 承担 VM 级验收；E2 只做应用层 |
