package com.ai.limbs.extensions.drawguess

import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DrawGuessSystemTest {
    private val low = SystemQuestion("灯塔", QuestionDifficulty.LOW, "建筑", "位于水边的建筑", "夜间发光引导船只")
    private val high = SystemQuestion("风车", QuestionDifficulty.HIGH, "设施", "利用自然力量的设施", "叶片被空气推动旋转")
    private class Canvas : InProcessUiStateProvider {
        override val stateJson = MutableStateFlow<String?>("{}")
        val events = mutableListOf<String>()
        var failCreate = false
        override suspend fun perform(eventId: String, payloadJson: String): String {
            if (eventId == "create" && failCreate) error("测试：画布创建失败")
            events += eventId
            return if (eventId == "freeze") """{"base64":"YQ=="}""" else "{}"
        }
    }
    private suspend fun send(g: DrawGuessGame, actor: Player, event: String, p: JSONObject = JSONObject()) =
        g.event(actor, event, p.put("revision", g.revision))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected rejected action") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private suspend fun prepare(game: DrawGuessGame, drawFirst: Boolean = true) {
        send(game, Player.AWEI, "open")
        send(game, Player.AWEI, "ready"); send(game, Player.LANER, "ready")
        send(game, Player.AWEI, "roll"); send(game, Player.LANER, "roll")
        send(game, Player.AWEI, "confirm_dice"); send(game, Player.LANER, "confirm_dice")
        send(game, Player.AWEI, "choose_order", JSONObject().put("drawFirst", drawFirst))
    }
    private suspend fun game(drawFirst: Boolean = true): Pair<DrawGuessGame, Canvas> {
        var rolls = 0
        val game = DrawGuessGame(questionBank = SystemQuestionBank(listOf(low, high)) { 0 }) {
            if (rolls++ % 2 == 0) 6 else 1
        }
        val canvas = Canvas(); game.connect(canvas); prepare(game, drawFirst)
        return game to canvas
    }
    private suspend fun system(g: DrawGuessGame, drawer: Player, difficulty: String) {
        send(g, drawer, "choose_mode", JSONObject().put("mode", "SYSTEM"))
        send(g, drawer, "choose_difficulty", JSONObject().put("difficulty", difficulty))
    }
    private suspend fun win(g: DrawGuessGame, drawer: Player) {
        val answer = g.view(drawer).getJSONObject("your_system_question").getString("word")
        send(g, drawer, "finish")
        send(g, drawer.other(), "guess", JSONObject().put("answer", answer))
    }
    @Test fun selectingDifficultyWaitsThreeSecondsAndBackDoesNotConsumeQuestions() = runBlocking {
        val (game, canvas) = game()
        send(game, Player.AWEI, "choose_mode", JSONObject().put("mode", "SYSTEM"))
        assertEquals(Phase.DIFFICULTY, game.phase)
        val waiting = game.event(Player.LANER, "view")
        assertEquals("CHOOSING_DIFFICULTY", waiting.getJSONObject("opponent_status").getString("state"))
        assertEquals(3_000L, waiting.getLong("retry_after_ms"))
        assertEquals(2, waiting.getJSONObject("difficulty_remaining").getInt("RANDOM"))
        val revision = game.revision
        repeat(3) { assertEquals(revision, game.event(Player.LANER, "view").getInt("revision")) }
        rejects { send(game, Player.LANER, "choose_difficulty", JSONObject().put("difficulty", "LOW")) }
        rejects { send(game, Player.LANER, "back_mode") }
        rejects { send(game, Player.AWEI, "choose_difficulty", JSONObject().put("difficulty", "BAD")) }
        assertEquals(revision, game.revision)
        val buttons = game.phonePanel().getJSONArray("actions")
        assertEquals(5, buttons.length())
        assertEquals("RANDOM", buttons.getJSONObject(3).getJSONObject("parameters").getString("difficulty"))
        assertFalse(buttons.getJSONObject(1).getBoolean("enabled"))
        assertEquals("back_mode", buttons.getJSONObject(4).getString("event"))
        send(game, Player.AWEI, "back_mode")
        assertEquals(Phase.MODE, game.phase)
        assertFalse(game.view(Player.AWEI).has("question_mode"))
        assertEquals(2, game.view(Player.AWEI).getJSONObject("difficulty_remaining").getInt("RANDOM"))
        assertTrue(canvas.events.isEmpty())
    }
    @Test fun systemSecretsOnlyReachDrawerAndQueriesCannotRerollOrReplaceThem() = runBlocking {
        val (game, canvas) = game()
        system(game, Player.AWEI, "LOW")
        assertEquals(Phase.DRAWING, game.phase)
        assertEquals(listOf("create"), canvas.events)
        val own = game.view(Player.AWEI)
        assertEquals("LOW", own.getString("difficulty_choice"))
        assertEquals("LOW", own.getString("question_difficulty"))
        assertEquals(low.word, own.getJSONObject("your_system_question").getString("word"))
        assertTrue(game.phonePanel().toString().contains(low.word))
        assertTrue(game.phonePanel().toString().contains(low.hint1))
        val other = game.event(Player.LANER, "view")
        assertEquals(5_000L, other.getLong("retry_after_ms"))
        assertFalse(other.has("your_system_question"))
        assertFalse(other.has("yourSealedWord"))
        assertFalse(other.toString().contains(low.word))
        assertFalse(other.toString().contains(low.hint1))
        val revision = game.revision
        repeat(3) { assertEquals(low.word, game.event(Player.AWEI, "view").getJSONObject("your_system_question").getString("word")) }
        rejects { send(game, Player.AWEI, "choose_difficulty", JSONObject().put("difficulty", "HIGH")) }
        rejects { send(game, Player.AWEI, "back_mode") }
        rejects { send(game, Player.AWEI, "seal_word", JSONObject().put("word", "新题")) }
        rejects { send(game, Player.AWEI, "seal_hints", JSONObject().put("hint1", "新提示").put("hint2", "新提示二")) }
        assertEquals(revision, game.revision)
        send(game, Player.AWEI, "finish")
        val picture = game.event(Player.LANER, "picture")
        assertEquals("**", picture.getString("wordMask"))
        assertEquals(0, picture.getJSONArray("hints").length())
        val first = send(game, Player.LANER, "guess", JSONObject().put("answer", "错一"))
        assertEquals(low.hint1, first.getJSONArray("hints").getString(0))
        assertFalse(first.toString().contains(low.hint2))
        assertFalse(first.toString().contains(low.word))
        val second = send(game, Player.LANER, "guess", JSONObject().put("answer", "错二"))
        assertEquals(low.hint2, second.getJSONArray("hints").getString(1))
        val finished = send(game, Player.LANER, "guess", JSONObject().put("answer", "错三"))
        assertEquals(low.word, finished.getJSONObject("lastResult").getString("answer"))
        assertEquals(Phase.MODE, game.phase)
        assertFalse(finished.has("your_system_question"))
        assertFalse(finished.has("difficulty_choice"))
        assertEquals(0, finished.getJSONObject("difficulty_remaining").getInt("LOW"))
        assertEquals("LANER", finished.getString("mode_chooser"))
        assertEquals(listOf("create", "freeze", "release"), canvas.events)
    }
    @Test fun nextDrawerChoosesAgainAndRandomExcludesEarlierRoundAcrossRoles() = runBlocking {
        val (game, _) = game()
        system(game, Player.AWEI, "LOW"); win(game, Player.AWEI)
        system(game, Player.LANER, "RANDOM")
        val own = game.view(Player.LANER)
        assertEquals("RANDOM", own.getString("difficulty_choice"))
        assertEquals("HIGH", own.getString("question_difficulty"))
        assertEquals(high.word, own.getJSONObject("your_system_question").getString("word"))
        assertTrue(own.getString("message").contains(LANER_SECRECY_REMINDER))
        val phone = game.phonePanel().toString()
        assertFalse(phone.contains(high.word)); assertFalse(phone.contains(high.hint1))
        assertFalse(game.view(Player.AWEI).has("your_system_question"))
        win(game, Player.LANER)
        assertEquals(0, game.view(Player.AWEI).getJSONObject("difficulty_remaining").getInt("RANDOM"))
        val buttons = game.phonePanel().getJSONArray("actions")
        assertFalse(buttons.getJSONObject(0).getBoolean("enabled"))
        assertTrue(buttons.getJSONObject(1).getBoolean("enabled"))
        val revision = game.revision
        rejects { send(game, Player.AWEI, "choose_mode", JSONObject().put("mode", "SYSTEM")) }
        assertEquals(revision, game.revision)
        send(game, Player.AWEI, "choose_mode", JSONObject().put("mode", "FREE"))
        assertEquals(Phase.WORD, game.phase)
    }
    @Test fun exhaustedSingleDifficultyDoesNotChangeStateAndNewGameResetsUsage() = runBlocking {
        val (game, _) = game()
        system(game, Player.AWEI, "LOW"); win(game, Player.AWEI)
        send(game, Player.LANER, "choose_mode", JSONObject().put("mode", "SYSTEM"))
        val revision = game.revision
        rejects { send(game, Player.LANER, "choose_difficulty", JSONObject().put("difficulty", "LOW")) }
        assertEquals(revision, game.revision); assertEquals(Phase.DIFFICULTY, game.phase)
        assertEquals(0, game.view(Player.LANER).getJSONObject("difficulty_remaining").getInt("LOW"))
        send(game, Player.LANER, "exit"); prepare(game)
        assertEquals(2, game.view(Player.AWEI).getJSONObject("difficulty_remaining").getInt("RANDOM"))
        assertFalse(game.view(Player.AWEI).has("difficulty_choice"))
        assertFalse(game.view(Player.AWEI).has("your_system_question"))
    }
    @Test fun freeQuestionsAreAlsoExcludedFromSystemPool() = runBlocking {
        val (game, _) = game()
        send(game, Player.AWEI, "choose_mode", JSONObject().put("mode", "FREE"))
        send(game, Player.AWEI, "seal_word", JSONObject().put("word", low.word))
        send(game, Player.AWEI, "seal_hints", JSONObject().put("hint1", "自己写的线索").put("hint2", "另一条线索"))
        send(game, Player.AWEI, "finish")
        send(game, Player.LANER, "guess", JSONObject().put("answer", low.word))
        system(game, Player.LANER, "RANDOM")
        assertEquals(high.word, game.view(Player.LANER).getJSONObject("your_system_question").getString("word"))
    }
    @Test fun failedCanvasCreationDoesNotConsumeOrPublishSelectedQuestion() = runBlocking {
        val (game, canvas) = game()
        send(game, Player.AWEI, "choose_mode", JSONObject().put("mode", "SYSTEM"))
        val revision = game.revision
        canvas.failCreate = true
        rejects { send(game, Player.AWEI, "choose_difficulty", JSONObject().put("difficulty", "LOW")) }
        assertEquals(revision, game.revision)
        assertEquals(Phase.DIFFICULTY, game.phase)
        assertEquals(1, game.view(Player.AWEI).getJSONObject("difficulty_remaining").getInt("LOW"))
        assertFalse(game.view(Player.AWEI).has("your_system_question"))
        canvas.failCreate = false
        send(game, Player.AWEI, "choose_difficulty", JSONObject().put("difficulty", "LOW"))
        assertEquals(low.word, game.view(Player.AWEI).getJSONObject("your_system_question").getString("word"))
        assertEquals(listOf("create"), canvas.events)
    }
}
