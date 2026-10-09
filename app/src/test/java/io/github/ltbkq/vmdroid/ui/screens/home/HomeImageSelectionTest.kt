package io.github.ltbkq.vmdroid.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.2 选择控件纯模型单测：两行结构、空态/加载失败/损坏三态推导、
 * 启动按钮形态（SHOW/HIDDEN/REVERIFY）、体积标签口径。
 */
class HomeImageSelectionTest {

    // ---------------------------------------------------------------- 行组装

    @Test
    fun buildRow_fullFields_line1NameVariant_line2IdSize() {
        val row = buildImageRow(
            imageId = "debian-minimal-arm64",
            fileLength = 148_000_000L,
            displayName = "Debian 13 (trixie)",
            variant = "最小化",
            identity = "debian:trixie",
            activeId = "debian-minimal-arm64",
        )
        assertEquals("Debian 13 (trixie) · 最小化", row.line1)
        assertEquals("debian-minimal-arm64 · 148 MB", row.line2)
        assertEquals("debian:trixie", row.identity)
        assertTrue(row.active)
        assertFalse(row.corrupt)
    }

    @Test
    fun buildRow_missingManifest_fallsBackToId_layoutNotCollapsed() {
        val row = buildImageRow(
            imageId = "debian-minimal-arm64",
            fileLength = 1_024L,
            displayName = null,
            variant = null,
            identity = null,
            activeId = null,
        )
        assertEquals("debian-minimal-arm64", row.line1)
        assertEquals("debian-minimal-arm64 · 1 kB", row.line2)
        assertNull(row.identity)
        assertFalse(row.active)
    }

    @Test
    fun buildRow_blankVariant_notAppended() {
        val row = buildImageRow(
            imageId = "x-arm64",
            fileLength = 0L,
            displayName = "Debian",
            variant = "  ",
            identity = null,
            activeId = null,
        )
        assertEquals("Debian", row.line1)
    }

    // ---------------------------------------------------------------- 状态推导

    @Test
    fun loaded_emptyConfirmed_isEmptyTrue_startHidden() {
        val state = HomeImageState.Loaded(rows = emptyList(), activeId = null)
        assertTrue(state.isEmpty)
        assertEquals(StartButtonMode.HIDDEN, startButtonMode(state))
    }

    @Test
    fun loaded_loadFailedNeverCountsAsEmpty() {
        // 加载失败：保留上次值 + inline error —— 即使一行都没有也不进空态
        val state = HomeImageState.Loaded(rows = emptyList(), activeId = null, loadFailed = true)
        assertFalse(state.isEmpty)
        // 无已知镜像且非空态 → 不误藏启动按钮（BootGuard 在 start() 兜底）
        assertEquals(StartButtonMode.SHOW, startButtonMode(state))
    }

    @Test
    fun loading_startButtonKeepsM1Behavior() {
        assertEquals(StartButtonMode.SHOW, startButtonMode(HomeImageState.Loading))
    }

    @Test
    fun displayedId_pendingWinsOverActive() {
        val row = buildImageRow("a", 1L, "A", null, "id:a", activeId = "a")
        val rowB = buildImageRow("b", 1L, "B", null, "id:b", activeId = "a")
        val state = HomeImageState.Loaded(listOf(row, rowB), activeId = "a")
        assertEquals("a", state.displayedId)
        val withPending = state.copy(pendingId = "b")
        assertEquals("b", withPending.displayedId)
    }

    @Test
    fun displayedCorrupt_drivesReverifyMode() {
        val good = buildImageRow("a", 1L, "A", null, null, activeId = "a")
        val state = HomeImageState.Loaded(listOf(good), activeId = "a")
        assertFalse(state.displayedCorrupt)
        assertEquals(StartButtonMode.SHOW, startButtonMode(state))

        // 激活检查拒绝 → 标损坏 + pendingId → 主按钮变「重新校验 / 重新下载」
        val corrupt = state.markCorrupt("a").copy(pendingId = "a")
        assertTrue(corrupt.displayedCorrupt)
        assertEquals(StartButtonMode.REVERIFY, startButtonMode(corrupt))
        assertTrue(corrupt.row("a")!!.corrupt)
        assertTrue(corrupt.row("a")!!.active) // 损坏标记不改变 active 归属
    }

    @Test
    fun markCorrupt_false_healsRow_reverifyBackToStart() {
        val row = buildImageRow("a", 1L, "A", null, null, activeId = "a")
        val corrupt = HomeImageState.Loaded(listOf(row), activeId = null)
            .markCorrupt("a")
            .copy(pendingId = "a")
        assertEquals(StartButtonMode.REVERIFY, startButtonMode(corrupt))

        val healed = corrupt.markCorrupt("a", value = false).copy(pendingId = null)
        assertFalse(healed.displayedCorrupt)
        assertEquals(StartButtonMode.SHOW, startButtonMode(healed))
    }

    @Test
    fun row_lookupAndMissing() {
        val row = buildImageRow("a", 1L, "A", null, null, activeId = null)
        val state = HomeImageState.Loaded(listOf(row), activeId = null)
        assertEquals(row, state.row("a"))
        assertNull(state.row("b"))
        assertNull(state.row(null))
    }

    // ---------------------------------------------------------------- 体积口径

    @Test
    fun formatBytes_decimalMbGbKb() {
        assertEquals("148 MB", formatBytes(148_000_000L))
        assertEquals("1.2 GB", formatBytes(1_200_000_000L))
        assertEquals("2 GB", formatBytes(2_000_000_000L))
        assertEquals("999 B", formatBytes(999L))
        assertEquals("1 kB", formatBytes(1_000L))
        assertEquals("0 B", formatBytes(0L))
    }
}
