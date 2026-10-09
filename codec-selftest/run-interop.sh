#!/usr/bin/env bash
#
# run-interop.sh — 工位 G 一键互操作验证（DESIGN §13.1「工具侧互操作」）
#
#   1) 生成 fixtures（Podroid-Debian/tests/gen-vectors.sh，幂等；缺才跑，跑完复查）
#   2) 编译 VmdImageCodec.kt + codec-selftest/InteropTest.kt → out/interop.jar
#   3) 运行 InteropTest（21 向量回放 / footer_layout 偏移回归 / mkimg 反向构建 /
#      catalog 签名 openssl 验签 / fixture 缺失必须失败）
#   4) 反向探测：fixtures 根指向不存在目录时，测试必须以退出码 1 失败
#      （禁止 Assume 式静默显示 OK）
#
# 用法：
#   ./run-interop.sh                        # 自动定位同级 Podroid-Debian
#   VMD_FIXTURES=/abs/Podroid-Debian ./run-interop.sh
#   ./run-interop.sh --fixtures /abs/Podroid-Debian
#
# 退出码：0 全部通过 · 1 任一失败（含 fixture 缺失）
#
set -euo pipefail

SELF=$(readlink -f "$0")
SELF_DIR=$(cd "$(dirname "$SELF")" && pwd)
APP_ROOT=$(cd "$SELF_DIR/.." && pwd)                       # vmdroid-app
WS_ROOT=$(cd "$APP_ROOT/.." && pwd)                        # 工作区根

KOTLINC=${VMD_KOTLINC:-$WS_ROOT/tools/kotlinc/bin/kotlinc}
STDLIB=$WS_ROOT/tools/kotlinc/lib/kotlin-stdlib.jar
CODEC=$APP_ROOT/app/src/main/java/io/github/ltbkq/vmdroid/systemimage/VmdImageCodec.kt
OUT_JAR=$SELF_DIR/out/interop.jar

# ---- fixtures 根：--fixtures 参数 > VMD_FIXTURES 环境变量 > 同级 Podroid-Debian ----
FIXTURES=${VMD_FIXTURES:-$WS_ROOT/Podroid-Debian}
while [ $# -gt 0 ]; do
    case "$1" in
        --fixtures) [ $# -ge 2 ] || { echo "run-interop: --fixtures needs a value" >&2; exit 1; }
                    FIXTURES=$2; shift 2 ;;
        --fixtures=*) FIXTURES=${1#--fixtures=}; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "run-interop: unknown argument: $1" >&2; exit 1 ;;
    esac
done
FIXTURES=$(readlink -f "$FIXTURES")

die() { echo "run-interop: ERROR: $*" >&2; exit 1; }

[ -d "$FIXTURES" ] || die "fixtures 根不存在: $FIXTURES"
[ -f "$CODEC" ] || die "找不到 VmdImageCodec.kt: $CODEC"
[ -x "$KOTLINC" ] || die "找不到 kotlinc: $KOTLINC (set VMD_KOTLINC=...)"
[ -f "$STDLIB" ] || die "找不到 kotlin-stdlib.jar: $STDLIB (运行时 classpath 必须包含它)"

# ---------------------------------------------------------------- 1. fixtures
VECJSON=$FIXTURES/tests/vectors/vectors.json
IMGDIR=$FIXTURES/tests/data/vectors
if [ ! -f "$VECJSON" ] || [ ! -d "$IMGDIR" ]; then
    echo "run-interop: fixtures 缺失 → 运行 gen-vectors.sh 生成"
    [ -x "$FIXTURES/tests/gen-vectors.sh" ] || die "找不到 $FIXTURES/tests/gen-vectors.sh"
    (cd "$FIXTURES" && ./tests/gen-vectors.sh)
fi
# 生成后复查（缺失 = 硬失败，绝不静默跳过）
[ -f "$VECJSON" ] || die "gen-vectors.sh 之后仍缺 $VECJSON"
[ -d "$IMGDIR" ] || die "gen-vectors.sh 之后仍缺 $IMGDIR"
N_IMG=$(find "$IMGDIR" -maxdepth 1 -name '*.img' | wc -l)
# 21 条向量 = 20 个 .img + 1 条裸 squashfs（bare-rootfs.sqfs，非 .img）
[ "$N_IMG" -ge 20 ] || die "$IMGDIR 只有 $N_IMG 个 .img（期望 20）→ ./tests/gen-vectors.sh"
echo "run-interop: fixtures OK · $VECJSON · $IMGDIR ($N_IMG imgs)"

# ---------------------------------------------------------------- 2. 编译
mkdir -p "$SELF_DIR/out"
echo "run-interop: 编译 VmdImageCodec.kt + InteropTest.kt → $OUT_JAR"
"$KOTLINC" "$CODEC" "$SELF_DIR/InteropTest.kt" \
    -jvm-target 17 -include-runtime -d "$OUT_JAR"

# ---------------------------------------------------------------- 3. 运行
echo "run-interop: java -Dvmd.fixtures=$FIXTURES -jar $OUT_JAR"
set +e
java -Dvmd.fixtures="$FIXTURES" -jar "$OUT_JAR"
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
    echo "run-interop: 测试失败 exit=$RC（下方仍执行缺失反向探测）" >&2
fi

# ---------------------------------------------------------------- 4. 反向探测
# fixtures 缺失时必须 exit 1（证明没有 Assume 式静默 OK）
echo "run-interop: 反向探测 —— fixtures 缺失时必须 exit 1"
set +e
java -Dvmd.fixtures="$FIXTURES/definitely-missing" -jar "$OUT_JAR" >/tmp/vmd-interop-missing.log 2>&1
MISS_RC=$?
set -e
if [ "$MISS_RC" -ne 1 ]; then
    sed -n '1,5p' /tmp/vmd-interop-missing.log >&2
    die "fixtures 缺失时退出码=$MISS_RC（期望 1）→ 存在静默跳过隐患"
fi
echo "run-interop: PASS 反向探测 exit=$MISS_RC（缺失即失败）"
if [ "$RC" -ne 0 ]; then
    echo "run-interop: FAIL exit=$RC（见上方失败明细）" >&2
    exit "$RC"
fi
echo "run-interop: ALL OK"
exit 0
