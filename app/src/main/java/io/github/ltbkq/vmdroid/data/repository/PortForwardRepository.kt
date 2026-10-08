/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Persists port forwarding rules in DataStore.
 */
package io.github.ltbkq.vmdroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.ltbkq.vmdroid.x11.X11Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single port forwarding rule.
 * @param hostPort Port on the Android device
 * @param guestPort Port inside the VM
 * @param protocol "tcp" or "udp"
 * @param loopbackOnly Bind the host listener to 127.0.0.1 instead of 0.0.0.0.
 *   Set for the implicit VNC/audio forwards (the in-app viewer dials loopback),
 *   so an unauthenticated X session + raw PCM aren't exposed to the whole LAN.
 *   NOT serialized — implicit rules are never persisted; user-created rules keep
 *   the default (0.0.0.0) so they remain reachable from a PC.
 */
data class PortForwardRule(
    val hostPort: Int,
    val guestPort: Int,
    val protocol: String = "tcp",
    val loopbackOnly: Boolean = false,
) {
    // serialize/deserialize intentionally omit loopbackOnly: persistence format
    // is unchanged (sacred) and only user rules (loopbackOnly=false) persist.
    fun serialize(): String = "$protocol:$hostPort:$guestPort"

    companion object {
        private val VALID_PROTOCOLS = setOf("tcp", "udp")

        fun deserialize(s: String): PortForwardRule? {
            val parts = s.split(":")
            if (parts.size != 3) return null
            val proto = parts[0]
            if (proto !in VALID_PROTOCOLS) return null
            val host = parts[1].toIntOrNull() ?: return null
            val guest = parts[2].toIntOrNull() ?: return null
            if (host !in 1..65535 || guest !in 1..65535) return null
            return PortForwardRule(host, guest, proto)
        }
    }
}

/**
 * Removes any existing entry in [current] that shares the same
 * (hostPort, protocol) key as [newRule], then adds [newRule].
 *
 * The engine and UI treat (hostPort, protocol) as a unique key — two rules
 * with the same host port and protocol would produce duplicate QEMU hostfwd
 * arguments and a duplicate Compose key crash.
 */
/**
 * Ceiling on persisted rules. Not a human limit — it exists because
 * `podroid-forward add` in a shell loop can produce thousands, and every rule is
 * a listening socket, a row in the Settings list, and a QMP round trip on every
 * boot. One user scripted 1005 of them and made their VM unstartable.
 */
internal const val MAX_PORT_FORWARD_RULES = 2048

/** Outcome of [PortForwardRepository.addRule]. */
enum class AddRuleResult { ADDED, RESERVED, TABLE_FULL }

/**
 * Whether [newRule] would push the table past [max]. Replacing an existing
 * (hostPort, protocol) is not growth, so it stays allowed at the limit —
 * otherwise a full table could not be corrected, only cleared.
 */
internal fun portForwardTableIsFull(
    current: Set<String>,
    newRule: PortForwardRule,
    max: Int = MAX_PORT_FORWARD_RULES,
): Boolean {
    if (current.size < max) return false
    return current.none { serialized ->
        val existing = PortForwardRule.deserialize(serialized)
        existing != null &&
            existing.hostPort == newRule.hostPort &&
            existing.protocol == newRule.protocol
    }
}

internal fun deduplicatePortForwards(
    current: Set<String>,
    newRule: PortForwardRule,
): Set<String> {
    val filtered = current.filterTo(mutableSetOf()) { serialized ->
        val existing = PortForwardRule.deserialize(serialized)
        // Keep entries that differ in either hostPort or protocol.
        existing == null ||
            existing.hostPort != newRule.hostPort ||
            existing.protocol != newRule.protocol
    }
    filtered.add(newRule.serialize())
    return filtered
}

@Singleton
class PortForwardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_PORT_FORWARDS = stringSetPreferencesKey("port_forwards")

        /**
         * Host ports owned by the implicit, loopback-bound X11 display/audio
         * forwards (injected at launch, never persisted). A user rule on one of
         * these would shadow the implicit rule and bind 0.0.0.0, exposing the
         * no-auth X session + raw PCM to the whole LAN. They are rejected on add
         * and filtered from reads here — the single chokepoint feeding the launch
         * snapshot, the EngineHolder live-diff, and the Settings UI — so a stale
         * rule persisted by an older build can never be surfaced or applied.
         * SSH (9922) is intentionally LAN-reachable and is NOT reserved.
         */
        val RESERVED_HOST_PORTS = setOf(X11Constants.VNC_PORT, X11Constants.AUDIO_PORT)
    }

    val rules: Flow<List<PortForwardRule>> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[KEY_PORT_FORWARDS]
                ?.mapNotNull { PortForwardRule.deserialize(it) }
                ?.filter { it.hostPort !in RESERVED_HOST_PORTS }
                ?.sortedWith(compareBy({ it.hostPort }, { it.protocol }))
                ?: emptyList()
        }
        .distinctUntilChanged()

    /**
     * Persist a rule. Returns why it was refused when it was, so a caller that
     * can show the reason does not have to guess — the guest CLI in particular
     * used to print OK for a rule that was dropped here.
     */
    suspend fun addRule(rule: PortForwardRule): AddRuleResult {
        if (rule.hostPort in RESERVED_HOST_PORTS) return AddRuleResult.RESERVED
        var result = AddRuleResult.ADDED
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PORT_FORWARDS] ?: emptySet()
            if (portForwardTableIsFull(current, rule)) {
                result = AddRuleResult.TABLE_FULL
                return@edit
            }
            prefs[KEY_PORT_FORWARDS] = deduplicatePortForwards(current, rule)
        }
        return result
    }

    suspend fun removeRule(rule: PortForwardRule) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PORT_FORWARDS]?.toMutableSet() ?: return@edit
            current.remove(rule.serialize())
            prefs[KEY_PORT_FORWARDS] = current
        }
    }

    /**
     * Removes every persisted rule and reports how many were removed. All
     * entries in the DataStore set are user rules by construction (implicit
     * forwards never persist), so this is a plain wipe of [KEY_PORT_FORWARDS] -
     * no other DataStore key is touched.
     */
    suspend fun clearRules(): Int {
        var removed = 0
        context.dataStore.edit { prefs ->
            removed = prefs[KEY_PORT_FORWARDS]?.size ?: 0
            prefs[KEY_PORT_FORWARDS] = emptySet()
        }
        return removed
    }

    suspend fun getRulesSnapshot(): List<PortForwardRule> =
        context.dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                prefs[KEY_PORT_FORWARDS]
                    ?.mapNotNull { PortForwardRule.deserialize(it) }
                    ?.filter { it.hostPort !in RESERVED_HOST_PORTS }
                    ?: emptyList()
            }.first()
}
