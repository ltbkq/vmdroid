# systemimage-selftest — 工位 F（M3）独立自测

`SystemImageStore` / `BootGuard` / `LogDiagnostics` / `MiniJson` 都是**纯 JVM Kotlin**
（零 `android.*` import），可脱离 Android SDK / Gradle 独立编译运行。本目录提供：无 JUnit
依赖的 `SelfTest.kt`、按冻结规格 `docs/IMAGE-FORMAT.md v1.0` 独立构造 fixture 的
`gen_fixtures.py`，以及编译运行说明。

> 与 `codec-selftest/`（工位 B 的 `VmdImageCodec` 自测）**相互独立**：目录、fixture、
> 断言都不交叉引用。`VmdImageCodec.kt` 只作为被测依赖一起编译。

## 覆盖范围（DESIGN 章节 → 用例）

| 章节 | 用例 |
|---|---|
| §16.3 | `boot_id` 格式 `yyyyMMddTHHmmss-<6hex>`（含固定时钟确定性） |
| §16.2 | 轮转 5 步时序：归档到**上一个** boot_id、本次 meta `finished_at:null`、孤儿 meta 补写 `result=killed`；半截 meta 识别并重建；未 finalize 的 meta 补 `killed`；连续两次启动归档 |
| §16.3 | `meta.json` temp+fsync+rename 原子写（无 `.tmp` 残留）+ **写中断（半截文件）下次读取识别并重建** |
| §16.3/§16.9 | `image.log` 单写线程 O_APPEND 整行写、JSONL 逐行可解析、**轮转 rename→reopen 后新文件非空**、≤3 个文件、`.3` 不存在 |
| §16.5 | `download_progress` 每 5% 或 4MB 节流（取较稀者） |
| §16.9 | `boots/` 配额 **≤10 次 ∧ ≤50MB 双条件**、按 boot_id 时间序**成对删除**、8MB×10 → 实留 6 |
| §7.4 | 11 条权威清单**逐条**触发样本 + 通过样本（13 个触发 case，含 #2 的三种触发、#7 的两种触发） |
| §5.2/§5.3 | `activate` 三态 `same` / `upgrade` / `identity`（返回 `RESET_REQUIRED` 信号：**不写 active.json、不清 storage.img**、`image.log` 留 `activate.decision`）+ 硬判据 `contract` / `init` + `allowReset=true` 确认路径 |
| §6.4 | `verify(full=false)` 只比 size+mtime；`verify(full=true)` 全量 sha256+逐段重算；安装 `.part` 不残留、原子 rename |
| §16.2 (C-R3-6) | **轮转/写失败不阻断启动**：`boots` 被文件占位、`image.log` 是目录 → 不抛到调用方 |

## 1. 生成 fixtures

```sh
cd <worktree根>            # 本例: /media/ltbkq/mydata/vmdroid-app
python3 systemimage-selftest/gen_fixtures.py
```

正向 5 张（`debian-minimal` / `debian-desktop` / `alpine-minimal` / `debian-contract2` /
`debian-openrc`，覆盖 §5.2 全部 decision）+ 负向 7 张（`bad-arch` / `bad-id` / `ssh-off` /
`bad-port` / `app-old` / `format2` / `junk`，对应 §7.4 #3–#9）。

## 2. 编译并运行 SelfTest（无 JUnit）

```sh
cd <worktree根>
K=/media/ltbkq/mydata/tools
$K/kotlinc/bin/kotlinc \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/VmdImageCodec.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/MiniJson.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/SystemImageStore.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/BootGuard.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/LogDiagnostics.kt \
  systemimage-selftest/SelfTest.kt \
  -jvm-target 17 -include-runtime -d systemimage-selftest/out/selftest.jar

java -jar systemimage-selftest/out/selftest.jar            # fixtures 自动向上目录查找
# 或: java -jar systemimage-selftest/out/selftest.jar /path/to/fixtures
```

退出码 0 = 全部通过（每行 `PASS`/`FAIL`，末行汇总）。本机实测：**47 passed, 0 failed**。

> 运行期产物在 `systemimage-selftest/out/tmp/`（每次运行先清空），编译产物 `out/*.jar`、
> `out/classes/` 均可随时删除重建。

## 3. 分层说明（可复现的「纯度」检查）

```sh
grep -rn "^import android" \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/{VmdImageCodec,MiniJson,SystemImageStore,BootGuard,LogDiagnostics}.kt
# 期望：无输出（0 个 android.* import）
```

`SystemImageRepository.kt` 是 **Android 薄壳**（Context / PackageManager / Hilt / logcat），
不含业务逻辑：它只把 `filesDir`、`versionCode` 和 `imageLog` 注入 [SystemImageStore]。
`VmdroidService.kt` 只加两处调用（BootGuard 主判定 + §16.2 轮转），见交付报告。

## 4. v2 自测（SelfTestV2 —— VmdDiskCodec / EspFat32，IMAGE-FORMAT-v2）

`SelfTestV2.kt` 与 `SelfTest.kt` 平行：纯 JVM、无 JUnit，被测依赖为
`VmdDiskCodec.kt`（GPT/meta/manifest 读取算法，§5 权威时序）与 `EspFat32.kt`
（ESP FAT32 只读解析，AVF 回退方案 B 用）。三段式：

1. **合成负样本**：诊断阶梯（`NOT_AN_IMAGE`/`LEGACY_FORMAT`/`CORRUPT` 三分支）、
   mixed-endian GUID 往返、FAT32 单元冒烟（现场 `mkfs.vfat`+`mcopy`，含 LFN/子目录）；
2. **夹具对账**：消费 `codec-selftest/fixtures/v2/fixtures-v2.json`（25 条，oracle 实录），
   `expect=ok` → 解析成功 + 字段可读 + `verifyFull`；`expect=reject` → 拒绝且 reason 映射一致。
   清单缺失时打印 SKIP（并行施工兜底），**不失败**；
3. **冒烟模式**：`java -jar selftest-v2.jar <manifest.json> <img>` 或 `VMD_SMOKE_IMAGE=<img>`
   指定现场镜像跑全流程（read/verifyFull/FAT32 读 ESP 五项文件/partuuid 对账）。

```sh
cd <worktree根>
K=/media/ltbkq/mydata/tools
$K/kotlinc/bin/kotlinc \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/VmdDiskCodec.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/EspFat32.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/MiniJson.kt \
  systemimage-selftest/SelfTestV2.kt \
  -jvm-target 17 -include-runtime -d systemimage-selftest/out/selftest-v2.jar

java -jar systemimage-selftest/out/selftest-v2.jar        # 夹具向上目录查找
```

本机实测（2026-10-12，四方集成对账）：**34 passed, 0 failed, 1 skipped**
（夹具 25/25 与 oracle 期望一致：3 ok / 22 reject；REJECT-REASONS 分布
CORRUPT=12、FORMAT_UNSUPPORTED=2、LEGACY_FORMAT=2、ARCH/SSH_CAP/SSH_PORT/IMAGE_ID/
BOOT_MODE/CONTRACT 各 1（12+2+2+6=22 ✓；CORRUPT=12 含 `app.min_version_code` 缺失项，
2026-10-12 reason 对齐后计数）。

## 4. v2 自测（SelfTestV2，IMAGE-FORMAT-v2）

`VmdDiskCodec.kt`（v2 磁盘镜像读取侧，§5 阶段 1–4）与 `EspFat32.kt`（ESP FAT32 最小只读
解析器，AVF 回退方案 B / 诊断）同样是**纯 JVM Kotlin**（零 `android.*` import），用
`SelfTestV2.kt`（无 JUnit 依赖）自测：

| 段 | 内容 | 依赖 |
|---|---|---|
| 1 | 合成负样本：§5 诊断阶梯（NOT_AN_IMAGE / LEGACY_FORMAT / GPT 头缺失→CORRUPT）+ mixed-endian GUID 往返（§2.2，`uuid.UUID(bytes_le)` 语义） | 无 |
| 2 | `EspFat32` 单元冒烟：`mkfs.vfat`+`mcopy` 现场造 FAT32（8.3 大小写不敏感 + LFN 长名 + 子目录 + 二进制） | 缺工具则 SKIP |
| 3 | 夹具对账：消费 **`codec-selftest/fixtures/v2/fixtures-v2.json`**（工位 B 并行生成，本目录**只读**）：`expect=ok` → read + 字段可读 + verifyFull；`expect=reject` → read/verifyFull 拒绝且 reason 一致。**清单缺失时打 SKIP 不失败** | 清单稍后落盘时自动启用 |
| 4 | 冒烟模式：对 `mkdisk.sh` 现场产物跑 read/verifyFull + FAT32 读 ESP 五项文件（`/vmlinuz`、`/initrd.img`、`/vmdroid/manifest.json`【LFN】、`/grub/grub.cfg`、`BOOTAA64.EFI`）+ FAT 卷 ID ↔ `disk.esp.fat_uuid` 对账 | 需指定镜像 |

### 4.1 编译

```sh
cd <worktree根>            # 本例: /media/ltbkq/mydata/vmdroid-app
K=/media/ltbkq/mydata/tools
$K/kotlinc/bin/kotlinc \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/VmdDiskCodec.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/EspFat32.kt \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/MiniJson.kt \
  systemimage-selftest/SelfTestV2.kt \
  -jvm-target 17 -include-runtime -d systemimage-selftest/out/selftest-v2.jar
```

### 4.2 运行

```sh
# 夹具对账（清单已落盘时；fixtures 相对路径自动向上查找）
java -jar systemimage-selftest/out/selftest-v2.jar \
  codec-selftest/fixtures/v2/fixtures-v2.json

# 夹具对账 + 冒烟（冒烟镜像用 mkdisk.sh 现场造）
java -jar systemimage-selftest/out/selftest-v2.jar \
  codec-selftest/fixtures/v2/fixtures-v2.json /tmp/codec-v2-smoke.img

# 环境变量形式（等价；缺清单 → SKIP）
VMD_FIXTURES_V2=codec-selftest/fixtures/v2/fixtures-v2.json \
VMD_SMOKE_IMAGE=/tmp/codec-v2-smoke.img \
java -jar systemimage-selftest/out/selftest-v2.jar
```

| 参数/环境变量 | 含义 |
|---|---|
| 参数 1 / `VMD_FIXTURES_V2` | 夹具清单路径（缺省自动查找；**缺失只 SKIP**） |
| 参数 2 / `VMD_SMOKE_IMAGE` | 冒烟镜像路径（缺省跳过冒烟） |
| `VMD_SELFTEST_VERSION_CODE` | 模拟应用 versionCode（缺省 1；APP_TOO_OLD 对账用） |
| `VMD_SELFTEST_SKIP_FULL` | `=1` 时跳过阶段 4 全量 root sha（夹具多/盘慢时加速） |

退出码 0 = 全部通过（每行 `PASS`/`FAIL`/`SKIP`/`WARN`，末行汇总）。本机实测：
**37 passed, 0 failed, 0 skipped, 1 warnings**（夹具 25 条 ok=3/reject=22 逐条一致 +
合成 9 条 + 冒烟 3 条；1 WARN 为 `/vmlinuz` 摘要 —— `--kernel` 为 gzip 形态时 ESP 存展开
`Image`，`kernel_sha256` 取自原始入参，属预期）。

> 清单 `reason` 字段是 **mkdisk.sh --verify（oracle，D11）实录文案**而非枚举名；
> `SelfTestV2.expectedReasonOf()` 内置 文案前缀 → §5/§8 十一项 reason 的映射（枚举名
> 直接命中时原样采用）。v2 reason 集合见 `VmdDiskReason`（BootGuard 集成侧的映射由
> BootGuard 工位补 `LEGACY_FORMAT` 枚举后统一做，本目录不改 `BootGuard.kt`）。

### 4.3 纯度检查（同 §3）

```sh
grep -rn "^import android" \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/{VmdDiskCodec,EspFat32,MiniJson}.kt
# 期望：无输出（0 个 android.* import）
```
