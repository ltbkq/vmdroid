# codec-selftest — VmdImageCodec 独立编解码自测

`VmdImageCodec.kt` 是**纯 JVM Kotlin**（零 `android.*` import），可脱离 Android SDK /
Gradle 独立编译运行。本目录提供：无 JUnit 依赖的 `SelfTest.kt`、按冻结规格
`docs/IMAGE-FORMAT.md v1.0` 独立构造的 fixture（`gen_fixtures.py`），以及编译运行说明。

> fixture 为**独立于 mkimg.sh 构造**（对规格可实现性的交叉验证）。若
> `Podroid-Debian/tests/vectors/vectors.json` 已产出，可另行做互操作比对：
> 用其向量替换/补充 `fixtures/` 后重跑即可（断言逻辑不变）。

## 目录

```
codec-selftest/
├── README.md            本文
├── gen_fixtures.py      按 IMAGE-FORMAT §2/§3/§4 构造 fixtures（python3，幂等）
├── gen_fixtures_v2.py   按 IMAGE-FORMAT-v2 §2/§3/§4 构造 fixtures/v2/（正例=mkdisk.sh 打包，
│                        负例=字节级定点篡改；期望=mkdisk.sh --verify 实录，自断言，见 §5）
├── SelfTest.kt          fun main() 自测（纯 stdlib，无 JUnit）
├── fixtures/            生成物：*.img（稀疏）、*.sha256（python hashlib 交叉基准）、sources/
│   └── v2/              v2 生成物：25 条 *.img（稀疏）+ fixtures-v2.json（不入 git）
└── out/                 编译输出（selftest.jar；可删，重新编译）
```

## 0. 一次性准备（本机已就绪）

```sh
# Kotlin 编译器 2.1.0（约 86MB）
mkdir -p /media/ltbkq/mydata/tools
curl -L -o /media/ltbkq/mydata/tools/kotlin-compiler.zip \
  https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip
cd /media/ltbkq/mydata/tools && unzip -q -o kotlin-compiler.zip   # → kotlinc/

# JUnit 4.13.2（跑 JUnit 测试用；SelfTest 不需要）
mkdir -p /media/ltbkq/mydata/tools/jars && cd /media/ltbkq/mydata/tools/jars
curl -sSLO https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
curl -sSLO https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
```

## 1. 生成 fixtures

```sh
cd <worktree根>            # 本例: /media/ltbkq/mydata/vmdroid-app
python3 codec-selftest/gen_fixtures.py
```

## 2. 编译并运行 SelfTest（无 JUnit）

```sh
cd <worktree根>
K=/media/ltbkq/mydata/tools
$K/kotlinc/bin/kotlinc \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/VmdImageCodec.kt \
  codec-selftest/SelfTest.kt \
  -jvm-target 17 -include-runtime -d codec-selftest/out/selftest.jar

java -jar codec-selftest/out/selftest.jar           # fixtures 自动向上目录查找
# 或: java -jar codec-selftest/out/selftest.jar /path/to/fixtures
```

退出码 0 = 全部通过（每行 `PASS`/`FAIL`，末行汇总）。本机实测：**47 passed, 0 failed**。

## 3. 编译并运行 JUnit 测试（给将来 CI）

```sh
cd <worktree根>
K=/media/ltbkq/mydata/tools
CP="$K/jars/junit-4.13.2.jar:$K/jars/hamcrest-core-1.3.jar"
$K/kotlinc/bin/kotlinc -cp "$CP" \
  app/src/main/java/io/github/ltbkq/vmdroid/systemimage/VmdImageCodec.kt \
  app/src/test/java/io/github/ltbkq/vmdroid/systemimage/VmdImageCodecTest.kt \
  -jvm-target 17 -d /tmp/vmd-junit-classes

java -cp "/tmp/vmd-junit-classes:$CP:$K/kotlinc/lib/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore io.github.ltbkq.vmdroid.systemimage.VmdImageCodecTest
```

CI 中 fixtures 缺失时测试类**直接 fail（不 skip）**，可用 `-Dvmd.fixtures=/path` 指定
（缺失即 fail 避免"绿灯假象"，IMP-T03；CI 须先跑 `gen_fixtures.py` 并断言产物存在）。
项目 Gradle 集成后等价于 `./gradlew :app:test`（同一源码，同一断言）。

## 4. 负向向量 → 期望 reason（§8 × §7.4）

| fixture | 期望 reason（bootGuard） |
|---|---|
| random-junk / zip-as-img / too-small / footer-overwritten / **truncate-1byte** / bare-squashfs | NOT_AN_IMAGE |
| file-size-mismatch / spliced-footer / tiny-magic | TRUNCATED (→CORRUPT) |
| format-version-2 / footer-size-bad / unknown-flags | FORMAT_UNSUPPORTED |
| flags-kernel-mismatch / flags-initrd-mismatch / rootfs-offset-nonzero / manifest-out-of-range / manifest-overlap / **kernel-overflow（溢出安全）** | CORRUPT |
| manifest-format-wrong / bad-manifest-json | MANIFEST_INVALID (→NOT_AN_IMAGE) |
| arch-amd64 | ARCH_MISMATCH |
| bad-image-id | IMAGE_ID_INVALID |
| ssh-cap-false / ssh-cap-missing-key | SSH_CAPABILITY_MISSING |
| ssh-port-2222 / ssh-port-bad-type | SSH_PORT_INVALID |
| app-too-old（current=1；999999 时通过） | APP_TOO_OLD |
| manifest-flip（read 期）/ kernel-flip、initrd-flip（verify/extract 期） | PAYLOAD_CORRUPT (→CORRUPT) |
| valid-min / valid-full / minimal-manifest / no-ssh-port | **读取通过**（正向） |

注：截断 1 字节实际先触发 footer 窗口移位 → magic 失败（详见交付报告「规格问题 1」），
§8 的"拒绝"期望满足，仅 reason 与该行描述的机制不同。

## 5. v2 夹具（`docs/IMAGE-FORMAT-v2.md v2.0` · M3 codec 用）

`gen_fixtures_v2.py` 面向**冻结**的 v2 规格，产出 `fixtures/v2/`（已被根 `.gitignore`
的 `/codec-selftest/fixtures/` 覆盖，**不入 git**；本脚本入库）：

```sh
cd <worktree根>                       # 本例: /media/ltbkq/mydata/vmdroid-app
python3 codec-selftest/gen_fixtures_v2.py            # 幂等重生成（25 条夹具，约 70 秒）
python3 codec-selftest/gen_fixtures_v2.py --clean    # 只清理 fixtures/v2/
```

构造哲学与 v1 一致（独立构造 = 对规格的交叉验证），v2 版分工如下：

- **正例由参照实现打包**：`Podroid-Debian/tools/mkdisk.sh`（§6 生成算法，`--no-smoke`
  `--root-size 2048MiB`；同一 rootfs 建两次 → §9 #2 稳定性）。M3 重写后的 Kotlin
  `VmdImageCodec` 与 mkdisk 内嵌的 python parser 是两个独立实现，夹具清单把两者
  钉在同一份期望上。
- **负例 = 正例的字节级定点篡改**（python 偏移写，不用 shell 算术；改 manifest 的
  向量**同步重算 meta 的 manifest_size/manifest_sha256**，改 GPT 表项的**同步重算
  GPT CRC** —— 否则只会命中摘要/CRC 错误而不是目标语义 reason）。
- **期望结果 = oracle 实录**：每条夹具实跑 `mkdisk.sh --verify --full`（§5 读取算法的
  参照实现），实际的 `FAIL stage=… reason=…` 原文写进清单；生成脚本同时自断言
  「规格期望 vs oracle 实际」，不符即退出码 1 —— 清单不可能静默漂移。
- 派生/稀疏：镜像 apparent 2.3 GiB，但按 1 MiB 块零段留洞，25 条夹具实际占盘约 2.8 GB。

### 5.1 `fixtures/v2/fixtures-v2.json`（机器可读期望清单）

顶层对象：`{spec, generated_by, oracle, root_size_mib, note, builds, fixtures[]}`。
`builds` 记录两条正例的 `content_id`/`root_sha256`/`partuuid_root`/`file_size`。
`fixtures[]` 每条：

| 字段 | 含义 |
|---|---|
| `name` / `file` | 夹具名 / `fixtures/v2/` 下的文件名 |
| `expect` | `"ok"`（阶段 1–3 通过）或 `"reject"` |
| `reason` | oracle 的 `FAIL reason=` 原文；`expect=ok` 时为 `null` |
| `spec_ref` | IMAGE-FORMAT-v2 条款（以 §9 向量号为主） |
| `verify_output` | `mkdisk.sh --verify --full` 完整 stdout（正例含 `VERIFY_OK`/`content_id`；`FAIL stage=` 亦在其中） |

### 5.2 夹具 → §9 向量对照（25 条：3 ok / 22 reject）

| fixture | §9 | 期望（oracle 实录） |
|---|---|---|
| `valid-minimal` / `valid-second-build` | #1 / #2 | ok；两次打包 `content_id` 相同、`partuuid_root` 不同 |
| `esp-bootaa64-flip` | #23 | **ok** —— IMP-C01：ESP 内容破坏读取方 MUST NOT reject（单字节：BOOTAA64.EFI 首字节，FAT32 解析定位） |
| `v1-footer` / `bare-squashfs` | #3 / #4 | `LEGACY_FORMAT: v1 footer` / `LEGACY_FORMAT: 裸 squashfs`（stage=ladder） |
| `truncate-1byte` | #5 | `gpt / backup LBA …`（备份 GPT 位置失败） |
| `append-1byte` | #6 | `meta / file_size … != actual …` |
| `gpt-header-crc-flip` | #7 | `gpt / header crc32` |
| `meta-tail-magic-bad` | #8 | `meta / magic_tail` |
| `meta-magic-both-bad` | §3 双魔数 | `meta / magic b'VMDIMG99'`（头+尾一起覆写，头先命中） |
| `manifest-byte-flip` | #9 | `manifest / manifest_sha256 不符` |
| `root-byte-flip` | #10 | `full / root 分区 sha256 不符`（**仅 `--full` 阶段 4 拒绝**，见下注） |
| `gpt-meta-entry-deleted` | #11 | `gpt / 缺 meta 分区（固定 GUID …）` |
| `gpt-root-not-last` | #12 | `layout / root 不是最后一个分区`（表项重排：ESP 移到 root 之后） |
| `boot-mode-direct` | #13 | `manifest / boot.mode='direct' (v2.0 必须 uefi)` |
| `contract-version-1` | #14 | `manifest / contract.version != 2` |
| `arch-x86-64` / `ssh-cap-false` / `ssh-port-2222` / `image-id-slash` | #15 | `arch='x86_64'` / `capabilities.ssh != true` / `accounts.ssh_port != 22` / `image.id 非法（防 -drive 注入）` |
| `app-min-version-missing` | §4.2 IMP-D05 | `manifest / app.min_version_code 缺失`（见下注：与 §4.2 缺省解释有张力） |
| `format-version-3-meta` / `format-version-3-manifest` | #16 | `meta / FORMAT_UNSUPPORTED: format_version=3`（meta 头）/ `manifest / format_version=3`（manifest 侧，meta 同步重算摘要） |
| `flags-0x1` / `flags-0x7` | #17 | `meta / flags 0x1 …` / `flags 0x7 …`（合法掩码 0x3 且 v2.0 必须全置） |

未覆盖的 §9 向量：#18（zst 稀疏等价）、#19（扩容回归）、#20/#21（引导/AVF 冒烟）、
#22/#24（GRUB 模块/`search --fs-uuid`）—— 属打包器/引导侧，不是 codec 判定路径。

使用注意（给 M3 的 Kotlin codec）：

- **档位**：§9 #1 写的是默认 4096 MiB 档，夹具用最小档 2048 MiB（省时省盘）；
  档位差异不改变 §5 任何判定路径，`file_size`/`root_size` 见 `builds` 与各 `verify_output`。
- **`root-byte-flip.img`**：阶段 1–3（启动前快检）**必须放行**（root 分区是 guest 可写的，
  §5「读取方 MUST NOT 拒绝」+ 状态机 dirty 归因），只有 `--full`（阶段 4 / pristine 全量）
  才拒绝。codec 的 `verify(full=true)` 入口应断言 reject，quick 入口应断言 ok。
- **`app-min-version-missing.img`**：§4.2 缺省解释对"无 `app` 块"给 `min_version_code=0`
  （可激活），而 IMP-D05 生成侧必需该键、oracle 对"缺键"拒绝 —— 夹具按 oracle 记
  `reject`；若 M3 codec 按缺省解释放行，需在 codec 侧说明与 oracle 的分歧。
- 夹具 rootfs 是合成小树，含 §4.3 白名单排除项（`/etc/machine-id`、`/var/x`、
  `/etc/ssh/ssh_host_*`、`/home/**`、`/boot/vmlinuz-*`…）与**不**排除的 `/boot/config-*`、
  文件/目录/符号链接各若干。已知参照实现偏差（生成脚本自测会 WARN，非夹具问题）：
  `Podroid-Debian/tools/mkdisk-content-id.py` 的 `EXACT` 集带前导 `/` 而遍历相对路径不带，
  导致 `/etc/machine-id`、`/root/.bash_history` 实际**未被排除**（`/var/**`、`ssh_host_*`
  等正常）。读取方只比对 `content_id` 字符串（§4.3），不影响 codec。
