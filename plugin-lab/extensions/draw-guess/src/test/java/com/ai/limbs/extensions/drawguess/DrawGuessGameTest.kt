package com.ai.limbs.extensions.drawguess

import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DrawGuessGameTest {
    private class Canvas : InProcessUiStateProvider {
        override val stateJson = MutableStateFlow<String?>("{}")
        val events = mutableListOf<String>()
        override suspend fun perform(eventId: String, payloadJson: String): String {
            events += eventId
            return if (eventId == "freeze") """{"base64":"YQ=="}""" else "{}"
        }
    }
    private suspend fun send(g: DrawGuessGame, actor: Player, event: String, p: JSONObject = JSONObject()) =
        g.event(actor, event, p.put("revision", g.revision))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected forbidden game action") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private suspend fun started(drawFirst: Boolean = true): Pair<DrawGuessGame, Canvas> {
        val values = ArrayDeque(listOf(6, 1)); val game = DrawGuessGame { values.removeFirst() }
        val canvas = Canvas(); game.connect(canvas)
        send(game, Player.AWEI, "open")
        send(game, Player.AWEI, "ready"); send(game, Player.LANER, "ready")
        send(game, Player.AWEI, "roll"); send(game, Player.LANER, "roll")
        send(game, Player.AWEI, "confirm_dice"); send(game, Player.LANER, "confirm_dice")
        send(game, Player.AWEI, "choose_order", JSONObject().put("drawFirst", drawFirst))
        return game to canvas
    }
    private suspend fun pictureReady(g: DrawGuessGame, drawer: Player = Player.AWEI, word: String = "自行车") {
        send(g, drawer, "seal_word", JSONObject().put("word", word))
        send(g, drawer, "seal_hints", JSONObject().put("hint1", "交通工具").put("hint2", "人力驱动"))
        send(g, drawer, "finish")
    }
    @Test fun preparationReturnsRulesAndRequiresBothPlayers() = runBlocking {
        val game = DrawGuessGame { 4 }
        val v = send(game, Player.LANER, "ready")
        assertEquals(GAME_RULES, v.getString("rules"))
        assertEquals("READY", v.getString("phase"))
        rejects { send(game, Player.LANER, "roll") }
        send(game, Player.AWEI, "ready")
        assertEquals(Phase.DICE, game.phase)
        assertTrue(game.phonePanel().getJSONArray("messages").length() > 0)
    }
    @Test fun waitingQueriesKeepRevisionAndStopRetryingWhenOtherPlayerIsReady() = runBlocking {
        val game = DrawGuessGame { 4 }
        send(game, Player.LANER, "open")
        val prepared = send(game, Player.LANER, "ready")
        assertTrue(prepared.getBoolean("success"))
        assertEquals("WAITING", prepared.getString("status"))
        assertEquals("AWEI", prepared.getString("waiting_for"))
        assertEquals(3_000L, prepared.getLong("retry_after_ms"))
        assertEquals("兰儿已准备，阿伟正在准备中。请等待3秒后再次查询游戏状态。", prepared.getString("message"))
        val revision = game.revision
        repeat(3) {
            val waiting = game.event(Player.LANER, "view")
            assertTrue(waiting.getBoolean("success"))
            assertEquals("WAITING", waiting.getString("status"))
            assertEquals(revision, waiting.getInt("revision"))
            assertEquals(1, waiting.getJSONArray("ready").length())
            assertEquals(0, waiting.getJSONArray("allowed").length())
            val next = waiting.getJSONObject("next_action")
            assertEquals("WAIT_THEN_QUERY", next.getString("type"))
            assertEquals("plugin.draw_guess.view", next.getJSONObject("capability").getString("name"))
            assertEquals(0, next.getJSONObject("capability").getJSONObject("parameters").length())
        }
        rejects { send(game, Player.LANER, "roll") }
        assertEquals(revision, game.revision)
        send(game, Player.AWEI, "ready")
        val next = game.event(Player.LANER, "view")
        assertEquals("DICE", next.getString("phase"))
        assertEquals("ACTION_REQUIRED", next.getString("status"))
        assertEquals("roll", next.getJSONArray("allowed").getString(0))
        assertFalse(next.has("retry_after_ms"))
        assertFalse(next.has("next_action"))
        assertFalse(next.has("waiting_for"))
    }
    @Test fun waitingIntervalIsConfigurableAndClosedGameDoesNotRequestPolling() = runBlocking {
        val game = DrawGuessGame(waitRetrySeconds = 6) { 4 }
        game.connect(Canvas())
        send(game, Player.LANER, "open")
        val waiting = send(game, Player.LANER, "ready")
        assertEquals(6_000L, waiting.getLong("retry_after_ms"))
        assertTrue(waiting.getString("message").contains("等待6秒"))
        val closed = send(game, Player.LANER, "exit")
        assertTrue(closed.getBoolean("success"))
        assertEquals("CLOSED", closed.getString("status"))
        assertFalse(closed.has("retry_after_ms"))
        assertFalse(closed.has("next_action"))
    }
    @Test fun diceIsSingleShotBothConfirmedAndTiesRestart() = runBlocking {
        val values = ArrayDeque(listOf(3, 3, 6, 1)); val game = DrawGuessGame { values.removeFirst() }
        send(game, Player.AWEI, "ready"); send(game, Player.LANER, "ready")
        send(game, Player.AWEI, "roll")
        rejects { send(game, Player.AWEI, "roll") }
        send(game, Player.LANER, "roll"); send(game, Player.AWEI, "confirm_dice")
        assertEquals(Phase.DICE, game.phase)
        send(game, Player.LANER, "confirm_dice")
        assertFalse(game.event(Player.AWEI, "view").has("yourDice"))
        send(game, Player.AWEI, "roll"); send(game, Player.LANER, "roll")
        send(game, Player.AWEI, "confirm_dice"); send(game, Player.LANER, "confirm_dice")
        assertEquals(Phase.ORDER, game.phase)
        rejects { send(game, Player.LANER, "choose_order", JSONObject().put("drawFirst", true)) }
        send(game, Player.AWEI, "choose_order", JSONObject().put("drawFirst", false))
        assertEquals("LANER", game.event(Player.AWEI, "view").getString("drawer"))
    }
    @Test fun sealedAnswerAndLockedHintsNeverEnterOpponentView() = runBlocking {
        val (game, canvas) = started()
        send(game, Player.AWEI, "seal_word", JSONObject().put("word", " 自行车 "))
        rejects { send(game, Player.AWEI, "seal_word", JSONObject().put("word", "另一个词")) }
        assertFalse(game.event(Player.LANER, "view").toString().contains("自行车"))
        send(game, Player.AWEI, "seal_hints", JSONObject().put("hint1", "交通工具").put("hint2", "人力驱动"))
        assertEquals(listOf("create"), canvas.events)
        rejects { game.event(Player.LANER, "canvas", JSONObject().put("revision", game.revision)) }
        send(game, Player.AWEI, "finish")
        val view = game.event(Player.LANER, "picture")
        assertEquals("***", view.getString("wordMask"))
        assertEquals(0, view.getJSONArray("hints").length())
        assertFalse(view.toString().contains("交通工具"))
        assertFalse(view.toString().contains("自行车"))
        assertTrue(view.has("mcp_content"))
    }
    @Test fun missesUnlockExactlyTwoHintsThenReleaseAndSwapAutomatically() = runBlocking {
        val (game, canvas) = started(); pictureReady(game)
        val first = send(game, Player.LANER, "guess", JSONObject().put("answer", "汽车"))
        assertTrue(first.getBoolean("success"))
        assertFalse(first.getJSONObject("lastResult").getBoolean("success"))
        assertEquals("交通工具", first.getJSONArray("hints").getString(0))
        assertEquals(2, first.getInt("remaining"))
        val second = send(game, Player.LANER, "guess", JSONObject().put("answer", "火车"))
        assertEquals(2, second.getJSONArray("hints").length())
        val third = send(game, Player.LANER, "guess", JSONObject().put("answer", "飞机"))
        assertEquals("WORD", third.getString("phase")); assertEquals("LANER", third.getString("drawer"))
        assertEquals(2, third.getInt("round"))
        assertEquals("自行车", third.getJSONObject("lastResult").getString("answer"))
        assertFalse(third.getJSONObject("lastResult").getBoolean("success"))
        assertEquals(listOf("create", "freeze", "release"), canvas.events)
        rejects { game.event(Player.LANER, "picture") }
    }
    @Test fun successfulGuessAlsoReleasesAndSwapsAndCountsCodePoints() = runBlocking {
        val (game, canvas) = started(); pictureReady(game, word = "猫🐈")
        assertEquals("**", game.event(Player.LANER, "view").getString("wordMask"))
        val v = send(game, Player.LANER, "guess", JSONObject().put("answer", " 猫🐈 "))
        assertTrue(v.getJSONObject("lastResult").getBoolean("success"))
        assertEquals(Phase.WORD, game.phase)
        assertEquals("release", canvas.events.last())
    }
    @Test fun lanerWordIsHiddenFromPhoneAndStaleEventsCannotMutate() = runBlocking {
        val (game, _) = started(drawFirst = false)
        val stale = game.revision
        send(game, Player.LANER, "seal_word", JSONObject().put("word", "画蛇添足"))
        assertFalse(game.phonePanel().toString().contains("画蛇添足"))
        rejects { game.event(Player.LANER, "seal_hints", JSONObject().put("revision", stale)
            .put("hint1", "提示一").put("hint2", "提示二")) }
        assertEquals(Phase.HINTS, game.phase)
        assertEquals("画蛇添足", game.event(Player.LANER, "view").getString("yourSealedWord"))
    }
    @Test fun emptyHintsAreRejectedAndExitCleansThenReopenRequiresPreparation() = runBlocking {
        val (game, canvas) = started()
        send(game, Player.AWEI, "seal_word", JSONObject().put("word", "猫"))
        rejects { send(game, Player.AWEI, "seal_hints", JSONObject().put("hint1", "").put("hint2", "动物")) }
        assertTrue(canvas.events.isEmpty())
        send(game, Player.AWEI, "exit")
        assertEquals(Phase.CLOSED, game.phase)
        send(game, Player.AWEI, "open")
        assertEquals(Phase.READY, game.phase)
        assertEquals(0, game.event(Player.LANER, "view").getJSONArray("ready").length())
    }
}
