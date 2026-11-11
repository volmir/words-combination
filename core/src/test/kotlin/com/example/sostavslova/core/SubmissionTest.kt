package com.example.sostavslova.core

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubmissionTest {
    private val index = WordIndex(listOf("бык", "колокол", "котик", "кот"))
    private val game = GameState("блоктик", listOf("кот"), listOf("кот"))

    private fun filter(allPositive: Boolean = false): KnownWords {
        val vectors = JSONObject(File("../tools/known_words_vectors.json").readText())
        val bytes = vectors.getString("binary_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (allPositive) {
            bytes.fill(0xff.toByte(), 28, bytes.size - 32)
            MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(bytes.size - 32))
                .copyInto(bytes, bytes.size - 32)
        }
        return assertNotNull(KnownWords.read(bytes))
    }

    @Test fun absentLetterHasExactCounts() {
        val submission = game.submit(" БЫК! ", index)
        assertEquals(AnswerResult.LETTERS_MISSING, submission.result)
        assertEquals(MissingLetter('ы', 0, 1), submission.missingLetter)
        assertFalse(submission.probabilistic)
        assertSame(game, submission.state)
    }

    @Test fun repeatedLetterAndFirstDeficitUseAlphabetOrder() {
        // И к, и о требуются дважды, доступны по одному. Первой идёт к.
        val submission = GameState("блокти", emptyList()).submit("колокол", index)
        assertEquals(MissingLetter('к', 1, 2), submission.missingLetter)
        val onlyO = GameState("колкли", emptyList()).submit("колокол", index)
        assertEquals(AnswerResult.LETTERS_MISSING, onlyO.result)
        assertEquals(MissingLetter('о', 1, 3), onlyO.missingLetter)
    }

    @Test fun buildableWordRemovedFromSelectionIsNotCounted() {
        val submission = game.submit("котик", index)
        assertEquals(AnswerResult.NOT_COUNTED, submission.result)
        assertNull(submission.missingLetter)
        assertFalse(submission.probabilistic)
        assertSame(game, submission.state)
    }

    @Test fun earlyChecksAndExactAcceptancePrecedeAllKnowledge() {
        val known = filter(true)
        for ((raw, expected) in listOf(
            "123" to AnswerResult.EMPTY,
            "бы" to AnswerResult.TOO_SHORT,
            "БЛОКТИК" to AnswerResult.SAME_AS_SOURCE,
            "КОТ" to AnswerResult.ALREADY_FOUND,
        )) {
            val submission = game.submit(raw, index, known)
            assertEquals(expected, submission.result)
            assertNull(submission.missingLetter)
            assertFalse(submission.probabilistic)
            assertSame(game, submission.state)
        }
        val fresh = game.copy(found = emptyList())
        val accepted = fresh.submit("кот", WordIndex(emptyList()), filter())
        assertEquals(AnswerResult.CORRECT, accepted.result)
        assertEquals(listOf("кот"), accepted.state.found)
        assertFalse(accepted.probabilistic)
        assertEquals(AnswerResult.CORRECT, fresh.submit("кот").result)
    }

    @Test fun fixedFilterDoesNotRecognizeInventedWordEvenWithMissingLetters() {
        val known = filter()
        assertFalse(known.mightContain("ыыыыы"))
        val submission = game.submit("ыыыыы", index, known)
        assertEquals(AnswerResult.NOT_FOUND, submission.result)
        assertNull(submission.missingLetter)
        assertFalse(submission.probabilistic)
        assertSame(game, submission.state)
    }

    @Test fun bloomPositiveOnlyExplainsRefusalAndExactKnowledgeTakesPrecedence() {
        val known = filter(true)
        assertTrue(known.mightContain("кик")) // Ложноположительное, не входит в исходный набор фильтра.
        val buildable = game.submit("кик", index, known)
        assertEquals(AnswerResult.NOT_COUNTED, buildable.result)
        assertTrue(buildable.probabilistic)
        assertEquals(AnswerResult.NOT_FOUND, game.submit("кик", index).result)
        val missing = game.submit("ыыыыы", index, known)
        assertEquals(AnswerResult.LETTERS_MISSING, missing.result)
        assertEquals(MissingLetter('ы', 0, 5), missing.missingLetter)
        assertTrue(missing.probabilistic)
        assertFalse(game.submit("котик", index, known).probabilistic)
        for (submission in listOf(buildable, missing)) {
            assertSame(game, submission.state)
            assertEquals(game.statistics(), submission.state.statistics())
        }
    }

    @Test fun filterNormalizationAndMissingFilterFallback() {
        val state = GameState("жик", emptyList())
        val submission = state.submit(" ЁЖИК ", knownWords = filter())
        assertEquals(AnswerResult.LETTERS_MISSING, submission.result)
        assertEquals(MissingLetter('е', 0, 1), submission.missingLetter)
        assertTrue(submission.probabilistic)
        assertEquals(AnswerResult.NOT_FOUND, state.submit("ежик").result)
        assertEquals(AnswerResult.NOT_FOUND, game.submit("котик").result)
    }

    @Test fun rejectionsPreserveTasksStarsHintsBudgetAndStatisticsEvenBeforeReconciliation() {
        val current = CurrentCampaignLevel(
            CampaignLevel(1, game.word, 1, 1, listOf(CampaignTask.T7("letter", 'к', 1))),
            game,
            // found уже выполняет задание, но ID ещё не согласован: отказ не выдаёт звезду.
            hints = HintState(shownSteps = 2, paidUnits = 1),
        )
        for (raw in listOf("123", "бы", game.word, "кот", "бык", "котик", "ыыыыы")) {
            val result = TaskRules.submit(current, raw, 4, index, filter())
            assertEquals(current, result.evaluation.state)
            assertTrue(result.evaluation.newlyCompletedTaskIds.isEmpty())
            assertEquals(0, result.evaluation.stars)
            assertEquals(HintRules.budget(current), HintRules.budget(result.evaluation.state))
            assertEquals(game.statistics(), result.evaluation.state.game.statistics())
            if (raw == "бык") assertEquals(MissingLetter('ы', 0, 1), result.missingLetter)
        }
    }
}
