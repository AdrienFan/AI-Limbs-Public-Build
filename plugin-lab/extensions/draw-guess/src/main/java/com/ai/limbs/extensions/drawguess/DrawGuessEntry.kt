package com.ai.limbs.extensions.drawguess

import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

internal const val GAME_ID = "plugin.art.studio.draw_guess"
// Child Runtime scopes capability names to the final extension-id segment.
internal val GAME_CAPABILITIES = "plugin.${GAME_ID.substringAfterLast('.')}"

class DrawGuessEntry : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        require(host.extensionId == GAME_ID)
        require(host.target.parentPluginId == "plugin.art.studio" &&
            host.target.point == "plugin.art.studio.extension_menu" && host.target.apiVersion == 1)
        val game = DrawGuessGame()
        val panelState = MutableStateFlow<String?>(game.phonePanel().toString())
        suspend fun publish() { panelState.value = game.phonePanel().toString() }
        val panel = object : InProcessUiStateProvider {
            override val stateJson = panelState
            override suspend fun perform(eventId: String, payloadJson: String): String {
                // This private channel is routed only by the matching parent phone panel.
                val result = game.event(Player.AWEI, eventId, JSONObject(payloadJson))
                publish()
                return result.toString()
            }
        }
        val menu = object : InProcessUiStateProvider {
            override val stateJson = MutableStateFlow<String?>(
                """{"schema":1,"items":[{"id":"open","title":"你画我猜","enabled":true}]}""")
            override suspend fun perform(eventId: String, payloadJson: String): String {
                require(eventId == "open")
                val result = game.event(Player.AWEI, "open")
                publish(); return result.toString()
            }
        }
        val handles = mutableListOf<AutoCloseable>()
        val examples = mapOf(
            "open" to JSONObject(), "view" to JSONObject(), "ready" to JSONObject(),
            "roll" to JSONObject().put("revision", 2), "confirm_dice" to JSONObject().put("revision", 3),
            "choose_order" to JSONObject().put("revision", 5).put("drawFirst", true),
            "seal_word" to JSONObject().put("revision", 6).put("word", "自行车"),
            "seal_hints" to JSONObject().put("revision", 7).put("hint1", "交通工具").put("hint2", "人力驱动"),
            "canvas" to JSONObject().put("revision", 8), "preview" to JSONObject().put("revision", 8),
            "paint" to JSONObject().put("revision", 8).put("type", "STROKE_ADD").put("params", JSONObject()
                .put("id", "f91df829-61bc-48af-8866-000000000001")
                .put("tool", "ink").put("color", "#FF245364").put("width", 6)
                .put("points", JSONArray().put(JSONArray().put(40).put(50)).put(JSONArray().put(180).put(130)))),
            "finish" to JSONObject().put("revision", 9), "picture" to JSONObject(),
            "guess" to JSONObject().put("revision", 10).put("answer", "自行车"),
            "exit" to JSONObject().put("revision", 10))
        val titles = mapOf("open" to "兰儿打开游戏", "view" to "读取游戏阶段和下一步", "ready" to "兰儿准备并读取极简规则",
            "roll" to "兰儿掷骰子", "confirm_dice" to "兰儿确认点数", "choose_order" to "赢家选择先画或先猜",
            "seal_word" to "兰儿封存题目", "seal_hints" to "兰儿封存两条提示并开画",
            "canvas" to "读取兰儿自己的临时画布", "paint" to "兰儿画一笔", "preview" to "预览兰儿自己的画",
            "finish" to "兰儿确认画完并交图", "picture" to "兰儿接收待猜图片", "guess" to "兰儿提交猜测", "exit" to "结束游戏并清理临时画布")
        try {
            for ((event, example) in examples) {
                val properties = JSONObject(); val required = JSONArray(); val specs = mutableListOf<InProcessCapabilityParameterSpec>()
                for (key in example.keys()) {
                    val type = when (example.get(key)) { is JSONObject -> "object"; is Boolean -> "boolean"; is Number -> "integer"; else -> "string" }
                    properties.put(key, JSONObject().put("type", type).put("description",
                        if (key == "revision") "先读取view，使用当前revision" else key))
                    required.put(key); specs += InProcessCapabilityParameterSpec(key, type, key, true)
                }
                handles += host.registerCapability(InProcessCapabilitySpec(id = "$GAME_CAPABILITIES.$event",
                    displayName = requireNotNull(titles[event]),
                    description = "你画我猜：${titles[event]}。仅操作兰儿身份；先view，按allowed执行。" +
                        when (event) {
                            "open" -> "无需参数；打开后再调用ready。已结束的游戏恢复到准备阶段；重复打开不重置进行中的回合。"
                            "ready" -> "准备后若status为WAITING，按retry_after_ms等待后调用view查询，不重复准备或提前执行下一步。" + GAME_RULES
                            "view" -> "查询不改变游戏状态。若status为WAITING，按retry_after_ms等待后再次调用view；其他状态按allowed执行。"
                            "paint" -> "修改时带当前revision；STROKE_ADD的params必须含唯一UUID格式的id、points和width；每笔使用新的id。"
                            else -> "修改时带当前revision；猜题阶段用picture取图，不能读取对方题目或绘画记录。"
                        },
                    keywords = listOf("你画我猜", "画室游戏", event), parameters = specs,
                    suggestedParamsJson = example.toString(),
                    inputSchema = JSONObject().put("type", "object").put("properties", properties)
                        .put("required", required).put("additionalProperties", false).toString(),
                    effect = if (event in setOf("view", "picture", "canvas", "preview")) InProcessCapabilityEffect.READ_ONLY
                        else InProcessCapabilityEffect.STATE_CHANGE,
                    domain = InProcessCapabilityDomain.PLUGIN,
                    executor = InProcessCapabilityExecutor { raw ->
                        try {
                            val result = game.event(Player.LANER, event, JSONObject(raw))
                            publish(); result.toString()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            host.logger.e("DrawGuess", "Game action failed: $event", error)
                            throw error
                        }
                    }))
            }
            handles += host.publishAiIngressDiscovery(ChildAiIngressDiscovery("art_studio.draw_guess.v1",
                JSONObject().put("name", "你画我猜").put("rules", GAME_RULES)
                    .put("start", "$GAME_CAPABILITIES.open").put("ready", "$GAME_CAPABILITIES.ready")
                    .put("view", "$GAME_CAPABILITIES.view")
                    .put("instruction", "Use only LANER game capabilities. Open the game before ready. Read view after context changes. When status is WAITING, wait retry_after_ms then call view again; do not repeat ready or advance before allowed changes. Never inspect the opponent's private form or ordinary project history. Gameplay is ephemeral.")
                    .toString()))
            host.publish(mapOf("schema" to 1, "menu" to menu, "panel" to panel,
                "connect" to Consumer<InProcessUiStateProvider>(game::connect)),
                metadata = mapOf("kind" to "art_studio_interactive_v1"))
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.close() }
            game.stop(); throw error
        }
        return ChildExtensionHandle {
            handles.asReversed().forEach { it.close() }
            game.stop(); panelState.value = null
        }
    }
}
