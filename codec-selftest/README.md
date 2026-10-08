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
├── SelfTest.kt          fun main() 自测（纯 stdlib，无 JUnit）
├── fixtures/            生成物：*.img（稀疏）、*.sha256（python hashlib 交叉基准）、sources/
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
