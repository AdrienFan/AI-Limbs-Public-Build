package com.ai.limbs.extensions.drawguess

import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

internal const val GAME_RULES = "双方准备后各掷一次骰子并确认；同点重掷，赢家选先画或先猜。画方封存词语、填两条提示后画画并交图。猜方先得字数星号，最多猜三次；错一、错二分别解锁一条提示，错三失败。结束公布答案、清理临时画布并交换角色。答案去首尾空格后精确匹配。"
internal enum class Player { AWEI, LANER;
    fun other() = if (this == AWEI) LANER else AWEI
    fun label() = if (this == AWEI) "阿伟" else "兰儿"
}
internal enum class Phase { READY, DICE, ORDER, WORD, HINTS, DRAWING, GUESSING, CLOSED }

/** One in-memory business state machine. Public views are generated for a specific player. */
internal class DrawGuessGame(private val dice: () -> Int = { SecureRandom().nextInt(6) + 1 }) {
    private val mutex = Mutex()
    private var canvas: InProcessUiStateProvider? = null
    var phase = Phase.READY; private set
    var revision = 0; private set
    private var round = 0
    private val ready = mutableSetOf<Player>()
    private val rolls = mutableMapOf<Player, Int>()
    private val confirmed = mutableSetOf<Player>()
    private var winner: Player? = null
    private var drawer = Player.AWEI
    private var word = ""
    private var hints = emptyList<String>()
    private var misses = 0
    private var image: String? = null
    private var lastResult: JSONObject? = null
    private var open = false
    fun connect(endpoint: InProcessUiStateProvider) { check(canvas == null); canvas = endpoint }
    private fun endpoint() = requireNotNull(canvas) { "请先安装画室0.2.94或以上并激活子插件" }
    private fun requireDrawer(actor: Player) { require(actor == drawer) { "当前不是你的绘画回合" } }
    private fun requirePhase(expected: Phase) { check(phase == expected) { "当前阶段为${phase.name}" } }

    suspend fun event(actor: Player, event: String, p: JSONObject = JSONObject()): JSONObject = mutex.withLock {
        if (event == "view") return@withLock view(actor)
        if (event == "picture") {
            requirePhase(Phase.GUESSING); require(actor == drawer.other())
            return@withLock view(actor).put("mcp_content", JSONArray().put(JSONObject()
                .put("type", "image").put("mimeType", "image/png").put("data", requireNotNull(image))))
        }
        if (event == "open") {
            if (phase == Phase.CLOSED) reset()
            open = true
            return@withLock view(actor)
        }
        if (event == "ready") {
            requirePhase(Phase.READY)
            ready += actor
            if (ready.size == 2) phase = Phase.DICE
        } else {
            require(p.getInt("revision") == revision) { "回合状态已变化，请重新读取view" }
            when (event) {
                "roll" -> {
                    requirePhase(Phase.DICE); require(actor !in rolls) { "本轮骰子已掷出，不能重选" }
                    rolls[actor] = dice().also { require(it in 1..6) }
                }
                "confirm_dice" -> {
                    requirePhase(Phase.DICE); require(actor in rolls && actor !in confirmed)
                    confirmed += actor
                    if (confirmed.size == 2) {
                        if (rolls.getValue(Player.AWEI) == rolls.getValue(Player.LANER)) {
                            rolls.clear(); confirmed.clear()
                        } else {
                            winner = if (rolls.getValue(Player.AWEI) > rolls.getValue(Player.LANER)) Player.AWEI else Player.LANER
                            phase = Phase.ORDER
                        }
                    }
                }
                "choose_order" -> {
                    requirePhase(Phase.ORDER); require(actor == winner) { "只有掷骰赢家能选顺序" }
                    val first = p.getBoolean("drawFirst")
                    drawer = if (first) actor else actor.other()
                    round = 1; phase = Phase.WORD
                }
                "seal_word" -> {
                    requirePhase(Phase.WORD); requireDrawer(actor)
                    val sealed = p.getString("word").trim()
                    require(sealed.isNotEmpty() && sealed.codePointCount(0, sealed.length) <= 32 &&
                        sealed.none { it.isISOControl() }) { "题目须为1–32个字，不含控制字符" }
                    word = sealed; phase = Phase.HINTS
                }
                "seal_hints" -> {
                    requirePhase(Phase.HINTS); requireDrawer(actor)
                    val input = listOf(p.getString("hint1").trim(), p.getString("hint2").trim())
                    require(input.all { it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl) }) {
                        "两条提示都须填写，每条最多120字符"
                    }
                    endpoint().perform("create", JSONObject().put("drawer", drawer.name).toString())
                    hints = input; phase = Phase.DRAWING
                }
                "paint", "canvas", "preview" -> {
                    requirePhase(Phase.DRAWING); requireDrawer(actor)
                    val action = when (event) { "paint" -> "apply"; "canvas" -> "snapshot"; else -> "preview" }
                    val result = JSONObject(endpoint().perform(action, p.toString()))
                    if (event == "paint") revision++
                    val response = view(actor).put("canvas", result)
                    if (event == "preview") {
                        response.put("mcp_content", result.getJSONArray("mcp_content"))
                        result.remove("base64"); result.remove("mcp_content")
                    }
                    return@withLock response
                }
                "finish" -> {
                    requirePhase(Phase.DRAWING); requireDrawer(actor)
                    val result = JSONObject(endpoint().perform("freeze", "{}"))
                    image = result.getString("base64"); phase = Phase.GUESSING
                }
                "guess" -> {
                    requirePhase(Phase.GUESSING); require(actor == drawer.other()) { "只有猜题方能提交答案" }
                    val guess = p.getString("answer").trim()
                    require(guess.isNotEmpty() && guess.length <= 128) { "请填写猜测答案" }
                    val success = guess == word
                    if (!success) misses++
                    lastResult = JSONObject().put("round", round).put("guess", guess).put("success", success)
                        .put("finished", success || misses == 3).put("guesser", actor.name)
                    if (success || misses == 3) {
                        // Release before publishing the next round; nobody can fetch the old drawing afterwards.
                        endpoint().perform("release", "{}")
                        lastResult!!.put("answer", word)
                        image = null; word = ""; hints = emptyList(); misses = 0
                        drawer = drawer.other(); round++; phase = Phase.WORD
                    }
                }
                "exit" -> {
                    endpoint().perform("release", "{}")
                    word = ""; hints = emptyList(); image = null
                    phase = Phase.CLOSED; open = false
                }
                else -> error("未知游戏操作：$event")
            }
        }
        revision++
        view(actor)
    }
    private fun reset() {
        ready.clear(); rolls.clear(); confirmed.clear(); winner = null
        word = ""; hints = emptyList(); image = null; misses = 0; round = 0; lastResult = null
        phase = Phase.READY; revision++
    }
    private fun allowed(actor: Player): List<String> = when (phase) {
        Phase.READY -> if (actor !in ready) listOf("ready") else emptyList()
        Phase.DICE -> if (actor !in rolls) listOf("roll") else if (actor !in confirmed) listOf("confirm_dice") else emptyList()
        Phase.ORDER -> if (actor == winner) listOf("choose_order") else emptyList()
        Phase.WORD -> if (actor == drawer) listOf("seal_word") else emptyList()
        Phase.HINTS -> if (actor == drawer) listOf("seal_hints") else emptyList()
        Phase.DRAWING -> if (actor == drawer) listOf("canvas", "paint", "preview", "finish") else emptyList()
        Phase.GUESSING -> if (actor != drawer) listOf("picture", "guess") else emptyList()
        Phase.CLOSED -> emptyList()
    }
    fun view(actor: Player): JSONObject {
        val v = JSONObject().put("revision", revision).put("round", round).put("phase", phase.name)
            .put("player", actor.name).put("drawer", drawer.name).put("guesser", drawer.other().name)
            .put("ready", JSONArray(ready.map { it.name })).put("allowed", JSONArray(allowed(actor)))
            .put("rules", GAME_RULES).put("remaining", 3 - misses).put("open", open)
        rolls[actor]?.let { v.put("yourDice", it).put("diceConfirmed", actor in confirmed) }
        if (phase == Phase.ORDER) v.put("winner", winner!!.name)
            .put("dice", JSONObject().put("AWEI", rolls.getValue(Player.AWEI)).put("LANER", rolls.getValue(Player.LANER)))
        if (actor == drawer && phase in setOf(Phase.HINTS, Phase.DRAWING)) v.put("yourSealedWord", word)
        if (phase == Phase.GUESSING) {
            v.put("wordMask", "*".repeat(word.codePointCount(0, word.length)))
            v.put("hints", JSONArray(hints.take(misses)))
        }
        lastResult?.let { v.put("lastResult", JSONObject(it.toString())) }
        return v
    }
    suspend fun phonePanel(): JSONObject = mutex.withLock {
        val v = view(Player.AWEI)
        val messages = JSONArray()
        messages.put(when (phase) {
            Phase.READY -> GAME_RULES
            Phase.DICE -> "双方各掷一次并确认；同点时重新掷。你的点数：${v.opt("yourDice") ?: "尚未掷出"}"
            Phase.ORDER -> "${winner!!.label()}赢了：请选择先画或先猜。阿伟${rolls[Player.AWEI]}点，兰儿${rolls[Player.LANER]}点。"
            Phase.WORD -> "第${round}轮，${drawer.label()}填写并封存题目。"
            Phase.HINTS -> "${drawer.label()}填写两条提示，完成后开始画画。"
            Phase.DRAWING -> "第${round}轮，${drawer.label()}作画。画完请确认完成。"
            Phase.GUESSING -> "${drawer.other().label()}猜题：${v.getString("wordMask")}，剩余${3 - misses}次。"
            Phase.CLOSED -> "游戏已结束。"
        })
        lastResult?.let { r -> messages.put(if (r.getBoolean("finished"))
            "上一轮${if (r.getBoolean("success")) "猜中了" else "三次未猜中"}，答案：${r.getString("answer")}" else "刚才猜了：${r.getString("guess")}，未猜中。") }
        if (phase == Phase.GUESSING) hints.take(misses).forEachIndexed { index, hint -> messages.put("提示${index + 1}：$hint") }
        val fields = JSONArray(); val actions = JSONArray()
        fun field(id: String, label: String) { fields.put(JSONObject().put("id", id).put("label", label)) }
        fun button(event: String, title: String, extra: JSONObject = JSONObject()) {
            actions.put(JSONObject().put("event", event).put("title", title).put("parameters", extra)
                .put("style", if (event == "ready") "circle" else "normal"))
        }
        for (action in allowed(Player.AWEI)) when (action) {
            "ready" -> button(action, "准备")
            "roll" -> button(action, "掷骰子")
            "confirm_dice" -> button(action, "确定点数")
            "choose_order" -> { button(action, "先画", JSONObject().put("drawFirst", true)); button(action, "先猜", JSONObject().put("drawFirst", false)) }
            "seal_word" -> { field("word", "题目（确认后封存）"); button(action, "确定封存") }
            "seal_hints" -> { field("hint1", "第一次猜错后的提示"); field("hint2", "第二次猜错后的提示"); button(action, "完成提示，开始作画") }
            "finish" -> button(action, "确定完成")
            "guess" -> { field("answer", "你的答案"); button(action, "提交猜测") }
        }
        if (phase == Phase.READY && Player.AWEI in ready) messages.put("阿伟已准备，等待兰儿。")
        if (allowed(Player.AWEI).isEmpty() && phase != Phase.CLOSED) messages.put("等待兰儿操作。")
        val p = JSONObject().put("schema", 1).put("title", "你画我猜").put("open", open)
            .put("formKey", "$round:${phase.name}").put("revision", revision)
            .put("messages", messages).put("fields", fields).put("actions", actions)
        if (phase == Phase.GUESSING && drawer.other() == Player.AWEI) p.put("image", true)
        return@withLock p
    }
    suspend fun stop() = mutex.withLock {
        word = ""; hints = emptyList(); image = null; open = false; phase = Phase.CLOSED
    }
}
