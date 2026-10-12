package io.github.ltbkq.vmdroid.engine.avf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Method

/**
 * M0 spike P0-1 —— AVF 固件/磁盘引导能力探测（设计文档 DESIGN-disk-image-v2.md §3.2/§11）。
 *
 * 要回答的问题：**AVF/crosvm 能否"无 kernel 的固件启动"（方案 A），
 * 还是必须走"从 ESP 提取 kernel/initrd 直启"（方案 B）？**
 *
 * 探测手段（不启动 VM，纯配置层，零风险）：
 *  1. dump `VirtualMachineCustomImageConfig$Builder` 等类的全部反射面，
 *     标注 firmware/pflash/bios/efi/uefi/kernel/initrd 关键字 → 找方案 A 的 API；
 *  2. 用与 `AvfEngine.launchAttempt` 相同的装配序列构建"**无 setKernelPath**"的
 *     `VirtualMachineConfig`，记录 build() 的成败与异常原文；
 *  3. 对照组：加 setKernelPath 后再 build 一次。
 *
 * 运行（需已连接设备，见 docs/spikes/M0-runbook.md）：
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.ltbkq.vmdroid.engine.avf.AvfFirmwareProbeTest
 * adb logcat -s AvfFirmwareProbe
 * ```
 *
 * 结论判读（runbook §P0-1 决策树）：
 *  - builder 出现 firmware/pflash 类 API 或 无 kernel build 成功  → SIGNAL-A（方案 A 可行，继续启动级验证）
 *  - 无此类 API 且无 kernel build 抛错                            → SIGNAL-B（方案 B 必做，即设计文档默认路径）
 *  - 连 AVF 类都没有                                             → SIGNAL-C（设备不支持 AVF，AVF 后端下线）
 *
 * 本类只读探测，不创建 VM、不写系统状态；spike 结束后可保留为诊断工具或删除。
 */
@RunWith(AndroidJUnit4::class)
class AvfFirmwareProbeTest {

    private val report = StringBuilder()

    private fun p(line: String) {
        report.appendLine(line)
        Log.i(TAG, line)
    }

    @Test
    fun probeAvfFirmwareBootCapability() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        p("=== P0-1 AVF firmware-boot probe ===")
        p("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} release=${android.os.Build.VERSION.RELEASE}")
        p("app=${ctx.packageName}")

        // ── 0. AVF 类是否在设备上 ─────────────────────────────────────────
        val avfPresent = runCatching { Class.forName("$PKG.VirtualMachineManager") }.isSuccess
        if (!avfPresent) {
            p("VERDICT=SIGNAL-C: $PKG.VirtualMachineManager 不存在 → 设备不支持 AVF")
            p("=== end ===")
            return
        }
        p("AVF framework classes: present")

        val mgr = runCatching { AvfReflect.manager(ctx) }.getOrNull()
        if (mgr == null) {
            p("VERDICT=SIGNAL-C: getSystemService(VirtualMachineManager) == null")
            p("=== end ===")
            return
        }

        // ── 1. capabilities 位掩码 ────────────────────────────────────────
        val caps = runCatching { AvfReflect.getCapabilities(mgr) }.getOrNull() ?: -1
        p("VirtualMachineManager.getCapabilities() = $caps (0x${Integer.toHexString(caps)})")
        dumpIntConstants("$PKG.VirtualMachineManager")
        dumpIntConstants("android.system.virtualmachine.AvfCapabilities")

        // ── 2. 反射面 dump（找方案 A 的 API）─────────────────────────────
        val keywords = Regex("firmware|pflash|flash|bios|efi|uefi|kernel|initrd|boot|disk|image", RegexOption.IGNORE_CASE)
        val interesting = mutableListOf<String>()
        for (cls in listOf(
            "$PKG.VirtualMachineCustomImageConfig\$Builder",
            "$PKG.VirtualMachineCustomImageConfig",
            "$PKG.VirtualMachineConfig\$Builder",
            "$PKG.VirtualMachineConfig",
            "$PKG.VirtualMachineManager",
        )) {
            val c = runCatching { Class.forName(cls) }.getOrNull()
            if (c == null) {
                p("class $cls: ABSENT")
                continue
            }
            p("class $cls:")
            val ms: Array<Method> = c.declaredMethods.sortedBy { it.name }.toTypedArray()
            for (m in ms) {
                val sig = "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}): ${m.returnType.simpleName}"
                val hit = keywords.containsMatchIn(m.name)
                if (hit) interesting += "  ${c.simpleName}.$sig"
                p("  ${if (hit) ">> " else "   "}$sig")
            }
            c.declaredFields.sortedBy { it.name }.forEach { f ->
                val hit = keywords.containsMatchIn(f.name)
                if (hit) interesting += "  field ${c.simpleName}.${f.name}: ${f.type.simpleName}"
                p("  ${if (hit) ">> " else "   "}field ${f.name}: ${f.type.simpleName}")
            }
        }
        p("keyword-matched API surface (firmware/pflash/bios/efi/uefi/kernel/initrd/boot):")
        if (interesting.isEmpty()) p("  (none)") else interesting.forEach { p(it) }
        val firmwareApi = interesting.any { Regex("firmware|pflash|bios|efi|uefi", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        p("hasFirmwareLikeApi = $firmwareApi")

        // ── 3. 无 kernel build 尝试（方案 A 关键判据）──────────────────────
        val disk = File(ctx.cacheDir, "p01-probe-disk.img").apply {
            if (!exists()) createProbeDisk(this)
        }
        p("probe disk = ${disk.absolutePath} (${disk.length()} bytes)")

        val noKernel = runCatching {
            buildConfig(ctx, disk, withKernel = false)
        }
        noKernel.fold(
            onSuccess = { p("build WITHOUT setKernelPath: SUCCESS -> $it") },
            onFailure = { p("build WITHOUT setKernelPath: FAILED -> ${it.javaClass.name}: ${it.message}") },
        )

        val withKernel = runCatching {
            buildConfig(ctx, disk, withKernel = true)
        }
        withKernel.fold(
            onSuccess = { p("build WITH setKernelPath(dummy): SUCCESS -> $it") },
            onFailure = { p("build WITH setKernelPath(dummy): FAILED -> ${it.javaClass.name}: ${it.message}") },
        )

        // ── 4. 判读 ──────────────────────────────────────────────────────
        val verdict = when {
            noKernel.isSuccess && firmwareApi -> "SIGNAL-A"
            noKernel.isSuccess -> "SIGNAL-A(no-api-but-accepted)"
            firmwareApi -> "SIGNAL-A(part): 有固件类 API 但无 kernel build 失败，需看失败原文"
            else -> "SIGNAL-B"
        }
        p("VERDICT=$verdict")
        p("  SIGNAL-A  → 方案 A（AVF 固件启动）可行：继续启动级验证（真机跑 .img 至 Ready!）")
        p("  SIGNAL-B  → 方案 A 不可行：按设计文档 §3.2 实现 ESP 提取直启（默认必做路径）")
        p("=== end ===")
    }

    /** 与 AvfEngine 相同的装配序列，仅省略/保留 setKernelPath。 */
    private fun buildConfig(ctx: android.content.Context, disk: File, withKernel: Boolean): Any {
        val cb = AvfReflect.newCustomBuilder()
        AvfReflect.setName(cb, "p01-probe")
        if (withKernel) {
            // dummy kernel file：build() 通常只校验路径类型而非内容；若校验内容会失败，
            // 那也是有效信号（fail-fast 消息里通常写明需要什么）。
            val dummy = File(ctx.cacheDir, "p01-dummy-kernel").apply {
                if (!exists()) writeBytes(ByteArray(4096))
            }
            AvfReflect.setKernelPath(cb, dummy.absolutePath)
        }
        AvfReflect.addParams(cb, "console=hvc0 mitigations=off")
        AvfReflect.addDisk(cb, disk.absolutePath, writable = true)
        val vmb = AvfReflect.newVmConfigBuilder(ctx)
        runCatching { AvfReflect.applyProtectedVm(vmb, false) }
        AvfReflect.setCustomImageConfig(vmb, cb)
        return AvfReflect.build(vmb)
    }

    private fun dumpIntConstants(className: String) {
        val c = runCatching { Class.forName(className) }.getOrNull() ?: run {
            p("constants in $className: ABSENT")
            return
        }
        val ints = c.declaredFields.filter {
            it.type == Int::class.javaPrimitiveType &&
                java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.sortedBy { it.name }
        if (ints.isEmpty()) { p("constants in $className: (none)"); return }
        p("constants in $className:")
        ints.forEach { f ->
            runCatching { f.isAccessible = true; p("  ${f.name} = ${f.getInt(null)}") }
        }
    }

    /** 最小可用的 4 MiB 全零占位盘（build() 通常不做内容校验）。 */
    private fun createProbeDisk(f: File) {
        f.outputStream().use { it.write(ByteArray(4 * 1024 * 1024)) }
    }

    private companion object {
        const val TAG = "AvfFirmwareProbe"
        const val PKG = "android.system.virtualmachine"
    }
}
