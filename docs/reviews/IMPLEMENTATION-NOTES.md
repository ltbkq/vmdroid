# 实现期规格问题登记册（R4 变更提案输入）

> 来源：第一波工位 A/B/C/D/G 实现报告 · 核实日期 2026-10-08 · 文档冻结于 v1.0（cc820c4）
> 本文只登记，不修改冻结文档。

- **冻结文档（本册只读）**：`docs/DESIGN.md`、`docs/IMAGE-FORMAT.md`、`docs/REVIEW.md`、`docs/reviews/R1.md`–`R3.md`
- **本文件性质**：评审日志类新增文件（R1–R3 同类），是 R4 变更提案的**唯一输入**；未经 R4 决议不得据此改冻结文档。
- **核实方法**：每条线索都先在冻结文档中定位到确切章节（下表"位置"列带行号 `L`，行号取自 cc820c4 之后的工作树），引用原文；凡能在本机验证的一律实测，证据写在"问题"或 §4/§5 列内。主要证据工具：`python3`（按 §5 伪代码复演诊断阶梯）、`unsquashfs -s`（superblock）、`grep`（三仓库源码与文档）、`stat`/`sha256`。
- **严重度口径**（`REVIEW.md §2`）：`Blocker` = 会导致实现失败/需求不满足/文档自相矛盾到无法执行（当轮必修）；`Major` = 造成返工或关键路径遗漏（当轮必修）；`Minor` = 表述/次要遗漏（登记）；`Info` = 建议（记录）。
- **线索覆盖**：原始线索 30 条 / 去重后 29 条（B1 与 A1 同一问题，合并登记于 IMP-D01）。全部已逐条落位，其中 3 条判为"不成立/风险已被覆盖"（见 §4），1 条属实现陷阱不需改文档但降级登记（C4 → IMP-C11）。

---

## 0. 如何使用（R4 流程提示）

1. **ID 稳定可引用**：R4 提案请直接引用 `IMP-Dxx` / `IMP-Cxx` / `BUG-xx` / `IMP-Txx`，并在提案里复述"建议改动"列的原文，避免二次转述走样。
2. **分类决定动作**，别混用：
   - `§1 规格缺陷` → **改冻结文档**（错/漏/自相矛盾，R4 必须给替换文本）；
   - `§2 实现约定` → 文档没错但**没规定**，R4 只需**钦定一句话**（选一个方向写死），否则各实现会分叉；
   - `§3 真 bug` → **改代码**，文档不动；
   - `§4` → 不进 R4，留档防止重复上报；
   - `§5` → 改**测试/构建基建**，其中 IMP-T04 与文档无关但阻塞 M1 验收。
3. **决议前的冻结纪律**：R4 决议之前，各实现工位**不得**按自己的理解继续扩散，尤其这三处会直接造成互操作分叉：`IMP-C01`（读取方是否校验 §2 构建约束）、`IMP-D03`（校验顺序/reason 归属）、`IMP-D04`（§7.4 reason 枚举）。
4. **每条"建议改动"都是可直接粘贴的替换文本方向**，本册**未执行**任何修改；R4 采纳后由文档 owner 统一落笔并 bump 版本（§16.5 之类引用处需同步）。
5. 本册不复述 R1–R3 已关闭问题；若某条与 R1–R3 结论冲突，以"实现期实测证据"为新证据走 R4 重开流程。

---

## 1. 规格缺陷（文档错/漏/自相矛盾）

| ID | 位置 | 引用原文 | 问题 | 建议改动 | 交叉印证来源 | 严重度 |
|---|---|---|---|---|---|---|
| **IMP-D01** | `IMAGE-FORMAT.md §8`（L324）× `§3 规则3`（L117）× `§5`（L232–235） | §8：`> 文件截断 1 字节 \| footer.file_size != size → 拒绝`；§5：`> if footer.magic != "VMDIMG01" or footer.magic_tail != "VMDIMG01": → 读 offset0：若为 "hsqs" → reject("裸 squashfs…")`，**先于** `> if footer.file_size != size: reject("truncated")` | 截断 1 字节使"最后 4096 字节"读取窗口整体左移 → **双 magic 先失败**，永远走不到 `file_size` 分支；§8 给出的拒绝**理由不成立**（结果"拒绝"仍成立）。`file_size != size` 实际只覆盖"footer 完好但文件被追加/尾部被换"。**实测**（`tests/data/vectors/truncated-1byte.img` 按 §5 复演）：magic 失败 → 落入 N4 分支；同目录 `file-size-mismatch.img`（footer 完好）才命中 `truncated` 分支 | ①§8 该行期望改为"**拒绝（双 magic 先失败 → `NOT_AN_IMAGE`）**"；②§8 补一行"footer 完好但尾字节被改/被追加 → `footer.file_size != size` → 拒绝（`CORRUPT`）"；③§3 规则 3 加注"纯截断由规则 1 拦截，本规则针对追加/拼接" | **A1 + B1**（两工位独立发现，交叉印证）+ 本册实测 | Major |
| **IMP-D02** | `IMAGE-FORMAT.md §5`（L232–234）× `§7.4 #3` × `§5.4` | §5：`> 魔数不匹配 → 走拒绝分支（N4：裸 squashfs 提示封装；否则"非本格式"）`；`> -> 读 offset0：若为 "hsqs" → reject("裸 squashfs，需用 mkimg.sh 封装为 .img")` | **诊断阶梯缺陷**：footer 被改写或文件被截断的 `.img`，offset 0 仍是 `hsqs` → 被误报成"裸 squashfs，请用 mkimg 封装"，与 §7.4 #3 的"给出 `mkimg.sh` 封装指引"叠加成**错误排障建议**（用户会去封装一个本来就是 `.img` 的坏文件）。**实测**：`footer-overwritten.img`、`truncated-1byte.img` 按 §5 复演均输出"裸 squashfs → 需封装" | §5 阶梯改为三级：① magic 失败 **且** 文件 >1 MiB 且 offset0 为 `hsqs` → `reject("footer 缺失或损坏（文件可能是被截断/改写的 .img）")` 归 `CORRUPT`；② magic 失败且确为纯 squashfs（≤1 MiB 或 `.squashfs` 来源）→ 才提示"用 `mkimg.sh` 封装"；③ 其余 → "非本格式"。同步把 §7.4 #3 拆成 `NOT_AN_IMAGE`（真裸 squashfs）与 `CORRUPT`（footer 坏但有 hsqs 骨架）两条 | **C1**（独立发现）+ A1/B1 同源 + 本册实测 | Major |
| **IMP-D03** | `IMAGE-FORMAT.md §3 规则1–3`（L112–117）× `§5`（L231–239）× `DESIGN.md §7.4`（L770–782） | §3：`> 先判断 file_size ≥ 4096 … 魔数匹配才检查 file_size < 8192 → 拒绝`，规则2 = `> 校验 footer_size == 4096、format_version ≤ 1、flags 保留位为 0`，规则3 = `> 校验 file_size == <实际文件大小>`；§5 顺序却是 `file_size` → `format_version` → `footer_size` → `flags` | **校验顺序三处不一致**：§3 把 footer_size/format_version/flags 排在 `file_size` **之前**，§5 反之；§7.4 表序（#3→#4→#5→#6→#7→#8→#9）又是第三种，而 codec 按 §5 序（`format → image.id → arch → ssh → ssh_port → app`，`VmdImageCodec.kt:500`）。合成样本（同时 format_version 过新 + file_size 不符）在三处得到**不同 reason 归属** → 负向测试断言无法跨实现对齐 | §5 顶部加一句钦定：`> **校验时序以本节伪代码为准**；§3 规则编号仅为清单，不表示先后。同一样本命中多条时，reason 取本节顺序下的第一个命中`；§7.4 表下补"同多样本按表序取第一个命中"（或直接指向 §5） | **B2** + 本册对 codec 顺序的核对 | Major |
| **IMP-D04** | `DESIGN.md §7.4`（L770–785）+ `§16.5`（L1516） | `> 每次拒绝必须写 image.log 的 bootguard_reject，reason 取本表枚举（§16.5 引用本表，勿另造 NO_IMAGE 之类别名）` | §7.4 只有 11 行 BootGuard 枚举，**缺 codec/解析级 reason**：`TRUNCATED / PAYLOAD_CORRUPT / MANIFEST_INVALID / CORRUPT / IO_ERROR`（注意 `CORRUPT` 在表里是 #2 的 `.meta.json` 语义，与"文件被截断"不同源）都没有落点，实现必须自行归并再映射，但**映射表文档没规定** → 不同实现会把同一失败写进 `image.log` 的不同 reason | §7.4 末尾增加"**codec 级 reason → §7.4 映射子表**"，先按现状钦定：`TRUNCATED→CORRUPT`、`PAYLOAD_CORRUPT→CORRUPT`、`IO_ERROR→CORRUPT`、`MANIFEST_INVALID→NOT_AN_IMAGE`、`FORMAT_*→FORMAT_UNSUPPORTED`；并把"勿另造别名"限定为 **`image.log` 写入值**（允许内部细粒度 reason 存在） | **B3** + `VmdImageCodec.kt:49-61`（`VmdImageReason(val bootGuard)` 现状）+ §16.5 | Major |
| **IMP-D05** | `IMAGE-FORMAT.md §6`（L287–297）× `§4 缺省解释`（L211–216）× `§5`（L254–257） | §6 步骤只有 `> 1. 读 rootfs 大小 R … 2. 计算各段 1 MiB 对齐偏移 … 3. 写 manifest JSON … 6. **自检**`；§4 缺省解释只列 `> 无 capabilities / 无 accounts / 无 contract` | **§6 未定义 mkimg 的必需 manifest 字段集**：catalog 侧必需的 `image.display_name/identity/variant/version/system_version`（§6.1 同名字段）与 §5.2 判定必需的 `identity` 都没有"生成时必须存在"的声明；`app.min_version_code` 不在 §4 缺省解释里 → **缺键时是拒绝还是视为 0 未定义**（§5 却直接 `app.min_version_code <= currentVersionCode`），跨实现会给出不同结果 | §6 增"**必需字段表**"：`image.id/display_name/identity/variant/version/system_version/arch`、`contract.version`、`capabilities.ssh`、`accounts.ssh_port`、`app.min_version_code`（缺一 mkimg 拒绝）；§4 缺省解释补一条钦定：`> 无 app 块 → 视为 min_version_code = 0（不拒绝）`（或"缺失即拒"，二选一） | **A4** + `tests/gen-vectors.sh` 现有 manifest 字段现状 | Major |
| **IMP-D06** | `IMAGE-FORMAT.md §6 步骤1`（L289）× `§2 rootfs_size`（L62） | `> 读 rootfs 大小 R（取 superblock.bytes_used，**丢弃**补齐至 4096 边界的尾部字节，本例 bytes_used→文件尾 383 B；R1: B-R1-9），算 rootfs_sha256` | **squashfs `bytes_used` 的字节偏移没写** → 各实现各猜（`struct` 偏移 / `unsquashfs -s` / `dd` 解析）。**实测**：superblock 内 **0x28（u64 小端）**，`tests/data/bare-rootfs.sqfs` 读出 8753，与 `unsquashfs -s` 的 `Filesystem size 8753 bytes` 一致 | §6 步骤1 括注改为：`> （squashfs superblock 的 bytes_used 位于偏移 0x28，u64 小端；等价于 unsquashfs -s 的 "Filesystem size"）` | **A6** + 本册实测 | Minor |
| **IMP-D07** | `DESIGN.md §11.4 依赖`（L1140–1141）× `验收`（L1202–1203）× `§13.1`（L1280） | `> **依赖**：qemu-system-aarch64（apt install qemu-system-arm…）、python3、mkfs.ext4（e2fsprogs）、ssh。`；`> 两条断言**都必须进 CI**（pc-boot-smoke.sh）。` | 依赖清单**缺 `sshpass`**：`ssh` 密码登录无法非交互输入（`-o BatchMode=yes` 只覆盖密钥/agent），R-16 与 §13.1 要求 CI 断言 `ssh -p 9922` **可登录** → 无 sshpass 时只能降级成 TCP 端口探活，**验收断言形同虚设且不报错**（`pc-run.sh:139` 打 `ssh probe DEGRADED` 后仍可能 PASS）。本机 `/usr/bin/sshpass` 存在，但清单不写则别的机器/CI 镜像不保证 | ①§11.4 依赖补 `sshpass`（`apt install sshpass`），或钦定零依赖替代：`setsid + SSH_ASKPASS` 助手脚本；②§13.1 PC 冒烟行补：`> ssh 断言必须是真登录（密码或密钥）；仅端口探活 = 该断言不通过（记 warning 且冒烟判 FAIL）` | **C2** + `pc-run.sh:98–141` + `pc-boot-smoke.sh` 现状 | Major |
| **IMP-D08** | `DESIGN.md §5.2 decision 表`（L554–560） | `> \| same \| new.rootfs_sha256 == active.rootfs_sha256 \| **否**（内容优先，永不重置）` 等 5 行；表首 `> 规则（按顺序判定；decision 取值 = 本表产出，image.log 直接引用，勿自造）` | 判定表**全部以"存在可读的 active 记录"为前提**，两种常见情形无行可匹配：① **首次激活**（`images/active.json` 不存在）→ 无 `active.*` 可比；② **active 记录在但镜像不可读/已删除** → 读不到 active 的 `contract`/`distro.init`。而文档同时要求 `decision` "勿自造"，实现只能自己发明取值 → `image.log` 的 `activate.decision` 跨实现不可比，M3 验收用例（`§13.2` `activate.decision` 有记录）无法统一断言 | §5.2 表补两行：`\| no_active \| 无 active.json（首次激活/记录损坏） \| **否**（等价 upgrade，不重置）`、`\| active_unreadable \| active 记录在但镜像不可读/缺失 \| **是**（无法证明 contract 未变 → 按 contract 保守重置）`；并在枚举列表（§16.5 `same`/`upgrade`/`identity`/`contract`/`init`）同步加这两个取值 | **D4** + `SystemImageStore.decideFor()`（已按这两种选择实现并在注释里写明"§5.2 未覆盖该分支"） | Major |
| **IMP-D09** | `DESIGN.md §7.3`（L760–761） | `> 启动前 awaitAssetsReady()（现在只等固件）+ 新增 awaitImagesReady()（等 .meta.json 校验判定完成），两者都完成后才允许 start()。` | `awaitImagesReady()` **未实现也未标里程碑**（全仓只有 `awaitAssetsReady`，`VmdroidApplication.kt:51`），§14 各里程碑行也未点名它 → M1/M2 读者会以为必须现在做 | §7.3 末补 `> （**M3**；M1/M2 阶段 `awaitImagesReady()` 视为立即完成）`；§14 M3 行补函数名 `awaitImagesReady()` | **D3** + grep 实证 | Minor |
| **IMP-D10** | `DESIGN.md §16.5`（L1516）× `§7.4` | `> reason（**枚举 = §7.4 权威清单**：NO_SYSTEM_IMAGE/CORRUPT/APP_TOO_OLD/ARCH/SSH_PORT/…，R3: A-R3-16 统一）` | **本册核实中发现**：例举的 `ARCH`、`SSH_PORT` 并不是 §7.4 的 `ARCH_MISMATCH`、`SSH_PORT_INVALID` → 与"枚举 = §7.4 权威清单"自相矛盾（R3 声称 A-R3-16 已统一，实际未统一到字符串） | §16.5 该单元格改为 `…：见 §7.4 权威清单（例：NO_SYSTEM_IMAGE / CORRUPT / ARCH_MISMATCH / SSH_PORT_INVALID）`，去掉自造缩写 | 工位 H 核实新发现（非原始线索） | Minor |

---

## 2. 实现约定（文档未规定，需钦定）

| ID | 位置 | 引用原文 | 问题 | 建议改动 | **当前各实现分别怎么做的** | 交叉印证来源 | 严重度 |
|---|---|---|---|---|---|---|---|
| **IMP-C01** | `IMAGE-FORMAT.md §2 约束表`（L63–67）× `§5 读取算法`（L243–252） | §2：`> 段对齐 \| kernel、initrd、manifest 均从 **1 MiB** 边界开始（R2: A-R2-17/B-R2-13）`；同表：`> 填充 \| 所有洞必须全零（读取方必须容忍非零垃圾：容忍即可，不校验）`；§5 边界检查只有 `> if seg.offset > MAX-seg.size or seg.offset+seg.size > file_size-4096: reject("segment out of range")`，**无对齐/最小长度检查** | §2 把 1 MiB 对齐、`kernel_size==0\|\|≥1MiB`、`initrd_size==0\|\|≥4096`、`reserved` 全零列为**约束**，但 §5 读取方一律不校验 → 不对齐但不越界的镜像会被"合规读取方"接受。§2 只对 **padding** 明确了"读取方不校验"，对对齐等**沉默** → 这是 A2 与 B6 说的同一件事的两面，必须钦定一个方向 | §5 末尾（或 §2 表）补一行明确宽松：`> **读取方校验范围**：1 MiB 段对齐、kernel/initrd 最小长度、reserved 全零、squashfs 内部一致性属**生成侧**约束（§6/§8 覆盖）；读取方 MUST NOT 因此拒绝，MAY 记 warning。读取方只校验 §5 列出的边界/顺序/魔数/哈希`（若反向钦定"必须校验"，则 §5 需补对应检查与 reason，并接受拒收非 mkimg 生成的镜像） | **mkimg**：`verify_img` 校验对齐与最小长度（`mkimg.sh` `M1/MIN_KERNEL/MIN_INITRD` 常量）；**Kotlin codec**：**故意不查**（避免拒非 mkimg 生成的合法镜像，`VmdImageCodec` 只查 §5 列出的项）；**imgboot_verify**：查段边界与段序、不查对齐（`imgboot.sh:393-400`） | **A2 + B6**（同一件事两面，合并登记） | Major |
| **IMP-C02** | `IMAGE-FORMAT.md §4 boot/checksums/contract.kernel`（L163–204）× `§6 步骤3`（L292） | §4：`> "kernel_sha256": "…"   // = footer.kernel_sha256（JSON 侧便于工具读取）`、`> "checksums": { … "rootfs_sha256": "…" } // 与 footer 字段一致（JSON 侧便于工具读取）`；§6：`> 3. 写 manifest JSON（UTF-8）→ 算 manifest_sha256。` | **派生字段（`checksums.rootfs_sha256`、`boot.kernel_sha256`/`initrd_sha256`、`contract.kernel.image_sha256`）谁写、与 footer 冲突时覆盖还是报错，完全未规定**。§4 只说"与 footer 一致"，没有保证机制；读取方也不比对 → 不一致会被静默接受，而 §4.4 又要用 `contract.kernel.image_sha256` 与 APK `firmware.properties` 比对 | §6 步骤 3 后补：`> mkimg **MUST** 用 footer 值覆写 checksums.rootfs_sha256 / boot.kernel_sha256 / boot.initrd_sha256 / contract.kernel.image_sha256（manifest 内同名字段仅为镜像内提示）；读取方以 **footer** 为准，manifest 与 footer 不一致 → 不拒绝，记 warning 并写 verify_fail 说明` | **mkimg**：覆写并在 usage 写明 `derived fields … are (re)written by mkimg`，打印 `derived manifest fields written`（**文档里没有这条规则**）；**codec**：读取时不比对派生字段与 footer；**imgboot**：只读 footer，不碰 manifest 派生字段 | **A3** | Major |
| **IMP-C03** | `IMAGE-FORMAT.md §6 步骤6`（L296–297）+ §6 CLI（L277–285） | `> 6. **自检**：① 同一解析器回读；② 若含 kernel/initrd → 自动跑 pc-boot-smoke.sh（起 QEMU **90s**，断言出现 Ready! 且 ssh -p 9922 可登录，见 DESIGN §13.2 R-16 用例）。` | **自检降级行为未定义**：缺 `qemu-system-aarch64` / 缺 `pc-boot-smoke.sh` / 缺 `IMG_BOOT_LIB` 时是 SKIP 还是 FAIL？`flags==0`（无 payload）时跑不跑？`--smoke`/`--no-smoke` 是否正式 CLI？（R1: A-R1-7 称 §6 是**唯一权威接口**，加旗标即扩接口） | §6 CLI 块补 `[--smoke \| --no-smoke]`，并写降级语义：`> 默认 auto：有 kernel/initrd payload 且 pc-boot-smoke.sh + qemu + IMG_BOOT_LIB 齐备 → 跑；缺任一 → warn 后跳过（构建仍成功）。--smoke：强制，缺工具 exit 3；--no-smoke：跳过（测试向量用）。CI 一律用 --smoke，禁止依赖 auto 的静默降级` | **mkimg.sh 现状**：`SMOKE=auto`（`mkimg.sh:324,665-698`）—— 三者齐备才跑、缺则 `warn "boot smoke skipped"`；`--smoke` 缺工具 `exit 3`；`--no-smoke` 跳过；无 payload 不跑。**文档一个字没写** | **A5** | Minor |
| **IMP-C04** | `IMAGE-FORMAT.md §4 缺省解释`（L211–216） | `> 无 capabilities → {ssh:true, x11:true, containers:true}（对齐上游全量镜像）`；`> capabilities.ssh 若为 false → 镜像不合格，mkimg 与应用均拒绝` | **有 `capabilities` 块但缺 `ssh` 键**的行为未定义：走"整块缺省"还是"缺键=false"？文档只覆盖了"整块缺失" | §4 缺省解释补：`> 有 capabilities 块但缺 ssh 键 → 视为 false（fail-closed，按 §7.4 #8 SSH_CAPABILITY_MISSING 拒绝）` | **Kotlin codec**：fail-closed，缺键= false → `SSH_CAPABILITY_MISSING`（`VmdImageCodec.kt:786-787` 注释明写）；**mkimg**：拒 `ssh:false`，未测缺键场景；**imgboot**：不解析 manifest 能力块 | **B4** | Minor |
| **IMP-C05** | `IMAGE-FORMAT.md §5`（L230–262）× `§2`（L64–66）× `DESIGN §7.4` | §5：`> if size < 4096: reject("too small")`、`> file_size < 8192 的拒绝只在 footer magic 匹配后才执行`；§2：`> manifest_size \| 1 ≤ M ≤ 65536` | 若干**边界失败没有 reason 归属**：`size<4096`、magic 后 `file_size<8192`、`rootfs_size==0`（§5 完全没查）、`manifest_size∉[1,65536]`（§2 有约束、§5 只查越界不查范围）→ 各实现自造文案与枚举。**子项 `rootfs_offset≠0` 不成立**：§5 已 `> require rootfs.offset == 0` | ①§5 补两条检查：`rootfs_size > 0`、`1 ≤ manifest_size ≤ 65536`（归 `CORRUPT`）；②§7.4 或 §5 给上述每个边界指定 reason（建议：`size<4096`/`file_size<8192` → `CORRUPT`；范围类 → `CORRUPT`），并把 §5 的 `too small` 等自由文案改成枚举名 | **codec**：各自给了细粒度 reason 并映射到 bootguard（`TRUNCATED`/`MANIFEST_INVALID`…）；**mkimg `verify_img`**：查 manifest 范围（`MAX_MANIFEST`）、kernel/initrd 最小长度；**imgboot_verify**：查双 magic/file_size/flags/边界/manifest sha | **B5**（其 `rootfs_offset≠0` 子项判不成立，见 §4） | Minor |
| **IMP-C06** | `IMAGE-FORMAT.md §4 字段规则`（L216） | `> accounts.ssh_port **缺省 22；≠ 22 一律拒绝**（DESIGN §4.8 端口规范）。` | **类型非整数**（字符串 `"22"`、`null`、浮点、数组）未定义：是按"≠22 拒绝"还是"解析失败=MANIFEST_INVALID"？ | §4 该条补：`> ssh_port 类型非整数（字符串/浮点/null）→ 视为 ≠ 22 → SSH_PORT_INVALID（fail-closed，不做字符串到数字的宽松转换）` | **codec**：类型非整数 → `SSH_PORT_INVALID`（`VmdImageCodec.kt:807,821`）；**mkimg**：按 JSON 数字解析，非数字直接解析失败；**imgboot**：不读该字段 | **B7** | Minor |
| **IMP-C07** | `DESIGN.md §11.4 要点表·端口`（L1198），全文无"端口占用"约定 | `> 端口 \| 只绑回环 127.0.0.1（9922/5900/4713 三者齐全，与 §4.8 一致）；ssh -p 9922 ltbkq@localhost（pw 123）` | **端口冲突无规格覆盖**：任一端口被占时 QEMU 报 `Could not set up host forwarding rule` 直接退出，并留下陈旧 chardev socket（下一次又 `Failed to bind`）。预检、报错文案、残留清理都没规定 | §11.4 要点表加一行：`\| 端口冲突 \| 启动前预检 9922/5900/4713，被占 → 打印占用进程并**明确失败**（不静默重试/不假成功）；启动前清理 run-dir 陈旧 *.sock \|` | **pc-run.sh**：启动前 `ss -ltnp` 预检三端口并打印占用者（fail-fast，`pc-run.sh:78-91`）；**imgboot**：`imgboot_prepare_run_dir` 清陈旧 `*.sock`；**文档**：无 | **C3** | Minor |
| **IMP-C08** | `DESIGN.md §11.4 串口行`（L1194）× 方式B命令（L1176） | `> 串口/显示 \| -display none -serial mon:stdio 为**必选项**（R2: B-R2-7）：ttyAMA0 不接 chardev 则 Ready! 无输出…` | `-serial mon:stdio` 把 monitor 与 console 复用同一 stdio；而 pc-run 前台模式把 stdout 重定向到 `console.log` → **前台模式下 monitor 不可交互**（`Ctrl-a c` 无处可按），文档的"必选项"与"console 落 console.log"取舍未说明 | §11.4 加注：`> pc-run 前台/后台模式下 monitor 与 console 同流，monitor **不可交互**；需要 monitor 时用 --dry-run 取 argv 后手工把 -serial mon:stdio 换成 -serial file:console.log -monitor unix:<run>/monitor.sock（或经 QMP socket）` | **pc-run.sh**：前台与后台均 `-serial mon:stdio` + stdout → `console.log`（`pc-run.sh:24,213,256`、`imgboot.sh:449`）；**文档**：只规定了"必选项" | **C5** | Info |
| **IMP-C09** | `DESIGN.md §2.3`（L162）× `§5.3`（L573–574） | §2.3：`> └── active.json   # {image_id, identity, rootfs_sha256, activated_at}`；§5.3：`> images/active.json 记录 {image_id, identity, rootfs_sha256, activated_at}（字段名与 §2.3 一致；**唯一写者 = SystemImageRepo**，R2: A-R2-6/D-R2-7）` | 文档两处**一致**（4 字段、无 `path`），但早期写者/§7.1 草图带 `path`；**读取方是否容忍未知字段、写入方是否禁写 `path` 未规定** → "按冻结文档严格实现"的实现会拒掉历史文件，"容忍"的实现又偏离字面 | §5.3 补一句钦定：`> 写入方 **MUST 只写这 4 个字段**（不写 path）；读取方 **MUST 容忍未知字段**（含历史 path），多出/缺少非必需字段均不作为拒绝依据；缺 image_id 视为无激活记录` | **M1 `SystemImageStore`**：写 4 字段（注释"不写 path；读取时容忍"），读容忍 `path`（`PATH_FIELD = "path" // 早期写者 / §7.1 草图的可选容忍字段`，`SystemImageStore.kt:608`）；**F 工位**：按冻结文档 4 字段严格实现 → 当前**没有实际冲突**，只是规则没写 | **D1** | Minor |
| **IMP-C10** | `DESIGN.md §5.2 规则4`（L566）× `§8.5`（L941–955）× `§14`（L1341） | §5.2：`> 4. 激活时判定为需重置 → 进入 RESET_REQUIRED，用户确认后由应用**重建 storage.img**（§4.3 整文件清零），再激活。`；§14 M3 行只列 `§8.2 启动镜像选择控件` | "用户确认后重建再激活"需要一个二次调用门闩（实现为 `activate(id, allowReset=true)`），但该参数与 §8.5 对话框的**归属里程碑都没写**：§8.5 不在 §14 任何行出现 → 读者不知道 UI 归谁、门闩是否 M3 必做 | §5.2 规则 4 末补 `（UI 落点 = §8.5 对话框，**M3**；实现需二次确认门闩 `allowReset`）`；§14 M3 行补 `+ §8.5 激活冲突对话框与 activate(allowReset) 接线` | **M1 `SystemImageRepository.activate(imageId, allowReset=false)`**：门闩已实现、注释写明"用户确认后由上层重建数据盘再以 allowReset=true 调一次"；**UI 未接线**（M3 范围）；**文档**：未标阶段 | **D5** | Minor |
| **IMP-C11** | 文档无需覆盖（登记备查） | `DESIGN.md` / `IMAGE-FORMAT.md` 全文无 `exec`/shell 进程替换相关约定；实测见 `pc-run.sh:210-213` 注释 | **后台启动陷阱**：dash 对 `sh -c '<argv>'` 不自动 `exec` → `$!` 拿到的是中间 shell，停机 `kill` 打不到真 QEMU → 泄漏实例并占满 9922/5900/4713（与 IMP-C07 连锁）。纯实现陷阱，**规格可不写** | 不改规格；可选在 §11.4 要点表加一句 `> 后台启动必须 sh -c "exec <argv>"（dash 不自动 exec）`，或只保留在 `pc-run.sh` 注释 | **pc-run.sh**：已 `sh -c "exec $pc_argv"`（含"曾泄漏一个实例并占满三个端口"的实测注释） | **C4**（成立但不需改文档，降级登记） | Info |

---

## 3. 真 bug（非规格问题，需直接修）

| ID | 位置 | 引用原文/证据 | 问题 | 修复建议（不执行） | 交叉印证 | 严重度 |
|---|---|---|---|---|---|---|
| **BUG-01** | `vmdroid-app/app/src/main/res/values/strings.xml:59`、`values-zh/strings.xml:59`；消费点 `ui/screens/setup/SetupScreen.kt:501` | 实测原文：`<string name="ssh_password_hint">ssh root@&lt;phone-ip&gt; -p 9922   (password: podroid)</string>`；对照 `DESIGN.md §4.8`：`> \| root \| 123 \| … \| ltbkq \| 123 \| sudo 组…`，`§10.1`：`> Home/镜像页常驻显示登录提示（数据来自 manifest accounts）：ssh ltbkq@localhost -p 9922  (pw: 123)` | **用户可见文案与实际 guest 凭据不符（真 bug）**：提示 `password: podroid`，而 §4.8 强制 `root/123`、`ltbkq/123` —— 用户照提示登录**必然失败**；且写死 `root@` 与 §10.1"数据来自 manifest `accounts`"、推荐账户 `ltbkq` 相悖 | ①两处 `strings.xml:59` 改为 `ssh ltbkq@localhost -p 9922   (password: 123)`（zh 同步）；②按 §10.1 改为从 manifest `accounts` 注入展示（`default_user` + 对应密码），至少去掉写死的 `root`/`podroid`；③加一条 UI 文案回归：grep 导出 zip 与字符串资源中不得出现 `podroid` 密码字样（与 §13.1 R3: C-R3-4 同款断言并列） | **D6** + grep 实证 | Major |
| **BUG-02** | `vmdroid-app/.../data/repository/UpdateRepository.kt:145`（消费点 `HomeViewModel.kt:61`） | 实测：`connection = URL("https://api.github.com/repos/ExTV/Podroid/releases/latest")`；对照 `DESIGN §12.3`：`> \| vmdroid-<ver>.apk \| **本仓库 Release** \| ≈70 MB \|` | **更新检查指向上游仓库**（`ExTV/Podroid`）→ 会把**上游 Podroid 的 release** 当成本应用更新推给用户（Home 已接线，24h 缓存后每次启动都可能弹） | 把端点改为本仓库 `ltbkq/vmdroid/releases/latest`（建议下沉为 `BuildConfig.UPDATE_REPO`，便于 CI 覆写），并核对 tag 解析（`removePrefix("v")`）与版本比较逻辑 | **D7** + grep 实证 | Major |
| **BUG-03** | `vmdroid-app/build-all.sh:172-173` | 实测：`local pkg="com.excp.podroid.debug"`、`local activity="com.excp.podroid.MainActivity"`；对照 `DESIGN §12.1`：`> 包名 \| io.github.ltbkq.vmdroid（debug 加 .debug 后缀）` | `run_boot_test` 用**旧包名/旧 Activity** → `adb shell am start`/`pm clear` 必然失败，脚本自检形同虚设（脚本还会先 `assembleDebug` 再部署，失败被掩盖在下游） | 改为 `io.github.ltbkq.vmdroid.debug` / `io.github.ltbkq.vmdroid.MainActivity`；更稳的做法是从 `app/build/outputs/apk` 反查或读 `applicationId` 派生，避免再次漂移 | **D7** + grep 实证 | Major |
| **BUG-04** | `vmdroid-app/settings.gradle.kts:29`、`gradle.properties:24-25` | 实测：`rootProject.name = "Podroid"`；`podroidQemuVersion=11.0.4`、`podroidKernelVersion=7.1.5`（读取处 `app/build.gradle.kts:14,30` 自洽）；对照 `DESIGN §3`：`> 品牌/文案 \| Podroid \| VMDroid（保留 guest 内 podroid-* 标识…）` | **上游命名遗留（仅命名，功能正常）**：root 工程名与两个 Gradle 属性仍是 `podroid*`；与"Android 侧改名"目标不一致，易在 CI/文档引用处造成混淆 | 同一 PR 内改名 `rootProject.name = "Vmdroid"`、`vmdroidQemuVersion`/`vmdroidKernelVersion`（**必须同步** `app/build.gradle.kts:14,30` 的 `providers.gradleProperty(...)`），或明确记录"保留上游名以便 cherry-pick"作为决策 | **D7** + grep 实证 | Info |

> D7 其余项已核：`UpdateRepository` 只剩 `ExTV/Podroid` 一处上游 URL（grep 全仓），`build-all.sh` 只剩 `com.excp.podroid` 两处；其余文件无上游包名残留。

---

## 4. 已验证无问题 / 不成立的线索

| ID（原始线索） | 判定 | 核实过程与证据 | 结论 |
|---|---|---|---|
| **C6**（imgboot_verify 不重算 payload sha256 → 坏内核上机） | **部分不成立**（风险已被现有路径覆盖） | `imgboot_verify` 确实只查 footer/manifest（`imgboot.sh:393-400`，注释"L13 manifest sha256"）；但 **pc-run.sh:197-198 与 pc-boot-smoke.sh:135-155 都是 verify → extract 两步**，`imgboot_extract` 对 kernel/initrd **逐一比对 payload sha256**（`imgboot.sh:344-346` `reject("%s payload sha256 不匹配…")`）。kernel/initrd 必须提取才能 `-kernel/-initrd` 启动，故现有 PC 路径**不存在"只 verify 就启动"的坏内核上机**；rootfs 不全量重算则完全符合 §3 规则 7"可延迟"与 §5 校验时机（启动前只比 size/mtime） | 不进 R4。可选 Info：§11.4 要点表补一句 `verify = footer/manifest，extract = payload sha256，两者合起来才是"全量校验"`，消除职责误解 |
| **D2**（§7.4 主判定接线 M1 未做、文档未标阶段） | **不成立**（时序性问题已消解） | ① `§14 M1` 行本就写着 `> **BootGuard 简版：仅 ABSENT 判定**，完整校验归 M3，R2: D-R2-4` → "文档未标阶段"不成立；② 当前**工作树**（未提交）`VmdroidService.kt:414-431` 已接 §7.4 主判定并落 `bootguard_reject` 到 `image.log`（含 `teardown()` 中止启动），与 §7.4 调用点表述完全一致，引擎侧 `failFastNoImage()` 作为兜底 | 不进 R4。**提醒**：该改动尚未 `git commit`，M1 提交（`8052a9a`）里没有它，评审/交付时别按提交态下结论 |
| **B5 子项 `rootfs_offset≠0` 未定义** | **不成立** | `IMAGE-FORMAT §5`（L247）已有 `> require rootfs.offset == 0 and prev_end = rootfs.size`，只是没给独立 reason 文案 | 该子项从 IMP-C05 中剔除；其余子项（`rootfs_size==0`、`manifest_size` 范围、reason 归属）仍成立 |
| **C4**（dash 不自动 exec） | 成立，但**不要求写进规格** | 已在实现中修复并留注释（`pc-run.sh:210-213`）；文档层面属实现陷阱 | 降级为 §2 `IMP-C11`（Info，可选一句话），不作为规格缺陷 |
| **A1 的"结果"部分** | 成立但**无功能缺陷** | 截断 1 字节**确实被拒绝**（只是走错分支），§8 的"→ 拒绝"结论仍成立 | 只修**理由/分支**（IMP-D01），不改验收结论 |

---

## 5. 测试盲区与基础设施隐患

| ID | 线索 | 现象与证据 | 风险 | 建议 | 严重度 |
|---|---|---|---|---|---|
| **IMP-T01** | **C7** | `DESIGN §13.1 工具侧`（L1282）：`> mkimg.sh ↔ Kotlin codec **互操作**；测试向量入库；catalog schema 前向兼容…` —— 未指明互操作必须覆盖**哪一层入口**。§3 规则 7 允许 payload 校验延迟（`> 逐 payload 校验 sha256（可延迟到"校验"动作，见 DESIGN §6.4）`），故 `VmdImageCodec.read()` **按规格就该**放行被翻转的 kernel/initrd，只有 `verifyPayloads()` 才拒（`VmdImageCodec.kt:516`）。G 工位实测：`kernel-flip.img` / `initrd-flip.img` 在 read 期被接受 | **互操作测试盲区**：若 CI 只断言 `read()` 通过 = 假绿，"坏内核不能上机"（§8 向量）在 codec 层无人守 | §13.1 工具侧行补：`> 互操作必须包含负向断言：kernel/initrd 单字节翻转 → read() 可通过（规则 7 允许延迟），**verifyPayloads() 必须拒绝**（PAYLOAD_CORRUPT）；反向：mkimg 产物 read+verifyPayloads 全通过`。fixture 已存在（`tests/data/vectors/kernel-flip.img`、`initrd-flip.img`），补断言即可 | Major |
| **IMP-T02** | **G1** | `Podroid-Debian/tests/gen-vectors.sh:66`：`head -c 8192 /dev/urandom > "$SQSRC/usr/local/bin/payload"`，而该 squashfs（`$BARE`）正是 `valid-full.img` / `valid-min.img` 的 rootfs（同脚本 `4/5` 步）。脚本其余部分为确定性做了很多努力（`mksquashfs -mkfs-time 0 -all-time 0`、确定性 fake kernel/initrd） | **向量非确定性**：每次重跑 `tests/vectors/vectors.json` 里 `rootfs_sha256` 与两张正例镜像的 `file_sha256`/`sha256`（实测 30+ 处）**全变** → CI 不可复现、每次重跑都是全量 diff、"测试向量入库"失去回归价值（§13.1 要求"测试向量入库"本意是固定期望） | ①把 `/dev/urandom` 换成固定内容（如 `head -c 8192 /dev/zero`、`yes` 或 python 确定性字节），保留 `mksquashfs` 的确定性参数；②重跑一次并提交新向量作为基线；③在 CI 加"跑两遍 gen-vectors，断言 `vectors.json` 字节不变"的守护 | Major |
| **IMP-T03** | **G2** | `app/src/test/.../VmdImageCodecTest.kt:15,28`：`import org.junit.Assume` + `Assume.assumeTrue(fixture存在)`；`codec-selftest/README.md:75` 还写着 `> CI 中 fixtures 缺失时测试类整体 **skip**（Assume）`。而 `codec-selftest/run-interop.sh:84` 已反向要求 `> fixtures 缺失时必须 exit 1（证明没有 Assume 式静默 OK）` | **静默跳过陷阱**：缺 fixture 时 JUnit 报 `OK (28 tests)`，实际**全跳** → 绿灯假象（复核时已踩到一次）；同一仓里两套口径（Assume-skip vs 必须失败）互相矛盾 | ①`VmdImageCodecTest` 改为 fixtures 缺失 → 直接 `fail()`（或在 `@BeforeClass` 断言 fixtures 存在）；②README 口径同步改；③CI 先跑 `gen_fixtures.py`/`gen-vectors.sh` 并断言产物存在，再跑测试；④可加一行守护：`grep -rn 'Assume' app/src/test` 必须为空 | Major |
| **IMP-T04** | **D8** | 实测：`/opt/android-sdk` 为空目录；`/usr/lib/android-sdk` 只有 `platform-tools`；无 `local.properties`、`ANDROID_HOME`/`ANDROID_SDK_ROOT` 为空；`vmdroid-app/app/build` **不存在**、全仓无 `.apk`、`build/reports/configuration-cache` 只有配置缓存 → **Gradle 从未编译过 app 模块**。**范围收窄**：纯 JVM 部分确已编译跑过 —— `codec-selftest/out/interop.jar`、`systemimage-selftest/out/`（standalone `kotlinc`，`tools/kotlinc`），故"Kotlin 从未编译"只对 **Android app 模块**成立 | M1 退出标准 `> APK ≈70 MB 可安装`（§14）**当前不可验证**；app 模块 299 条 import 只做了人工核对，Compose/Hilt/依赖注入部分的编译错误、`testDebugUnitTest` 结果全未知 → 所有 app 侧工位结论都建立在"未编译"之上 | ①装 `cmdline-tools` + `platforms;android-36` + `build-tools`（或直接走 CI），至少跑通 `assembleDebug` 与 `:app:testDebugUnitTest`；②§14 M1 退出标准补"以 CI 通过为凭"；③在拿到首个 APK 之前，本登记册及各工位涉及 app 模块的结论一律按"未编译、不可采信为通过"处理；④codec/selftest 已有 standalone 通路，可作为编译兜底继续用 | **Blocker**（对 M1 验收；环境/流程问题，非文档缺陷，需与 R4 并行处理） |

---

## 6. 统计与 R4 建议

### 6.1 分类与严重度统计

| 分类 | 条数 | Blocker | Major | Minor | Info |
|---|---|---|---|---|---|
| §1 规格缺陷（IMP-D01–D10，含 H 工位新发现 1 条） | 10 | 0 | 7 | 3 | 0 |
| §2 实现约定（IMP-C01–C11） | 11 | 0 | 2 | 7 | 2 |
| §3 真 bug（BUG-01–04，D6/D7 拆 4 项） | 4 | 0 | 3 | 0 | 1 |
| §4 已验证无问题 / 不成立 | 5 | — | — | — | — |
| §5 测试盲区与基建隐患（IMP-T01–T04） | 4 | **1** | 3 | 0 | 0 |
| **合计（登记条目）** | **34** | **1** | **15** | **10** | **3** |

- 线索覆盖：原始 30 条 / 去重 29 条 **全部落位**；判为不成立或风险已被覆盖 **3 条**（C6、D2、B5 的 `rootfs_offset` 子项）；降级为"不需改文档"**1 条**（C4 → IMP-C11）。
- 线索来源分布：A 6 条、B 7 条（B1 并入 D01）、C 7 条、D 8 条、G 2 条；另有本册核实新发现 1 条（IMP-D10）。
- 被**两个工位独立撞上**的（可信度最高，实测已证实）：截断/损坏诊断阶梯（A1+B1+C1 → IMP-D01/IMP-D02）、读取方是否校验 §2 构建约束（A2+B6 → IMP-C01）。

### 6.2 最该先进 R4 的 3 条

1. **IMP-D08（Major）— §5.2 判定表缺"首次激活"与"active 不可读"两行**。这是设计的关键语义（§5.2 自称"本设计的关键语义"），且 `decision` 被 §16.5/§13.2 直接引用为日志枚举、禁止自造；不钦定则 M3 三个读写点（UI、repo、image.log）各自发明取值，事后无法对账。改法明确（加两行 + 枚举同步），成本约 6 行。
2. **IMP-D01 + IMP-D02（Major，建议合并成一个 R4 提案）— 截断/损坏镜像的拒绝理由与诊断阶梯**。A、B 两工位独立发现，本册按 §5 伪代码实测证实（`truncated-1byte.img`、`footer-overwritten.img` 都落到"请用 mkimg 封装"）；直接影响 §8 验收向量（理由写错）与用户排障（误导性下一步，违反 §5.4"给可执行的下一步"）。改 §8 一行 + §5 阶梯三段 + §7.4 拆一条 reason。
3. **IMP-C01（Major）— 钦定"读取方是否校验 §2 构建侧约束"**。mkimg/`verify_img` 查、Kotlin codec 故意不查、imgboot 查一半，**三个实现已经各走各的**；§2 只对 padding 明确了"读取方不校验"，对对齐/最小长度/reserved 沉默。一条"读取方 MUST NOT 拒绝（MAY warn）"即可锁死口径，避免互操作测试出现"同一镜像 A 过 B 不过"。

**与 R4 并行、但不走 R4 的两件事**：
- **IMP-T04（Blocker）**：先让 app 模块真正编译一次（装 SDK 或上 CI），否则 M1 退出标准与所有 app 侧结论都不可验证；
- **BUG-01 / BUG-02 / BUG-03（Major，改代码不改文档）**：登录提示密码 `podroid`、更新检查指向上游仓库、`build-all.sh` 旧包名 —— 三条都是用户/测试直接踩的坑，可独立 PR 快速修掉。
