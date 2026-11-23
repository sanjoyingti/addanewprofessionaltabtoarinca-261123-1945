package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class RulePacks(private val store: BypassStore) {

    private val active = MutableStateFlow<List<RulePattern>>(emptyList())
    val activePatterns: StateFlow<List<RulePattern>> = active.asStateFlow()

    fun activeRules(): List<RulePattern> = active.value

    suspend fun list(): List<RulePack> = store.listRulePacks()

    suspend fun load(id: String): RulePack? {
        val pack = store.loadRulePack(id) ?: return null
        active.value = pack.patterns
        return pack
    }

    fun clear() {
        active.value = emptyList()
    }

    fun add(pattern: RulePattern) {
        active.value = active.value + pattern
    }

    fun remove(index: Int) {
        active.value = active.value.filterIndexed { i, _ -> i != index }
    }

    suspend fun saveAs(name: String): RulePack {
        val pack = RulePack(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "Rule pack" },
            created = System.currentTimeMillis(),
            patterns = active.value
        )
        store.saveRulePack(pack)
        return pack
    }

    suspend fun delete(id: String) = store.deleteRulePack(id)
}
