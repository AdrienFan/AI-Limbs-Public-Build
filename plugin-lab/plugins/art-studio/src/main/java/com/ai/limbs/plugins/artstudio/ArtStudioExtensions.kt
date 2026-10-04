package com.ai.limbs.plugins.artstudio

import com.ai.limbs.plugin.runtime.ChildExtensionBinding
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val ART_EXTENSION_POINT = "$ART_ID.extension_menu"
internal const val ART_EXTENSION_API = 1
internal const val ART_EXTENSION_MENUS = "$ART_ID.extension_menus"

internal data class ArtExtensionItem(val id: String, val title: String, val enabled: Boolean)
internal data class ArtExtensionRow(val extensionId: String, val binding: String, val item: ArtExtensionItem) {
    fun request(): String = JSONObject().put("extensionId", extensionId).put("binding", binding)
        .put("id", item.id).toString()
}

/** This point accepts menu state/event bindings, not shared-component definitions or parent commands. */
internal object ArtExtensionMenuSchema {
    fun items(raw: String): List<ArtExtensionItem> {
        require(raw.length <= 16384) { "扩展菜单状态超过16384字符限制" }
        val state = JSONObject(raw)
        require(state.getInt("schema") == 1) { "画室扩展菜单schema必须为1" }
        val items = state.getJSONArray("items")
        require(items.length() <= 32) { "每个扩展最多32个菜单项" }
        val parsed = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = item.getString("id")
            require(id.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) { "扩展菜单id格式错误" }
            val title = item.getString("title").trim()
            require(title.isNotBlank() && title.length <= 80 && title.none { it.isISOControl() }) {
                "扩展菜单标题须为1–80个可显示字符"
            }
            ArtExtensionItem(id, title, item.getBoolean("enabled"))
        }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "扩展菜单id不能重复" }
        return parsed
    }
    fun rows(raw: String): List<ArtExtensionRow> {
        val root = JSONObject(raw)
        require(root.getInt("schema") == 1) { "画室扩展目录schema不匹配" }
        val rows = root.getJSONArray("items")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            ArtExtensionRow(row.getString("extensionId"), row.getString("binding"),
                ArtExtensionItem(row.getString("id"), row.getString("title"), row.getBoolean("enabled")))
        }
    }
}

/** Resident-owned routing retains Host-attested child identities; presentation sees only menu data. */
internal class ArtStudioExtensionMenus(private val scope: CoroutineScope,
    private val reportError: (String, Exception) -> Unit,
    private val recordUse: (String) -> Unit) : InProcessUiStateProvider, AutoCloseable {
    private class Entry(val binding: ChildExtensionBinding, val token: String,
        val provider: InProcessUiStateProvider, var items: List<ArtExtensionItem>) {
        lateinit var observer: Job
    }
    private val entries = linkedMapOf<String, Entry>()
    private val state = MutableStateFlow("""{"schema":1,"items":[]}""")
    override val stateJson: StateFlow<String?> = state
    private var closed = false

    @Synchronized fun bind(binding: ChildExtensionBinding): AutoCloseable {
        check(!closed) { "画室扩展点已关闭" }
        require(binding.target.parentPluginId == ART_ID && binding.target.point == ART_EXTENSION_POINT &&
            binding.target.apiVersion == ART_EXTENSION_API) { "画室扩展目标或API不匹配" }
        require(binding.extensionId !in entries) { "画室扩展已绑定" }
        val provider = binding.payload as? InProcessUiStateProvider
            ?: error("画室菜单扩展必须publish InProcessUiStateProvider")
        val initial = ArtExtensionMenuSchema.items(requireNotNull(provider.stateJson.value) {
            "画室菜单扩展必须提供初始状态"
        })
        val entry = Entry(binding, UUID.randomUUID().toString(), provider, initial)
        entry.observer = scope.launch(start = CoroutineStart.LAZY) {
            provider.stateJson.collect { raw ->
                try {
                    val items = ArtExtensionMenuSchema.items(requireNotNull(raw) { "扩展菜单状态为空" })
                    synchronized(this@ArtStudioExtensionMenus) {
                        if (entries[binding.extensionId] === entry && entry.items != items) {
                            entry.items = items
                            publish()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // Invalid new state removes its menu; never keep stale executable items.
                    synchronized(this@ArtStudioExtensionMenus) {
                        if (entries[binding.extensionId] === entry) {
                            entry.items = emptyList()
                            publish()
                        }
                    }
                    reportError(binding.extensionId, error)
                }
            }
        }
        entries[binding.extensionId] = entry
        publish()
        entry.observer.start()
        return AutoCloseable {
            synchronized(this) {
                if (entries[binding.extensionId] === entry) {
                    entries.remove(binding.extensionId)
                    entry.observer.cancel()
                    publish()
                }
            }
        }
    }
    private fun publish() {
        val rows = JSONArray()
        for ((id, entry) in entries) for (item in entry.items) rows.put(JSONObject()
            .put("extensionId", id).put("binding", entry.token)
            .put("id", item.id).put("title", item.title).put("enabled", item.enabled))
        state.value = JSONObject().put("schema", 1).put("items", rows).toString()
    }
    override suspend fun perform(eventId: String, payloadJson: String): String {
        require(eventId == "activate") { "画室扩展目录只接受activate事件" }
        val request = JSONObject(payloadJson)
        val id = request.getString("extensionId")
        val menuId = request.getString("id")
        val entry = synchronized(this) {
            check(!closed) { "画室扩展点已关闭" }
            val current = requireNotNull(entries[id]) { "此画室扩展已停用或卸载" }
            require(current.token == request.getString("binding")) { "画室扩展版本已改变，请重新打开菜单" }
            // The observer may not have processed the latest disabled state yet.
            val liveItems = ArtExtensionMenuSchema.items(requireNotNull(current.provider.stateJson.value))
            require(liveItems.any { it.id == menuId && it.enabled }) { "此扩展菜单项不可用" }
            current
        }
        recordUse(id)
        // Only the child's own event channel executes. No parent capability identity is lent out.
        return entry.provider.perform(menuId, "{}")
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { it.observer.cancel() }
        entries.clear()
        publish()
    }
}
