package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random

class DefinitionHintTest {
    @Test
    fun masksDecomposedYoAndShortIAsWholeTokens() {
        assertEquals("*** служит условным примером.", DefinitionHint.prepare("ежик", "Е\u0308жик служит условным примером."))
        assertEquals("*** служит условным примером.", DefinitionHint.prepare("йод", "И\u0306од служит условным примером."))
    }

    @Test
    fun masksAnswerFormsRootsAndStressWithoutRevealingTheirLength() {
        val text = "ДЕ́РЕВО, деревья и деревянный предмет служат условным примером."
        assertEquals("***, *** и *** предмет служат условным примером.",
            DefinitionHint.prepare("дерево", text, forms = listOf("деревья")))
    }

    @Test
    fun usesObservedCognatesToHandleAlternatingRoots() {
        val hint = DefinitionHint.prepare("рука", "Ручной прибор удерживают руками возле большого стола.",
            forms = listOf("руками"), related = listOf("ручной"))
        assertEquals("*** прибор удерживают *** возле большого стола.", hint)
        assertNotNull(DefinitionHint.prepare("лев", "Львиный след виден возле широкой тропинки.", forms = listOf("льва")))
    }

    @Test
    fun preservesPunctuationUnrelatedWordsAndFunctionWords() {
        val hint = DefinitionHint.prepare("кот", "Животное, которое живёт рядом с человеком; котёнок прячется.")
        assertEquals("Животное, которое живёт рядом с человеком; *** прячется.", hint)
        assertFalse(DefinitionHint.sharesRoot("игра", "и"))
        assertFalse(DefinitionHint.sharesRoot("кот", "комар"))
        assertTrue(DefinitionHint.sharesRoot("дерево", "деревянный"))
    }

    @Test
    fun declinesEmptyReferenceOnlyAndMostlyMaskedClues() {
        for (text in listOf("", "Дом", "Домашний дом", "То же, что дом.", "разг. то же, что дом.",
                "Уменьш. к дому.", "Действие по значению глагола домить.", "<b>Жилое здание</b>")) {
            assertNull(DefinitionHint.prepare("дом", text), text)
        }
        assertNotNull(DefinitionHint.prepare("дом", "Здание для постоянного проживания людей."))
    }

    @Test
    fun declinesLinksIncludingEncodedAnswerButAllowsPlainAbbreviations() {
        for (link in listOf("https://example.org/%D0%B4%D0%BE%D0%BC", "http://example.org/дом", "www.example.org/дом")) {
            assertNull(DefinitionHint.prepare("дом", "Синтетический предмет описан в $link"))
        }
        assertNotNull(DefinitionHint.prepare("сайт", "Участок всемирной сети WWW."))
    }

    @Test
    fun hintCandidatesAreBoundedDistinctAndNeverAlreadyFound() {
        val game = GameState("торт", listOf("рот", "тор", "тот"), found = listOf("рот"))
        val candidates = game.hintCandidates(Random(42), 2)
        assertEquals(setOf("тор", "тот"), candidates.toSet())
        assertEquals(candidates, game.hintCandidates(Random(42), 2))
        assertEquals(1, game.hintCandidates(Random(42), 1).size)
        assertTrue(game.copy(found = game.allWords).hintCandidates().isEmpty())
    }
}
