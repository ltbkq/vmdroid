package io.github.ltbkq.vmdroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ltbkq.vmdroid.BuildConfig
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.data.repository.PortForwardRepository
import io.github.ltbkq.vmdroid.data.repository.SettingsRepository
import io.github.ltbkq.vmdroid.data.repository.UpdateInfo
import io.github.ltbkq.vmdroid.data.repository.UpdateRepository
import io.github.ltbkq.vmdroid.engine.EngineHolder
import io.github.ltbkq.vmdroid.engine.VmState
import io.github.ltbkq.vmdroid.service.VmdroidService
import io.github.ltbkq.vmdroid.systemimage.SystemImageRepository
import io.github.ltbkq.vmdroid.systemimage.SystemImageStore
import io.github.ltbkq.vmdroid.systemimage.VmdImageException
import io.github.ltbkq.vmdroid.systemimage.VmdImageReason
import io.github.ltbkq.vmdroid.systemimage.requiresReset
import io.github.ltbkq.vmdroid.util.NetworkUtils
import io.github.ltbkq.vmdroid.util.UptimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Aggregated Home metadata used by the data sections (resources, network,
 * last-session). `Resources` is shown in the meta row in every state; the
 * Network/LastSession sections render conditionally based on vmState.
 */
data class HomeMeta(
    val ramMb: Int,
    val cpus: Int,
    val storageGb: Int,
    val sshEnabled: Boolean,
    val portForwardCount: Int,
    val lastBootDurationMs: Long,
) {
    val resourcesLabel: String = "${formatRam(ramMb)} · $cpus CPU · ${storageGb} GB"

    private fun formatRam(mb: Int): String =
        if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineHolder,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val updateRepository: UpdateRepository,
    private val systemImages: SystemImageRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    /** True while a stop is tearing the VM down (Running/Starting -> Stopped). */
    val stopping: StateFlow<Boolean> = engine.stopping
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val bootStage: StateFlow<String> = engine.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Pushed live by the guest over the host bridge (STATS verb), persisted via
    // SettingsRepository.setLastContainerCount and read back here as a Flow -
    // works identically on both backends, no polling needed.
    val containerCount: StateFlow<Int?> = settingsRepository.lastContainerCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Aggregated metadata for the Home data sections. */
    val meta: StateFlow<HomeMeta> = combine(
        settingsRepository.vmRamMb,
        settingsRepository.vmCpus,
        settingsRepository.storageSizeGb,
        settingsRepository.sshEnabled,
        portForwardRepository.rules.map { it.size }.distinctUntilChanged(),
        settingsRepository.lastBootDurationMs,
    ) { values ->
        HomeMeta(
            ramMb = values[0] as Int,
            cpus = values[1] as Int,
            storageGb = values[2] as Int,
            sshEnabled = values[3] as Boolean,
            portForwardCount = values[4] as Int,
            lastBootDurationMs = values[5] as Long,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeMeta(ramMb = 512, cpus = 2, storageGb = 8, sshEnabled = false, portForwardCount = 0, lastBootDurationMs = 0L),
    )

    // Ticker — drives uptime display refresh every second, but only while the VM
    // is Running. Gated via flatMapLatest on vmState so no ticks (and no HomeScreen
    // recompositions) happen while the VM is Idle/Starting/Stopped.
    val uptimeTicker: StateFlow<Long> = engine.state
        .map { it is VmState.Running }
        .distinctUntilChanged()
        .flatMapLatest { isRunning ->
            if (isRunning) flow {
                while (true) {
                    emit(System.currentTimeMillis() / 1000)
                    delay(1000)
                }
            } else flowOf(0L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Phone IPv4 — cheap, lazily recomputed when the screen reads it. */
    fun phoneIp(): String = NetworkUtils.localIpv4(context)

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    /**
     * True when AVF (pKVM) is present on this device but neither
     * MANAGE_VIRTUAL_MACHINE nor USE_CUSTOM_VIRTUAL_MACHINE have been granted
     * yet — and the user hasn't dismissed the banner.
     *
     * The AVF probe is fast (no IPC, no binder), so we derive it via a
     * simple map on the dismissed flow rather than a heavy combine.
     */
    private val _avfProbe = io.github.ltbkq.vmdroid.engine.avf.AvfDiagnostics.probe(context)
    val showAvfHint: StateFlow<Boolean> = settingsRepository.avfHintDismissed
        .map { dismissed ->
            _avfProbe.featureSupported &&
                !_avfProbe.managePermissionGranted &&
                !dismissed
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when AVF is selected but the current pick fell back to QEMU
     *  because this device can't run it. Passive - no actions, just tells the
     *  user why the VM isn't running on the backend they picked. See #66. */
    val backendFallbackBanner: StateFlow<Boolean> = combine(
        engine.backendFallback,
        settingsRepository.engineSelection,
    ) { fallback, sel ->
        fallback != null && sel == io.github.ltbkq.vmdroid.engine.EngineSelection.AVF
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when AVF was the active backend and the VM ended in Error
     *  (boot failure / crash). Drives the actionable failure surface. */
    val avfBootFailure: StateFlow<Boolean> = vmState
        .map { it is VmState.Error && engine.backendId == "avf" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Advice for the failure surface, based on the current vCPU setting. */
    val avfFailureAdvice: StateFlow<io.github.ltbkq.vmdroid.engine.avf.AvfFailureGuidance.Advice> =
        settingsRepository.vmCpus
            .map { io.github.ltbkq.vmdroid.engine.avf.AvfFailureGuidance.advise(it) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                io.github.ltbkq.vmdroid.engine.avf.AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
            )

    /** True while an AVF VM is starting/running and the headless-GPU networking
     *  workaround failed to attach (see [io.github.ltbkq.vmdroid.engine.avf.AvfReflect.GpuConfigOutcome.FAILED]),
     *  which predicts virtmgr routing the VM onto crosvm_minimal (no virtio-net).
     *  AvfEngine always requests networking, so every AVF VM is affected.
     *  Combined live (not sampled) so the flag appears as soon as the outcome
     *  is recorded during startup. */
    val avfNetWorkaroundFailed: StateFlow<Boolean> = combine(
        vmState,
        io.github.ltbkq.vmdroid.engine.avf.AvfReflect.lastGpuConfigOutcome,
    ) { state, outcome ->
        engine.backendId == "avf" &&
            (state is VmState.Running || state is VmState.Starting) &&
            outcome == io.github.ltbkq.vmdroid.engine.avf.AvfReflect.GpuConfigOutcome.FAILED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun useOneCoreAndRetry() {
        viewModelScope.launch {
            settingsRepository.setVmCpus(1)
            restartVm()
        }
    }

    fun switchToQemuAndRetry() {
        viewModelScope.launch {
            settingsRepository.setEngineSelection(io.github.ltbkq.vmdroid.engine.EngineSelection.QEMU)
            restartVm()
        }
    }

    // ---------------------------------------------------------------- §8.2 启动镜像选择

    private val _images = MutableStateFlow<HomeImageState>(HomeImageState.Loading)

    /** §8.2 选择控件状态（行列表 / 激活项 / 校验中标记 / 一次性提示）。 */
    val images: StateFlow<HomeImageState> = _images.asStateFlow()

    private val _resetPrompt = MutableStateFlow<ResetPrompt?>(null)

    /** §8.5 激活冲突对话框（非空即显示；确认或取消后清空）。 */
    val resetPrompt: StateFlow<ResetPrompt?> = _resetPrompt.asStateFlow()

    // init 放在**全部属性之后**：checkForUpdate()/refreshImages() 经
    // Dispatchers.Main.immediate 在构造期内同步跑，读到声明在后的
    // MutableStateFlow 会拿到 JVM 默认 null（NPE，机上实测闪退）。
    init {
        checkForUpdate()
        refreshImages()
    }

    /** §8.2「运行中禁用」（v1 不做热切换）。 */
    private fun vmBusy(): Boolean {
        val s = vmState.value
        return s is VmState.Running || s is VmState.Starting || stopping.value
    }

    /**
     * 重读 `images/` 列表（初始加载、ON_RESUME、导入后）。失败时**保留上次值**
     * + `loadFailed`（§8.2「加载失败」行：顶部 inline error、布局不塌陷）。
     * [message] 给定时覆盖一次性提示（导入成功 → 「已导入 …」）。
     */
    fun refreshImages(message: String? = null) {
        val prev = _images.value as? HomeImageState.Loaded
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val activeId = systemImages.activeImageId()
                    val rows = systemImages.list().map { installed ->
                        val info = runCatching { systemImages.store.readImage(installed.imageId) }.getOrNull()
                        val image = info?.manifest?.image
                        buildImageRow(
                            imageId = installed.imageId,
                            fileLength = installed.file.length(),
                            displayName = image?.displayName,
                            variant = image?.variant,
                            identity = image?.identity,
                            activeId = activeId,
                            corrupt = prev?.row(installed.imageId)?.corrupt == true,
                            systemVersion = image?.systemVersion,
                        )
                    }
                    HomeImageState.Loaded(
                        rows = rows,
                        activeId = activeId,
                        pendingId = prev?.pendingId?.takeIf { id -> rows.any { it.imageId == id } },
                        message = message ?: prev?.message,
                    )
                }
                _images.value = loaded
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                android.util.Log.w("HomeViewModel", "image list refresh failed", t)
                _images.value = HomeImageState.Loaded(
                    rows = prev?.rows ?: emptyList(),
                    activeId = prev?.activeId,
                    loadFailed = true,
                    message = message ?: prev?.message,
                )
            }
        }
    }

    /**
     * §8.2 选中一项：`checkActivation` 判据 → 判据相同**直接改写 `active.json`**
     * （提示「下次启动生效」）；需重置 → 弹 §8.5；检查拒绝 → 标损坏 + pending
     * （主按钮变「重新校验 / 重新下载」）。
     */
    fun onImageSelected(id: String) {
        val cur = _images.value as? HomeImageState.Loaded ?: return
        if (cur.checkingId != null || vmBusy()) return
        val row = cur.row(id) ?: return
        if (id == cur.displayedId && !row.corrupt) return // 显示值未变且未损坏 → 无操作
        _images.value = cur.copy(checkingId = id, message = null)
        viewModelScope.launch {
            try {
                val check = withContext(Dispatchers.IO) { systemImages.store.checkActivation(id) }
                val state = _images.value as? HomeImageState.Loaded ?: return@launch
                when (check) {
                    is SystemImageStore.ActivationCheck.Rejected -> {
                        _images.value = state.markCorrupt(id).copy(
                            checkingId = null,
                            pendingId = id,
                            message = imageErrorMessage(check.reason, check.detail),
                        )
                    }
                    is SystemImageStore.ActivationCheck.Eligible -> {
                        if (check.decision.requiresReset()) {
                            _resetPrompt.value = withContext(Dispatchers.IO) {
                                buildResetPrompt(id, check.decision.code)
                            }
                            _images.value = state.copy(checkingId = null)
                        } else {
                            withContext(Dispatchers.IO) { systemImages.activate(id) }
                            _images.value = (_images.value as? HomeImageState.Loaded ?: state).copy(
                                activeId = id,
                                pendingId = null,
                                checkingId = null,
                                message = context.getString(R.string.image_activated_next_boot),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _images.value = (_images.value as? HomeImageState.Loaded ?: cur).copy(
                    checkingId = null,
                    message = imageErrorMessage(t),
                )
            }
        }
    }

    /** §8.5 对话框入参：active 记录 identity → 目标 manifest identity。 */
    private suspend fun buildResetPrompt(targetId: String, decision: String): ResetPrompt {
        val from = systemImages.active()?.identity ?: "?"
        val to = runCatching { systemImages.store.readImage(targetId) }
            .getOrNull()?.manifest?.image?.identity ?: "?"
        return ResetPrompt(targetId, fromIdentity = from, toIdentity = to, decision = decision)
    }

    /**
     * §8.5 确认：默认先 §4.3 整文件清零 `storage.img`，**再**
     * `activate(allowReset=true)`（§5.2 规则 4 的顺序：重建 → 激活）；
     * [keepData]（高级勾选）跳过清零直接切换 —— 用户自担「可能无法启动」。
     */
    fun confirmReset(keepData: Boolean) {
        val prompt = _resetPrompt.value ?: return
        _resetPrompt.value = null
        val cur = _images.value as? HomeImageState.Loaded
        if (cur != null) _images.value = cur.copy(checkingId = prompt.targetId, message = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!keepData) systemImages.rebuildStorageImage()
                    systemImages.activate(prompt.targetId, allowReset = true)
                }
                _images.value = (_images.value as? HomeImageState.Loaded ?: cur)?.copy(
                    activeId = prompt.targetId,
                    pendingId = null,
                    checkingId = null,
                    message = context.getString(R.string.image_activated_next_boot),
                ) ?: HomeImageState.Loaded(
                    rows = emptyList(),
                    activeId = prompt.targetId,
                    message = context.getString(R.string.image_activated_next_boot),
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _images.value = (_images.value as? HomeImageState.Loaded ?: cur)?.copy(
                    checkingId = null,
                    message = imageErrorMessage(t),
                ) ?: HomeImageState.Loaded(
                    rows = emptyList(),
                    activeId = null,
                    message = imageErrorMessage(t),
                )
            }
        }
    }

    /** §8.5 取消：不写 active.json、不清数据盘（`RESET_REQUIRED` 是信号，不落盘）。 */
    fun cancelReset() {
        _resetPrompt.value = null
        val cur = _images.value as? HomeImageState.Loaded ?: return
        _images.value = cur.copy(checkingId = null)
    }

    /**
     * §6.3 SAF 导入：`content://` 流 → `install()`（`.part` → sha256 → footer/
     * manifest 校验 → 原子 rename）。非 .img / 损坏内容由 codec 拒绝并给封装指引。
     */
    fun importImage(uri: android.net.Uri) {
        val cur = _images.value as? HomeImageState.Loaded
        val importing = context.getString(R.string.importing)
        _images.value = cur?.copy(message = importing)
            ?: HomeImageState.Loaded(rows = emptyList(), activeId = null, message = importing)
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("cannot open $uri")
                    input.use { systemImages.install(it, src = uri.toString()) }
                }
                refreshImages(message = context.getString(R.string.image_imported, imported.imageId))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _images.value = (_images.value as? HomeImageState.Loaded ?: cur)?.copy(
                    message = imageErrorMessage(t),
                ) ?: HomeImageState.Loaded(
                    rows = emptyList(),
                    activeId = null,
                    message = imageErrorMessage(t),
                )
            }
        }
    }

    /** §8.2 主按钮 REVERIFY：全量重算（§6.4 full），通过则治愈损坏标记。 */
    fun reverifyImage() {
        val cur = _images.value as? HomeImageState.Loaded ?: return
        val id = cur.displayedId ?: return
        if (cur.checkingId != null || vmBusy()) return
        _images.value = cur.copy(checkingId = id, message = null)
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { systemImages.verify(id, full = true) }
                val state = _images.value as? HomeImageState.Loaded ?: return@launch
                _images.value = state.markCorrupt(id, !r.ok).copy(
                    checkingId = null,
                    pendingId = if (r.ok) null else id,
                    message = context.getString(
                        if (r.ok) R.string.image_verify_ok else R.string.image_verify_failed,
                    ),
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _images.value = (_images.value as? HomeImageState.Loaded ?: cur).copy(
                    checkingId = null,
                    message = imageErrorMessage(t),
                )
            }
        }
    }

    /** §6.3/§7.4 异常 → 用户文案（共享实现见 ui/ImageMessages.kt，与 Images 页同源）。 */
    private fun imageErrorMessage(t: Throwable): String =
        io.github.ltbkq.vmdroid.ui.imageErrorMessage(context, t)

    private fun imageErrorMessage(reason: VmdImageReason, detail: String?): String =
        io.github.ltbkq.vmdroid.ui.imageErrorMessage(context, reason, detail)

    /** Format "Up Xm Ys" / "Up Xh Ym" from the engine's →Running timestamp. */
    fun uptimeLabel(@Suppress("UNUSED_PARAMETER") tickerTrigger: Long): String? {
        val since = engine.runningSinceMs ?: return null
        val totalSec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        return context.getString(R.string.up) + " " + UptimeFormatter.format(context, totalSec)
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val info = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@launch
                if (!updateRepository.isDismissed(info.latestVersion)) {
                    _updateInfo.value = info
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w("HomeViewModel", "update check failed", e)
            }
        }
    }

    fun dismissUpdate() {
        val version = _updateInfo.value?.latestVersion ?: return
        _updateInfo.value = null
        viewModelScope.launch { updateRepository.dismissUpdate(version) }
    }

    fun dismissAvfHint() {
        viewModelScope.launch { settingsRepository.setAvfHintDismissed(true) }
    }

    fun startVmdroid() = VmdroidService.start(context)

    fun stopVm() = VmdroidService.stop(context)

    fun restartVm() {
        VmdroidService.stop(context)
        viewModelScope.launch {
            // Only start if we observed a terminal state within the timeout window.
            // A null result means QEMU is still tearing down — starting over it would
            // race two instances on the same socket files and storage.img.
            val reached = withTimeoutOrNull(10_000) {
                engine.state.first { state ->
                    state is VmState.Stopped || state is VmState.Idle || state is VmState.Error
                }
            }
            if (reached != null) {
                VmdroidService.start(context)
            }
        }
    }
}
