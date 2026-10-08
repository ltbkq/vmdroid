/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package io.github.ltbkq.vmdroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {

    @Test
    fun `quotes strings with spaces`() {
        assertEquals("'my nginx'", ShellQuote.quote("my nginx"))
    }

    @Test
    fun `leaves simple names unquoted`() {
        assertEquals("my-nginx", ShellQuote.quote("my-nginx"))
    }

    @Test
    fun `escapes embedded single quotes`() {
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
    }

    @Test
    fun `empty string becomes empty single quotes`() {
        assertEquals("''", ShellQuote.quote(""))
    }
}
