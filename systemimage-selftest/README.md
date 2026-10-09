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
