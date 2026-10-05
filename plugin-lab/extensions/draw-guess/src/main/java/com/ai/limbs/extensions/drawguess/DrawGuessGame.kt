package com.ai.limbs.extensions.drawguess

import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

internal const val GAME_RULES = "双方准备后各掷一次骰子并确认；同点重掷，赢家选先画或先猜。每轮由当轮出题／绘画方选择游戏模式；当前只开放自由出题。画方封存词语、填两条提示后画画并交图。猜方先得字数星号，最多猜三次；错一、错二分别解锁一条提示，错三失败。结束公布答案、清理临时画布并交换角色，由新的画方选择下一轮模式，不重复开局掷骰。答案去首尾空格后精确匹配。"
internal const val LANER_SECRECY_REMINDER = "请注意：勿在任务进度、对外可见的推理说明或聊天中暴露自己的考题及尚未解锁的提示。考题和提示只通过游戏封存接口提交；提示按规则解锁，答案在本轮结束后公开。"
internal const val DEFAULT_WAIT_RETRY_SECONDS = 3
internal const val DEFAULT_LONG_WAIT_RETRY_SECONDS = 5
internal enum class Player { AWEI, LANER;
    fun other() = if (this == AWEI) LANER else AWEI
    fun label() = if (this == AWEI) "阿伟" else "兰儿"
}
internal enum class Phase { READY, DICE, ORDER, MODE, WORD, HINTS, DRAWING, GUESSING, CLOSED }

/** One in-memory business state machine. Public views are generated for a specific player. */
internal class DrawGuessGame(
    private val waitRetrySeconds: Int = DEFAULT_WAIT_RETRY_SECONDS,
    private val longWaitRetrySeconds: Int = DEFAULT_LONG_WAIT_RETRY_SECONDS,
    private val dice: () -> Int = { SecureRandom().nextInt(6) + 1 }
) {
    init { require(waitRetrySeconds > 0 && longWaitRetrySeconds > 0) { "等待查询间隔必须大于0秒" } }
    private val mutex = Mutex()
    private var canvas: InProcessUiStateProvider? = null
    var phase = Phase.READY; private set
    var revision = 0; private set
    private var round = 0
    private val ready = mutableSetOf<Player>()
    private val rolls = mutableMapOf<Player, Int>()
    private val confirmed = mutableSetOf<Player>()
    private var winner: Player? = null
    private var questionMode: String? = null
    private var diceAttempt = 1
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
                "choose_mode" -> {
                    requirePhase(Phase.MODE)
                    requireDrawer(actor)
                    require(p.getString("mode") == "FREE") { "系统出题模式暂未开放，当前仅支持自由出题" }
                    questionMode = "FREE"; phase = Phase.WORD
                }
                "roll" -> {
                    requirePhase(Phase.DICE); require(actor !in rolls) { "本轮骰子已掷出，不能重选" }
                    rolls[actor] = dice().also { require(it in 1..6) }
                }
                "confirm_dice" -> {
                    requirePhase(Phase.DICE); require(actor in rolls && actor !in confirmed)
                    confirmed += actor
                    if (confirmed.size == 2) {
                        if (rolls.getValue(Player.AWEI) == rolls.getValue(Player.LANER)) {
                            rolls.clear(); confirmed.clear(); diceAttempt++
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
                    round = 1; phase = Phase.MODE
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
                        drawer = drawer.other(); questionMode = null; round++; phase = Phase.MODE
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
        questionMode = null; diceAttempt = 1
        word = ""; hints = emptyList(); image = null; misses = 0; round = 0; lastResult = null
        phase = Phase.READY; revision++
    }
    // The AI entrance must advertise open before readiness, including after an exit.
    private fun allowed(actor: Player): List<String> =
        if (actor == Player.LANER && !open) listOf("open") else when (phase) {
        Phase.READY -> if (actor !in ready) listOf("ready") else emptyList()
        Phase.MODE -> if (actor == drawer) listOf("choose_mode") else emptyList()
        Phase.DICE -> if (actor !in rolls) listOf("roll") else if (actor !in confirmed) listOf("confirm_dice") else emptyList()
        Phase.ORDER -> if (actor == winner) listOf("choose_order") else emptyList()
        Phase.WORD -> if (actor == drawer) listOf("seal_word") else emptyList()
        Phase.HINTS -> if (actor == drawer) listOf("seal_hints") else emptyList()
        Phase.DRAWING -> if (actor == drawer) listOf("canvas", "paint", "preview", "finish") else emptyList()
        Phase.GUESSING -> if (actor != drawer) listOf("picture", "guess") else emptyList()
        Phase.CLOSED -> emptyList()
    }
    private fun waitIntervalSeconds(): Int = when (phase) {
        Phase.WORD, Phase.HINTS, Phase.DRAWING -> longWaitRetrySeconds
        else -> waitRetrySeconds
    }
    private fun opponentStatus(): JSONObject {
        val (state, message) = when (phase) {
            Phase.READY -> if (Player.AWEI in ready) "READY" to "阿伟已准备"
                else "PREPARING" to "阿伟正在准备中"
            Phase.MODE -> if (drawer == Player.AWEI) "CHOOSING_MODE" to "阿伟正在选择游戏模式"
                else "WAITING_FOR_LANER" to "阿伟正在等待兰儿选择游戏模式"
            Phase.DICE -> if (Player.AWEI !in rolls) "DICE_NOT_ROLLED" to "阿伟尚未掷骰子"
                else if (Player.AWEI !in confirmed) "DICE_UNCONFIRMED" to "阿伟已掷骰子，等待确认点数"
                else "DICE_CONFIRMED" to "阿伟已确认点数"
            Phase.ORDER -> if (winner == Player.AWEI) "CHOOSING_ORDER" to "阿伟正在选择先画或先猜"
                else "WAITING_FOR_LANER" to "阿伟正在等待兰儿选择先画或先猜"
            Phase.WORD -> if (drawer == Player.AWEI) "ENTERING_WORD" to "等待阿伟填写并封存题目"
                else "WAITING_FOR_LANER" to "阿伟正在等待兰儿封存题目"
            Phase.HINTS -> if (drawer == Player.AWEI) "ENTERING_HINTS" to "等待阿伟填写并封存两条提示"
                else "WAITING_FOR_LANER" to "阿伟正在等待兰儿填写并封存两条提示"
            Phase.DRAWING -> if (drawer == Player.AWEI) "DRAWING" to "等待阿伟绘画并确认完成"
                else "WAITING_FOR_LANER" to "阿伟正在等待兰儿绘画并交图"
            Phase.GUESSING -> if (drawer == Player.LANER) {
                val detail = if (misses == 0) "等待阿伟提交猜测答案"
                    else "阿伟第" + misses + "次猜测未命中，等待下一次猜测"
                "GUESSING" to detail
            } else "WAITING_FOR_LANER" to "阿伟正在等待兰儿提交猜测答案"
            Phase.CLOSED -> "CLOSED" to "游戏已结束"
        }
        return JSONObject().put("player", Player.AWEI.name).put("state", state).put("message", message)
    }
    private fun lanerActionMessage(actions: List<String>, opponent: JSONObject): String {
        if (phase == Phase.CLOSED) return "游戏已结束，停止查询。"
        if (!open) return "游戏尚未打开，请先打开游戏。"
        val context = when (phase) {
            Phase.DICE -> if (diceAttempt > 1) "上一轮骰子同点，双方重新掷骰。"
                else "双方已准备，进入掷骰阶段。"
            Phase.ORDER -> "兰儿赢得选择权。"
            Phase.MODE -> "第" + round + "轮，由兰儿选择本轮游戏模式。"
            Phase.WORD -> "第" + round + "轮，由兰儿出题并绘画。"
            else -> ""
        }
        val labels = actions.map { action ->
            when (action) {
                "ready" -> "准备"
                "roll" -> "掷骰子"
                "confirm_dice" -> "确认点数"
                "choose_order" -> "选择先画或先猜"
                "choose_mode" -> "选择并确认本轮游戏模式"
                "seal_word" -> "填写并封存题目"
                "seal_hints" -> "填写并封存两条提示"
                "canvas" -> "读取自己的画布"
                "paint" -> "绘画"
                "preview" -> "预览自己的画"
                "finish" -> "确认完成并交图"
                "picture" -> "读取待猜图片"
                "guess" -> "提交猜测答案"
                else -> error("未知兰儿动作：" + action)
            }
        }
        return context + opponent.getString("message") + "。兰儿现在可以" + labels.joinToString("、") + "。"
    }
    fun view(actor: Player): JSONObject {
        val actions = allowed(actor)
        // Execution success is separate from whether a guess was correct in lastResult.
        val v = JSONObject().put("success", true)
            .put("revision", revision).put("round", round).put("phase", phase.name)
            .put("player", actor.name).put("drawer", drawer.name).put("guesser", drawer.other().name)
            .put("ready", JSONArray(ready.map { it.name })).put("allowed", JSONArray(actions))
            .put("rules", GAME_RULES).put("remaining", 3 - misses).put("open", open)
        questionMode?.let { v.put("question_mode", it) }
        if (phase in setOf(Phase.MODE, Phase.WORD, Phase.HINTS, Phase.DRAWING, Phase.GUESSING)) v.put("mode_chooser", drawer.name)
        if (phase in setOf(Phase.DICE, Phase.ORDER)) v.put("dice_attempt", diceAttempt)
        if (actor == Player.LANER) {
            val opponent = opponentStatus()
            val waiting = open && phase != Phase.CLOSED && actions.isEmpty()
            v.put("opponent_status", opponent)
                .put("status", if (waiting) "WAITING" else if (phase == Phase.CLOSED) "CLOSED" else "ACTION_REQUIRED")
            if (waiting) {
                val seconds = waitIntervalSeconds()
                val pending = if (phase == Phase.READY) "兰儿已准备，" + opponent.getString("message")
                    else opponent.getString("message")
                // Only a state query is retried; gameplay actions remain explicit and revision-checked.
                v.put("waiting_for", Player.AWEI.name)
                    .put("message", pending + "。请等待" + seconds + "秒后再次查询游戏状态。")
                    .put("retry_after_ms", seconds.toLong() * 1_000L)
                    .put("next_action", JSONObject().put("type", "WAIT_THEN_QUERY")
                        .put("capability", JSONObject().put("name", "$GAME_CAPABILITIES.view")
                            .put("parameters", JSONObject())))
            } else v.put("message", lanerActionMessage(actions, opponent))
            if (drawer == Player.LANER && phase in setOf(Phase.WORD, Phase.HINTS, Phase.DRAWING, Phase.GUESSING)) {
                v.put("message", v.getString("message") + LANER_SECRECY_REMINDER)
            }
        }
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
            Phase.MODE -> "第${round}轮，由${drawer.label()}选择出题模式。系统出题暂未开放，低／中／高难度预留；自由出题由画方自己出题并绘画。"
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
        fun button(event: String, title: String, extra: JSONObject = JSONObject(), enabled: Boolean = true) {
            actions.put(JSONObject().put("event", event).put("title", title).put("parameters", extra)
                .put("enabled", enabled).put("style", if (event == "ready") "circle" else "normal"))
        }
        for (action in allowed(Player.AWEI)) when (action) {
            "ready" -> button(action, "准备")
            "choose_mode" -> {
                button(action, "系统出题（暂未开放）", JSONObject().put("mode", "SYSTEM"), enabled = false)
                button(action, "自由出题（确认）", JSONObject().put("mode", "FREE"))
            }
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
