# VMDroid `tools/` —— PC 启动工具（R-16 / G9）

> 设计依据：[DESIGN.md](../docs/DESIGN.md) **§11.4**（PC 启动，R-16）、§11.2（仓库分工）、
> §16（日志风格）；镜像格式见 [IMAGE-FORMAT.md](../docs/IMAGE-FORMAT.md) §2/§3/§5。
> 文档已冻结 v1.0：实现与文档冲突时**以文档为准并回报**，不擅自改文档。

| 文件 | 说明 |
|---|---|
| `pc-run.sh` | **用户入口**：`tools/pc-run.sh debian.img`（随 Release 分发） |
| `lib/imgboot.sh` | **共享实现库**（纯 POSIX sh + python3），`pc-run.sh` 与 Podroid-Debian 的 `tools/pc-boot-smoke.sh` 共同 source |
| `selftest.sh` | 工位 C 自测（夹具构造、校验/拒绝向量、argv 逐项核对、storage、Ready! 等待） |
| `tests/mkfixture.py` | 最小 `.img` 夹具构造器（仅供 `selftest.sh`，不是发布物） |

---

## 1. 依赖

| 命令 | apt 包 | 必需 |
|---|---|---|
| `qemu-system-aarch64` | `qemu-system-arm` | ✅ |
| `python3` | `python3` | ✅ |
| `mkfs.ext4` | `e2fsprogs` | ✅ |
| `ssh` | `openssh-client` | ✅ |
| `sshpass` | `sshpass` | ⚠️ 可选：**无则 SSH 探活降级为端口探活**（见 §4） |

```sh
sudo apt install qemu-system-arm python3 e2fsprogs openssh-client   # 建议再装 sshpass
```

`pc-run.sh` 启动前会逐项 `command -v` 检查，缺失即打印 `apt install` 指引并退出 1。

---

## 2. 用法（`pc-run.sh`）

```sh
tools/pc-run.sh debian.img                       # 默认：前台启动，console 落 <run-dir>/console.log
tools/pc-run.sh --dry-run debian.img             # 只打印完整 QEMU 命令行（零副作用）
tools/pc-run.sh --smoke --timeout 90 debian.img  # 启动 → 等 Ready! → SSH 探活 → 停机退出
```

| 选项 | 默认 | 说明 |
|---|---|---|
| `--dry-run` | — | 只打印 argv（单行、POSIX shell 转义，可直接 `eval`），**不创建任何文件** |
| `--smoke` | — | 后台启动 → `imgboot_wait_ready` → SSH 探活 → 停 QEMU → 按结果退出 0/1 |
| `--timeout N` | `90` | `Ready!` 超时秒数（与 `boot-test.sh BOOT_TIMEOUT=90` 对齐） |
| `--run-dir DIR` | `./vmdroid-run` | 运行目录：socket / `console.log` / `storage.img` / 提取物 |
| `--storage FILE` | `<run-dir>/storage.img` | 数据盘（vda）路径 |
| `--storage-gb N` | `4` | 首次创建数据盘大小（GiB） |
| `-h` / `--help` | — | 用法 |

行为要点：

- **端口预检（fail-fast）**：`9922/5900/4713` 任一在 `127.0.0.1` 上被占用 → 启动前直接
  报 `preflight FAIL` + 占用者 + 指引并退出 1（这三个端口由启动契约固定、不可改；
  让 QEMU 自己失败只会留下一句 `Could not set up host forwarding rule` 和残留 socket）。
  常见占用者 = 上一次未退出的 `qemu-system-aarch64`。
- **启动前三段式准备**（每段都打 `[t+X.Xs]` 阶段日志）：`verify footer → extract kernel/initrd →
  prepare storage.img → start qemu`。
- `storage.img` **不存在或 0 字节**才 `truncate -s <N>G + mkfs.ext4 -F`；已存在则复用（幂等），
  恢复出厂由 guest `init-podroid` 负责，本工具不重建（DESIGN §4.3）。
- 启动前**清理 `<run-dir>` 下的陈旧 `*.sock`** —— QEMU 对残留 socket 会报
  `Failed to bind socket`，这是手工反复启动最容易踩的坑（端口预检通过后才清理，
  因此不会误删正在运行的实例的 socket）。
- **停机语义（`--smoke`）**：`SIGTERM` → 最多等 10s → 仍存活则 `SIGKILL` → `wait` 收尸
  → 再清一次 socket；保证 `pc-run.sh` 返回时**没有 qemu 残留、端口已释放**。
  （实现要点：后台启动必须写 `sh -c "exec <argv>"`；dash 对后台 `sh -c 'cmd'`
  **不会**自动 exec，`$!` 会是中间 shell 的 pid，停机时 kill 不到真 QEMU。）
- 端口仅绑回环：`9922→22`(ssh `ltbkq`/`123`)、`5900`(vnc)、`4713`(pulse)。

---

## 3. 共享库接口（**冻结**，工位 A 集成契约）

`Podroid-Debian/tools/pc-boot-smoke.sh` 用法：

```sh
REPO_VMDROID=/path/to/vmdroid
# shellcheck source=/dev/null
. "$REPO_VMDROID/tools/lib/imgboot.sh"

imgboot_prepare_run_dir "$RUN_DIR"          # 可选但强烈建议（见下「陈旧 socket」）
imgboot_verify      "$IMG"                  # exit 0/1
imgboot_extract     "$IMG" "$EXTRACT_DIR"   # → $EXTRACT_DIR/vmlinuz, initrd.img
imgboot_storage_init "$STORAGE" 4           # 不存在/0 字节 → truncate + mkfs.ext4 -F
IMGBOOT_RUN_DIR=$RUN_DIR
IMGBOOT_EXTRACT_DIR=$EXTRACT_DIR
ARGV=$(imgboot_qemu_argv "$IMG" "$STORAGE") # 单行、含可执行名，eval 即可执行
eval "$ARGV" >"$RUN_DIR/console.log" 2>&1 &
PID=$!
imgboot_wait_ready "$RUN_DIR/console.log" 90 "$PID"   # 第三参可选
```

| 函数 | 语义 |
|---|---|
| `imgboot_verify <img>` | 校验 footer：**双 magic / file_size / format_version / footer_size(4096) / flags 掩码 0x3 / flags↔段一致性 / 段边界与段序（溢出安全）/ manifest sha256**；再按 IMAGE-FORMAT §4 做 manifest 语义校验（`format`、`image.id` 正则、`arch=arm64`、`capabilities.ssh`、`accounts.ssh_port=22`，缺失字段按 §4 缺省解释）。**stdout 打印关键字段**，失败原因打 stderr，`exit 0/1` |
| `imgboot_extract <img> <outdir>` | 按 footer 偏移提取 → `<outdir>/vmlinuz`、`<outdir>/initrd.img`，**逐一比对 `kernel_sha256`/`initrd_sha256`**（先校验后落盘，临时文件 + `rename`） |
| `imgboot_qemu_argv <img> <storage>` | 打印完整 `qemu-system-aarch64` 命令行：**单行、POSIX 单引号转义、含可执行名**，`eval` 即可执行；`IMGBOOT_ARGV_ONLY=1` 时只输出参数 |
| `imgboot_storage_init <storage> <gb>` | 不存在或 0 字节 → `truncate -s <gb>G` + `mkfs.ext4 -F`；已存在且非空 → 复用并打日志 |
| `imgboot_wait_ready <logfile> <sec> [<pid>]` | 每 0.5s 轮询 `<logfile>` 出现 `Ready!`；超时 **exit 1**；第三参 `<pid>`（**可选扩展**）：进程先亡则立即 exit 1，不空等 |

可选辅助（新增，不改变上述 5 个接口）：`imgboot_prepare_run_dir <dir>`、`imgboot_log <msg>`、
`imgboot_die <msg>`。

### 环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `IMGBOOT_RUN_DIR` | `./vmdroid-run` | `imgboot_qemu_argv` 的 chardev socket 目录（`pc-run.sh --run-dir` 即写入它） |
| `IMGBOOT_EXTRACT_DIR` | `$IMGBOOT_RUN_DIR/extract` | `-kernel` / `-initrd` 的查找目录 |
| `IMGBOOT_ARGV_ONLY` | `0` | `=1` 时 `imgboot_qemu_argv` 不打印可执行名 |

### 调用方必须知道的三件事

1. **失败路径一律 `exit 1`**（与接口约定的 “exit 0/1 / 超时 exit 1” 一致）。
   要捕获失败（负向测试）请放子 shell：`( imgboot_verify bad.img ) && echo ok`。
2. **`imgboot_qemu_argv` 之前要先 `imgboot_prepare_run_dir`**（或自行 `mkdir -p` + `rm -f *.sock`），
   否则 QEMU 会因残留 `terminal.sock/ctrl.sock/host.sock` 报 `Failed to bind socket`。
3. **socket 路径 ≤ 107 字节**（Linux `sun_path`）：`imgboot_qemu_argv` 会实测三条
   `<run>/terminal.sock|ctrl.sock|host.sock`，超长即报错并给指引（`--run-dir /tmp/vr` 之类）。
   同时**拒绝含逗号的路径**（逗号会截断 `-drive`/`-chardev` 选项，DESIGN §6.1 同源风险）。

---

## 4. QEMU 参数清单（DESIGN §11.4，`--dry-run` 与 smoke 共用同一 argv）

| 项 | 取值 |
|---|---|
| 机器/CPU/加速 | `-M virt,gic-version=3` · `-cpu max` · `-accel tcg,thread=multi` |
| 无头必需 | `-display none` · `-serial mon:stdio`（缺则 `Ready!` 无处输出 / 无头机报错） |
| 规格 | `-smp 4` · `-m 4096` |
| 固件 | `-kernel <提取>/vmlinuz` · `-initrd <提取>/initrd.img`（**来自 `.img` 自身 payload**） |
| cmdline | `-append "console=ttyAMA0 mitigations=off androidip=10.0.2.15 podroid.x11.dpi=96"` |
| vda | `-drive file=<storage>,if=none,id=drive1,format=raw,discard=unmap,detect-zeroes=unmap` + `-device virtio-blk-pci,drive=drive1` |
| vdb | `-drive file=<img>,if=none,id=drive2,format=raw,readonly=on` + `-device virtio-blk-pci,drive=drive2` |
| 网络 | `-netdev user,id=net0,ipv6=off,hostfwd=…9922-:22,…5900-:5900,…4713-:4713` + `-device virtio-net-pci,netdev=net0` |
| **hvc0–2** | `-device virtio-serial-pci` + 3× (`-chardev socket,…server=on,wait=off` + `-device virtconsole,chardev=ch0/1/2`) → `<run>/{terminal,ctrl,host}.sock`；**缺则 guest `podroid-getty@hvc0` 无限重启空烧 CPU（R3: B-R2-8）** |

---

## 5. SSH 探活与降级（`--smoke`）

1. 等 `127.0.0.1:9922` 可连（≤30s 重试）；
2. `ssh -o StrictHostKeyChecking=no -o BatchMode=yes -p 9922 ltbkq@127.0.0.1`（密钥/agent）；
3. **有 `sshpass`** → `sshpass -p 123 ssh -o PreferredAuthentications=password …`（真实密码登录断言）；
4. **无 `sshpass`** → 降级为**端口探活**并打印说明 + 手动验证命令，仍算通过。

> CI（GitHub `ubuntu-latest` 默认**不带** `sshpass`）若要满足 R-16 “两条断言都必须进 CI”
> （`Ready!` + `ssh -p 9922` 可登录），请在 workflow 里 `sudo apt-get install -y sshpass`。

---

## 6. 日志

阶段日志统一为 DESIGN §16 风格，零点 = 库被 source 的时刻：

```text
[t+0.0s] pc-run: mode=smoke image=debian.img run_dir=./vmdroid-run
[t+0.1s] run dir ready: ./vmdroid-run (stale *.sock removed)
[t+0.1s] verifying footer: debian.img
imgboot: verify OK — debian.img
  file_size       : 157286400
  ...
[t+1.2s] extracting kernel/initrd -> ./vmdroid-run/extract
[t+1.4s] creating storage: ./vmdroid-run/storage.img (4G sparse + mkfs.ext4 -F)
[t+2.0s] starting qemu-system-aarch64 (background)
[t+2.0s] waiting for 'Ready!' in ./vmdroid-run/console.log (timeout 90s)
[t+34.7s] Ready! detected after 32.6s of waiting
```

`console.log` 是**原始字节流不加前缀**（`Ready!` 等标记靠子串匹配，不能污染），阶段时间只进
stderr/stdout 的 `[t+X.Xs]` 行与调用方自己的 meta。

---

## 7. 自测

```sh
sh tools/selftest.sh        # 全部通过 → exit 0（约 60 项断言，耗时 ~10s）
```

65 项断言，覆盖：

- `bash -n` + `shellcheck`（rc=0）；
- 夹具构造、`verify` 正例（stdout 关键字段）；
- **7 类拒绝向量**：footer magic 单字节翻转 / manifest 翻转 / `flags=0x7`（保留位）/
  `flags=0x1`（flags↔段不一致）/ kernel 翻转 → `extract` 拒绝 / 裸 squashfs / 截断 1 字节；
- `extract` 与独立 `sha256sum` **三方对账**（`sha256sum` == mkfixture == footer）；
- `--dry-run` **逐项核对全部 QEMU 参数**（25 项，见 §4）+ 零副作用；
- socket 路径 >107 字节、路径含逗号 → 拒绝；CLI `--help`/缺参/未知选项退出码；
- `storage_init`（`dumpe2fs -h` 确认 ext4 + 幂等复用）；
- `wait_ready` 命中 / 超时 / `<pid>` 存活 / `<pid>` 已死；
- **`--smoke` 全管线**：假内核 → 预期超时 rc=1、无 qemu 残留、socket 已清；
- **端口预检**：占住 9922 → 启动前拦截、不启 qemu。

环境变量：`IMGBOOT_TEST_DIR`（默认 `/tmp/opencode/vmdroid-imgboot-selftest`，会被清空重建）、
`IMGBOOT_SQFS`（默认 `Podroid-Debian/out/debian-rootfs.squashfs`，不存在则用 1 MiB 假 rootfs）。

---

## 8. 已知限制 / 遗留

1. **夹具内核是假的**：`selftest.sh` 构造的 kernel/initrd 仅结构合法（arm64 Image 头 /
   gzip 头），**不可启动**，因此本轮**未跑真启动**；`tools/pc-run.sh --smoke` 的端到端验收
   待 Podroid-Debian 产出真实 `vmlinuz-virt`/`initrd.img`（R-16 M0 spike）。
2. `imgboot_verify` **不重算 rootfs/kernel 全量 sha256**（345 MB 级别只做 manifest sha256，
   payload sha 在 `imgboot_extract` 时校验）——与 IMAGE-FORMAT §3 规则 7「可延迟到校验动作」一致；
   全文件 sha256 属 catalog/应用侧职责。
3. PC 端**不提供**图形前端、USB/9p 透传（DESIGN §11.4「不承诺」）；`:5900` 可连但需自备 viewer。
4. **`9922/5900/4713` 必须空闲**：端口由启动契约固定（DESIGN §11.4 / §4.8），无法改端口；
   被占时 `pc-run.sh` 会 fail-fast 并打印占用者（如 PulseAudio 占 4713、残留 qemu 占 9922）。
5. `-serial mon:stdio` 在默认前台模式下 stdio 重定向到 `console.log`，**monitor 交互不可用**
   （与 DESIGN §11.4「console 落文件」的写法一致）；如需交互可自行改用 `--dry-run` 输出的 argv。
6. 文档冻结：本轮实现发现的规格问题（如有）只在此登记，**不改 `docs/**`**。
