package com.ai.limbs.extensions.drawguess

import org.junit.Assert.*
import org.junit.Test

class SystemQuestionBankTest {
    private fun q(word: String, difficulty: QuestionDifficulty) =
        SystemQuestion(word, difficulty, "测试", "第一条线索", "第二条线索")

    @Test fun authoredBankHasThirtyValidUniqueQuestionsInEachDifficulty() {
        assertEquals(90, SYSTEM_QUESTIONS.size)
        assertEquals(90, SYSTEM_QUESTIONS.map { it.word }.distinct().size)
        for (difficulty in listOf(QuestionDifficulty.LOW, QuestionDifficulty.MEDIUM, QuestionDifficulty.HIGH))
            assertEquals(30, SYSTEM_QUESTIONS.count { it.difficulty == difficulty })
        val bank = SystemQuestionBank()
        assertEquals(90, bank.remaining(QuestionDifficulty.RANDOM, emptySet()))
        for (entry in SYSTEM_QUESTIONS) {
            assertTrue(entry.word.codePointCount(0, entry.word.length) in 1..32)
            assertTrue(entry.category.isNotBlank())
            assertNotEquals(entry.hint1, entry.hint2)
            for (hint in listOf(entry.hint1, entry.hint2)) {
                assertTrue(hint.isNotBlank() && hint.length <= 120)
                assertFalse(hint.contains(entry.word))
            }
        }
    }
    @Test fun randomDrawIndexesAllEligibleWordsRatherThanChoosingDifficultyFirst() {
        val questions = listOf(q("猫", QuestionDifficulty.LOW), q("船", QuestionDifficulty.LOW),
            q("塔", QuestionDifficulty.MEDIUM), q("灯", QuestionDifficulty.HIGH))
        questions.indices.forEach { index ->
            var bound = 0
            val bank = SystemQuestionBank(questions) { size -> bound = size; index }
            assertEquals(questions[index], bank.pick(QuestionDifficulty.RANDOM, emptySet()))
            assertEquals(4, bound)
        }
        var bound = 0
        val bank = SystemQuestionBank(questions) { size -> bound = size; size - 1 }
        assertEquals("船", bank.pick(QuestionDifficulty.LOW, emptySet()).word)
        assertEquals(2, bound)
        assertEquals("塔", bank.pick(QuestionDifficulty.RANDOM, setOf("猫", "船", "灯")).word)
        assertEquals(1, bound)
    }
    @Test fun exhaustedScopesRejectInsteadOfRepeatingAndUsedWordsFilterEveryScope() {
        val bank = SystemQuestionBank(listOf(q("猫", QuestionDifficulty.LOW), q("塔", QuestionDifficulty.HIGH))) { 0 }
        assertEquals(0, bank.remaining(QuestionDifficulty.LOW, setOf("猫")))
        assertEquals(1, bank.remaining(QuestionDifficulty.RANDOM, setOf("猫")))
        assertEquals("塔", bank.pick(QuestionDifficulty.RANDOM, setOf("猫")).word)
        try { bank.pick(QuestionDifficulty.LOW, setOf("猫")); fail("Expected exhausted scope") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("本局已用完")) }
        try { bank.pick(QuestionDifficulty.RANDOM, setOf("猫", "塔")); fail("Expected exhausted bank") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("本局已用完")) }
    }
}
