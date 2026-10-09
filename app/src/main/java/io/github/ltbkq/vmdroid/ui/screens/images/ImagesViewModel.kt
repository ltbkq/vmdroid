/*
 * VMDroid - images management page view model (DESIGN §8.3).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * §8.3 布局四态（加载/禁用/错误/正常）与动作语义在这里；文案与 Home（§8.2）
 * 同源共享（ui/ImageMessages.kt + ResetConfirmDialog）。下载经
 * `SystemImageRepository.downloadImage`（§6.2 预检/续传/通知），断点任务在
 * 页面打开时补扫自动续传（进程级入口在 VmdroidApplication）。
 */
package io.github.ltbkq.vmdroid.ui.screens.images

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.data.repository.ImageCatalogRepository
import io.github.ltbkq.vmdroid.data.repository.SettingsRepository
import io.github.ltbkq.vmdroid.engine.EngineHolder
import io.github.ltbkq.vmdroid.engine.VmState
import io.github.ltbkq.vmdroid.systemimage.CatalogEntry
import io.github.ltbkq.vmdroid.systemimage.CatalogException
import io.github.ltbkq.vmdroid.systemimage.DownloadErrors
import io.github.ltbkq.vmdroid.systemimage.DownloadOutcome
import io.github.ltbkq.vmdroid.systemimage.DownloadProgress
import io.github.ltbkq.vmdroid.systemimage.SystemImageRepository
import io.github.ltbkq.vmdroid.systemimage.SystemImageStore
import io.github.ltbkq.vmdroid.systemimage.requiresReset
import io.github.ltbkq.vmdroid.ui.imageErrorMessage
import io.github.ltbkq.vmdroid.ui.screens.home.ImageRowUi
import io.github.ltbkq.vmdroid.ui.screens.home.ResetPrompt
import io.github.ltbkq.vmdroid.ui.screens.home.buildImageRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** §8.3 目录行下载态（Running 携带进度做行内进度条；Failed 点击重试）。 */
sealed interface DownloadRowUi {
    data class Running(val progress: DownloadProgress) : DownloadRowUi
    data class Failed(val err: String) : DownloadRowUi
}

/** §8.3 页面状态。 */
data class ImagesUiState(
    // ── 已安装（卡片） ──
    val rows: List<ImageRowUi> = emptyList(),
    val activeId: String? = null,
    val installedLoading: Boolean = true,
    val installedFailed: Boolean = false,
    val busyIds: Set<String> = emptySet(), // 激活/校验/删除 spinner
    val verifyingId: String? = null,

    // ── 在线目录（行） ──
    val catalog: List<CatalogEntry>? = null,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val downloads: Map<String, DownloadRowUi> = emptyMap(),
    val wifiBlocked: Boolean = false,

    // ── 全局 ──
    val message: String? = null,
    val importing: Boolean = false,
    val resetPrompt: ResetPrompt? = null, // §8.5 激活冲突
    val removePromptId: String? = null,
    val factoryResetPrompt: Boolean = false,
    val showSubscribe: Boolean = false,
    val catalogUrl: String = "",
) {
    fun row(id: String): ImageRowUi? = rows.find { it.imageId == id }

    fun markCorrupt(id: String): ImagesUiState =
        copy(rows = rows.map { if (it.imageId == id) it.copy(corrupt = true) else it })
}

@HiltViewModel
class ImagesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemImages: SystemImageRepository,
    private val catalogRepo: ImageCatalogRepository,
    private val settings: SettingsRepository,
    private val engine: EngineHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(ImagesUiState())
    val state: StateFlow<ImagesUiState> = _state.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    /** §8.3「禁用」态驱动（同 §8.2 运行中禁用）：Running/Starting/停止过渡。 */
    val vmBusy: StateFlow<Boolean> = combine(engine.state, engine.stopping) { s, stopping ->
        s is VmState.Running || s is VmState.Starting || stopping
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refresh()
    }

    /** §8.3「正常/加载」：装机列表 + 目录 + Wi-Fi 设置 + 断点补扫。 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(catalogLoading = true, installedLoading = it.rows.isEmpty()) }
            refreshInstalled()
            _state.update {
                it.copy(
                    wifiBlocked = systemImages.wifiOnlyBlocked(),
                    catalogUrl = catalogRepo.catalogUrl(),
                )
            }
            fetchCatalog()
            _state.update { it.copy(catalogLoading = false) }
            resumePending()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- 已安装

    private suspend fun refreshInstalled() {
        val prev = _state.value
        try {
            val (rows, activeId) = withContext(Dispatchers.IO) {
                val active = systemImages.activeImageId()
                val list = systemImages.list().map { installed ->
                    val info = runCatching { systemImages.store.readImage(installed.imageId) }.getOrNull()
                    val image = info?.manifest?.image
                    buildImageRow(
                        imageId = installed.imageId,
                        fileLength = installed.file.length(),
                        displayName = image?.displayName,
                        variant = image?.variant,
                        identity = image?.identity,
                        activeId = active,
                        // 重拉不擦除既有 CORRUPT 标记（复校治愈）
                        corrupt = prev.row(installed.imageId)?.corrupt == true,
                        systemVersion = image?.systemVersion,
                    )
                }
                list to active
            }
            _state.update {
                it.copy(rows = rows, activeId = activeId, installedLoading = false, installedFailed = false)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "listing installed images failed", t)
            // §8.2 同款「保留上次值 + inline error」
            _state.update { it.copy(installedLoading = false, installedFailed = true) }
        }
    }

    // ---------------------------------------------------------------- 目录

    private suspend fun fetchCatalog() {
        try {
            val entries = catalogRepo.fetch()
            _state.update { it.copy(catalog = entries, catalogError = null) }
        } catch (e: CatalogException) {
            _state.update { it.copy(catalogError = catalogErrorMessage(e)) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update {
                it.copy(catalogError = context.getString(R.string.images_catalog_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun catalogErrorMessage(e: CatalogException): String = when (e.reason) {
        CatalogException.Reason.SIGNATURE_MISSING,
        CatalogException.Reason.SIGNATURE_INVALID,
        -> context.getString(R.string.images_catalog_sig_invalid) // §6.1：不降级加载
        CatalogException.Reason.MALFORMED,
        CatalogException.Reason.BAD_ENTRY,
        -> context.getString(R.string.images_catalog_malformed)
        CatalogException.Reason.NETWORK ->
            context.getString(R.string.images_catalog_error, e.message ?: "network")
    }

    fun openSubscribe() {
        viewModelScope.launch {
            _state.update { it.copy(showSubscribe = true, catalogUrl = catalogRepo.catalogUrl()) }
        }
    }

    fun closeSubscribe() = _state.update { it.copy(showSubscribe = false) }

    fun setCatalogUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            settings.setCatalogUrl(trimmed)
            _state.update { it.copy(showSubscribe = false, catalogLoading = true) }
            fetchCatalog()
            _state.update { it.copy(catalogLoading = false, catalogUrl = trimmed) }
        }
    }

    // ---------------------------------------------------------------- 下载（§6.2）

    fun download(entry: CatalogEntry) {
        val id = entry.imageId
        if (downloadJobs[id]?.isActive == true) return
        _state.update {
            it.copy(
                downloads = it.downloads + (id to DownloadRowUi.Running(DownloadProgress(0, entry.size, 0))),
                message = null,
            )
        }
        val job = viewModelScope.launch {
            try {
                val out = systemImages.downloadImage(entry) { p ->
                    _state.update { s -> s.copy(downloads = s.downloads + (id to DownloadRowUi.Running(p))) }
                }
                when (out) {
                    is DownloadOutcome.Success -> {
                        _state.update {
                            it.copy(
                                downloads = it.downloads - id,
                                message = context.getString(R.string.notif_download_done, entry.displayName),
                            )
                        }
                        refreshInstalled()
                    }
                    is DownloadOutcome.Failure -> {
                        val f = out.failure
                        if (f.err == DownloadErrors.CANCELLED) {
                            // 取消不留失败行（.part/.info 保留供续传）
                            _state.update { it.copy(downloads = it.downloads - id) }
                        } else {
                            _state.update {
                                it.copy(
                                    downloads = it.downloads + (id to DownloadRowUi.Failed(f.err)),
                                    message = downloadFailMessage(f.err, f.message),
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update {
                    it.copy(
                        downloads = it.downloads + (id to DownloadRowUi.Failed(DownloadErrors.NETWORK)),
                        message = downloadFailMessage(DownloadErrors.NETWORK, t.message),
                    )
                }
            }
        }
        downloadJobs[id] = job
        // 取消（离开页面/再次点击）→ 只清行内状态：.part/.info 保留，续传由
        // VmdroidApplication/下次 refresh 的 resumePending 恢复
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                _state.update { it.copy(downloads = it.downloads - id) }
            }
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs[id]?.cancel()
        downloadJobs.remove(id)
    }

    /** 取消态不给文案（由 [download] 的 invokeOnCompletion 清行）→ null。 */
    private fun downloadFailMessage(err: String, detail: String?): String? = when (err) {
        DownloadErrors.STORAGE_FULL -> context.getString(R.string.images_err_storage_full)
        DownloadErrors.WIFI_ONLY -> context.getString(R.string.images_wifi_only)
        DownloadErrors.CANCELLED -> null
        else -> context.getString(R.string.image_err_generic, detail ?: err)
    }

    /** 页面打开补扫断点（与 Application 级入口并发安全：repository 串行锁）。 */
    private fun resumePending() {
        val pending = systemImages.pendingDownloadIds()
        if (pending.isEmpty()) return
        if (pending.all { downloadJobs[it]?.isActive == true }) return
        viewModelScope.launch {
            try {
                systemImages.resumeInterruptedDownloads()
                refreshInstalled()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "page resume of interrupted downloads failed", t)
            }
        }
    }

    // ---------------------------------------------------------------- 激活 / §8.5

    /** §8.3「设为启动镜像」：checkActivation → 直接生效 / §8.5 对话框 / 拒绝标损坏。 */
    fun activate(id: String) {
        if (id == _state.value.activeId || vmBusy.value) return
        _state.update { it.copy(busyIds = it.busyIds + id, message = null) }
        viewModelScope.launch {
            try {
                val check = withContext(Dispatchers.IO) { systemImages.store.checkActivation(id) }
                when (check) {
                    is SystemImageStore.ActivationCheck.Rejected -> {
                        _state.update {
                            it.markCorrupt(id).copy(
                                busyIds = it.busyIds - id,
                                message = imageErrorMessage(context, check.reason, check.detail),
                            )
                        }
                    }
                    is SystemImageStore.ActivationCheck.Eligible -> {
                        if (check.decision.requiresReset()) {
                            val prompt = withContext(Dispatchers.IO) { buildResetPrompt(id, check.decision.code) }
                            _state.update { it.copy(resetPrompt = prompt, busyIds = it.busyIds - id) }
                        } else {
                            withContext(Dispatchers.IO) { systemImages.activate(id) }
                            refreshInstalled()
                            _state.update {
                                it.copy(
                                    busyIds = it.busyIds - id,
                                    message = context.getString(R.string.image_activated_next_boot),
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(busyIds = it.busyIds - id, message = imageErrorMessage(context, t)) }
            }
        }
    }

    private suspend fun buildResetPrompt(targetId: String, decision: String): ResetPrompt {
        val activeId = _state.value.activeId
        val from = activeId?.let { readIdentity(it) } ?: "—"
        val to = readIdentity(targetId) ?: "—"
        return ResetPrompt(targetId, from, to, decision)
    }

    private suspend fun readIdentity(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { systemImages.store.readImage(id) }.getOrNull()?.manifest?.image?.identity
    }

    /** §8.5 确认顺序（§5.2 规则 4）：先清零 storage.img，再 activate(allowReset=true)。 */
    fun confirmReset(keepData: Boolean) {
        val prompt = _state.value.resetPrompt ?: return
        _state.update { it.copy(resetPrompt = null, busyIds = it.busyIds + prompt.targetId) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!keepData) systemImages.rebuildStorageImage()
                    systemImages.activate(prompt.targetId, allowReset = true)
                }
                refreshInstalled()
                _state.update {
                    it.copy(
                        busyIds = it.busyIds - prompt.targetId,
                        message = context.getString(R.string.image_activated_next_boot),
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update {
                    it.copy(busyIds = it.busyIds - prompt.targetId, message = imageErrorMessage(context, t))
                }
            }
        }
    }

    fun cancelReset() = _state.update { it.copy(resetPrompt = null) }

    // ---------------------------------------------------------------- 校验 / 移除 / 恢复出厂

    /** §6.4 full 校验；通过治愈 CORRUPT 标记，失败标记并提示。 */
    fun verify(id: String) {
        if (_state.value.verifyingId != null) return
        _state.update { it.copy(verifyingId = id, message = null) }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { systemImages.verify(id, full = true) }
                _state.update {
                    it.copy(
                        // ok → 清 CORRUPT；fail → 标 CORRUPT（假治愈防线）。
                        // verifyingId 必须同批清掉，否则 spinner 卡死。
                        verifyingId = null,
                        rows = it.rows.map { row ->
                            if (row.imageId == id) row.copy(corrupt = !r.ok) else row
                        },
                        message = context.getString(
                            if (r.ok) R.string.image_verify_ok else R.string.image_verify_failed,
                        ),
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(verifyingId = null, message = imageErrorMessage(context, t)) }
            }
        }
    }

    fun promptRemove(id: String) = _state.update { it.copy(removePromptId = id) }

    fun cancelRemove() = _state.update { it.copy(removePromptId = null) }

    fun confirmRemove() {
        val id = _state.value.removePromptId ?: return
        _state.update { it.copy(removePromptId = null, busyIds = it.busyIds + id) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { systemImages.delete(id, src = "images_page") }
                refreshInstalled()
                _state.update {
                    it.copy(busyIds = it.busyIds - id, message = context.getString(R.string.images_removed, id))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(busyIds = it.busyIds - id, message = imageErrorMessage(context, t)) }
            }
        }
    }

    fun promptFactoryReset() = _state.update { it.copy(factoryResetPrompt = true) }

    fun cancelFactoryReset() = _state.update { it.copy(factoryResetPrompt = false) }

    /** §6.4/§13.2 设置级恢复出厂：整文件清零 storage.img（不需要 §8.5 确认链）。 */
    fun confirmFactoryReset() {
        _state.update { it.copy(factoryResetPrompt = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { systemImages.rebuildStorageImage() }
                _state.update { it.copy(message = context.getString(R.string.images_factory_done)) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(message = imageErrorMessage(context, t)) }
            }
        }
    }

    // ---------------------------------------------------------------- 导入

    /** §6.3 SAF 导入（与 Home 同链路：content:// → install 校验 → 原子落盘）。 */
    fun importImage(uri: Uri) {
        _state.update { it.copy(message = context.getString(R.string.importing), importing = true) }
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("cannot open $uri")
                    input.use { systemImages.install(it, src = uri.toString()) }
                }
                refreshInstalled()
                _state.update {
                    it.copy(importing = false, message = context.getString(R.string.image_imported, imported.imageId))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(importing = false, message = imageErrorMessage(context, t)) }
            }
        }
    }

    // ---------------------------------------------------------------- 杂项

    companion object {
        private const val TAG = "ImagesViewModel"
    }
}
