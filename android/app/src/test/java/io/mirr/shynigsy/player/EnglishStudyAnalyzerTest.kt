package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class EnglishStudyAnalyzerTest {
    @Test fun looksUpRegisteredIrregularFormsWithoutGuessingSuffixes() {
        val result = EnglishStudyAnalyzer.analyze("Children went home and took books. Glorped clas blargsons.")
        val entries = result.vocabulary.map { it.expression }
        assertTrue(entries.containsAll(listOf("child", "go", "home", "take", "book")))
        assertTrue(result.unknownWords.containsAll(listOf("glorped", "clas", "blargsons")))
        assertFalse(entries.any { it in setOf("glorp", "cla", "blargson") })
    }

    @Test fun phrasesKeepReadingOrderAndSuppressTheirConstituentWords() {
        val result = EnglishStudyAnalyzer.analyze("We gave up. Take care of children. I look forward to tomorrow.")
        val entries = result.vocabulary.map { it.expression }
        assertTrue(entries.indexOf("give up") < entries.indexOf("take care of"))
        assertTrue(entries.indexOf("take care of") < entries.indexOf("look forward to"))
        assertFalse(entries.contains("give"))
        assertFalse(entries.contains("take"))
        assertFalse(entries.contains("care"))
        assertFalse(entries.contains("look"))
        assertTrue(entries.contains("child"))
    }

    @Test fun phrasesMatchWholeWordsAndDoNotBridgePunctuationOrNewCues() {
        for (source in listOf("look forward tomorrow to", "look. Forward to tomorrow", "look\nforward to tomorrow", "outlook forward to tomorrow")) {
            val result = EnglishStudyAnalyzer.analyze(source)
            assertFalse(source, result.vocabulary.any { it.expression == "look forward to" })
        }
    }

    @Test fun ignoresCaseAndNormalizesCurlyContractionsButKeepsOriginalExamples() {
        val source = "I’VE already GONE home. She isn’t working. We won’t go."
        val result = EnglishStudyAnalyzer.analyze(source)
        assertEquals(source, result.sourceText)
        assertTrue(result.grammar.any { it.title == "have/has/had + 과거분사" && it.example == "I’VE already GONE" })
        assertTrue(result.grammar.any { it.title == "be + -ing 진행형" && it.example == "isn’t working" })
        assertTrue(result.grammar.any { it.title == "조동사 + 동사원형" && it.example == "won’t go" })
        assertFalse(result.unknownWords.any { '\'' in it || '’' in it })
        assertTrue(result.grammar.all { source.contains(it.example) })
    }

    @Test fun detectsModalPerfectBeforeTheSimpleModalRule() {
        val result = EnglishStudyAnalyzer.analyze("You should have taken the car. We could’ve gone home.")
        assertEquals(1, result.grammar.size)
        assertEquals("조동사 + have + 과거분사", result.grammar.single().title)
        assertEquals("should have taken", result.grammar.single().example)
        assertTrue(result.vocabulary.any { it.expression == "take" })
    }

    @Test fun distinguishesIrregularPastFromPastParticiple() {
        assertTrue(EnglishStudyAnalyzer.analyze("I have gone. She has seen it. They had taken it.")
            .grammar.any { it.title == "have/has/had + 과거분사" })
        assertTrue(EnglishStudyAnalyzer.analyze("I have went. She has saw it. They had took it.").grammar.isEmpty())
    }

    @Test fun doesNotTreatPossessionOrNotOnlyAsPerfectOrSimpleNegation() {
        val result = EnglishStudyAnalyzer.analyze("I had a car. She has a book. He is not only happy but also kind.")
        assertFalse(result.grammar.any { it.title.contains("과거분사") || it.title.contains("부정문") })
        assertTrue(result.grammar.any { it.title == "not only ... but also 병렬 표현" })
    }

    @Test fun unknownVerbsCannotTriggerAConfidentGrammarExplanation() {
        val result = EnglishStudyAnalyzer.analyze("She has glorped. He is blarging. We should snorf. They are going to zzorp.")
        assertTrue(result.grammar.isEmpty())
        assertTrue(result.unknownWords.containsAll(listOf("glorped", "blarging", "snorf", "zzorp")))
    }

    @Test fun aGrammarMatchNeverBridgesASentenceOrCueBoundary() {
        for (source in listOf("We have. Taken it.", "We have! Taken it.", "We have? Taken it.", "We have; taken it.", "We have\ntaken it.")) {
            assertTrue(source, EnglishStudyAnalyzer.analyze(source).grammar.isEmpty())
        }
    }

    @Test fun supportsSeparateSentencesAcrossTheWholePassage() {
        val result = EnglishStudyAnalyzer.analyze("We have eaten. I am working. The car was stolen. You have to go. She used to work.")
        val titles = result.grammar.map { it.title }
        assertTrue(titles.containsAll(listOf("have/has/had + 과거분사", "be + -ing 진행형", "be + 과거분사: 수동·상태", "have to + 동사원형", "used to + 동사원형")))
        assertEquals(5, result.grammar.size)
        assertFalse(result.truncated)
    }

    @Test fun recognizesCompletedContinuousAndPassiveConstructionsWithoutDuplicateSubrules() {
        val result = EnglishStudyAnalyzer.analyze("I have been working. The book has been written.")
        assertEquals(listOf("완료진행형", "완료 수동태"), result.grammar.map { it.title })
        assertEquals(listOf("have been working", "has been written"), result.grammar.map { it.example })
    }

    @Test fun explainsAmbiguousContractionsWithoutChoosingASingleMeaning() {
        val result = EnglishStudyAnalyzer.analyze("He’s gone. I’d read the book. She’d taken the car.")
        assertTrue(result.grammar.any { it.title == "'s + 과거분사: 축약형 확인" && it.explanation.contains("has 또는 is") })
        assertTrue(result.grammar.any { it.title == "'d + 동사: 축약형 확인" && it.explanation.contains("had 또는 would") })
        assertTrue(result.grammar.any { it.title == "have/has/had + 과거분사" && it.example == "She’d taken" })
    }

    @Test fun recognizesGoingToBeforeProgressiveAndBeUsedToWithoutHabitualMislabel() {
        val going = EnglishStudyAnalyzer.analyze("We are going to leave.")
        assertEquals("be going to + 동사원형", going.grammar.single().title)
        val accustomed = EnglishStudyAnalyzer.analyze("I am used to work.")
        assertFalse(accustomed.grammar.any { it.title == "used to + 동사원형" })
        assertFalse(accustomed.vocabulary.any { it.expression == "used to" })
        assertFalse(EnglishStudyAnalyzer.analyze("Are you used to work?").vocabulary.any { it.expression == "used to" })
    }

    @Test fun registeredPhraseVariantsAndUnambiguousContractionsUseTheSameDictionaryEntry() {
        val result = EnglishStudyAnalyzer.analyze("ALRIGHT. I’m able to go. They’re about to leave.")
        assertTrue(result.vocabulary.map { it.expression }.containsAll(listOf("all right", "be able to", "be about to")))
        assertFalse(result.unknownWords.contains("alright"))
    }

    @Test fun supportsComparisonsAndInfinitivesWithKnownAdjectivesAndVerbs() {
        val result = EnglishStudyAnalyzer.analyze("It is too small to see. He is old enough to drive. She is as happy as me. This is more important than that. He is better than me. I want to leave.")
        assertTrue(result.grammar.map { it.title }.containsAll(listOf(
            "too + 형용사 + to부정사", "형용사 + enough + to부정사", "as + 형용사 + as 동등 비교",
            "more/less + 형용사 + than 비교", "비교급 + than", "동사 뒤의 to부정사",
        )))
    }

    @Test fun recognizesQuestionsNegationLetAndAdvice() {
        val result = EnglishStudyAnalyzer.analyze("Do you know? Why did she leave? I don’t know. Are you happy? She is not ready. Let’s go. You had better leave.")
        assertTrue(result.grammar.map { it.title }.containsAll(listOf(
            "do/does/did 의문문 어순", "do/does/did not + 동사원형", "be동사 의문문 어순",
            "be동사 부정문", "let + 목적어 + 동사원형", "had better + 동사원형",
        )))
    }

    @Test fun relativeClauseNeedsAKnownAntecedentAndKnownVerb() {
        val valid = EnglishStudyAnalyzer.analyze("The man who helped us left. The book that I read is here.")
        assertTrue(valid.grammar.any { it.title == "명사 뒤의 관계절 후보" })
        assertFalse(EnglishStudyAnalyzer.analyze("I know that she left. The man who glorped left.").grammar.any { it.title == "명사 뒤의 관계절 후보" })
    }

    @Test fun stripsSubtitleMarkupWithoutLeakingTagNamesAndKeepsTextComparisons() {
        val result = EnglishStudyAnalyzer.analyze("{\\an8}<v Alex><i>I&#39;ve</i> taken&nbsp;the car.</v><br>[music] 2 < 3 &amp; 5 > 4")
        assertEquals("I've taken the car.\n  2 < 3 & 5 > 4", result.sourceText)
        assertTrue(result.grammar.any { it.example == "I've taken" })
        assertFalse(result.unknownWords.any { it in setOf("alex", "an", "nbsp", "amp", "music", "br") })
    }

    @Test fun preservesBracketedSpokenTextAndDoesNotSplitUnicodeWordsIntoEnglishFragments() {
        val result = EnglishStudyAnalyzer.analyze("[I have gone.] café 안녕하세요 日本語")
        assertTrue(result.sourceText.contains("[I have gone.]"))
        assertTrue(result.grammar.any { it.example == "have gone" })
        assertFalse(result.unknownWords.contains("caf"))
    }

    @Test fun emptyOrNonEnglishInputHasNoInventedContent() {
        for (source in listOf("", "   ", "안녕하세요. 日本語 123 ♪", "<i>[music]</i>")) {
            val result = EnglishStudyAnalyzer.analyze(source)
            assertTrue(result.vocabulary.isEmpty())
            assertTrue(result.grammar.isEmpty())
            assertTrue(result.unknownWords.isEmpty())
        }
    }

    @Test fun capsInputAndDoesNotLookUpAPartialTrailingWord() {
        val source = " ".repeat(3_998) + "books"
        val result = EnglishStudyAnalyzer.analyze(source)
        assertTrue(result.truncated)
        assertTrue(result.sourceText.length <= 4_000)
        assertTrue(result.vocabulary.isEmpty())
        assertTrue(result.unknownWords.isEmpty())
        assertTrue(EnglishStudyAnalyzer.analyze("Hello", truncated = true).truncated)
    }

    @Test fun inputCapDoesNotSplitAUnicodeSurrogatePair() {
        val result = EnglishStudyAnalyzer.analyze(" ".repeat(3_999) + "😀")
        assertTrue(result.truncated)
        assertTrue(result.sourceText.none { it.isSurrogate() })
        assertTrue(result.unknownWords.isEmpty())
    }

    @Test fun vocabularyLimitIsReportedWithoutChangingReadingOrder() {
        val knownWords = EnglishStudyLexicon.words.take(30).map { it.lemma }
        val result = EnglishStudyAnalyzer.analyze(knownWords.joinToString(". "))
        assertEquals(24, result.vocabulary.size)
        assertTrue(result.truncated)
        assertEquals(knownWords.filterNot { it == "be" }.take(24), result.vocabulary.map { it.expression })
    }

    @Test fun grammarLimitIsReportedAfterDistinctRulesAreCollected() {
        val result = EnglishStudyAnalyzer.analyze("We should have gone. I have been working. The book has been written. I have eaten. He is running. It was stolen. We are going to leave. I used to work. We have to go. You can help.")
        assertEquals(8, result.grammar.size)
        assertTrue(result.truncated)
    }

    @Test fun unknownLimitIsReportedAndNeverSuppliesDictionaryMeanings() {
        val unknown = ('a'..'z').map { "zzorp$it" }
        val result = EnglishStudyAnalyzer.analyze(unknown.joinToString(" "))
        assertEquals(unknown.take(20), result.unknownWords)
        assertTrue(result.vocabulary.isEmpty())
        assertTrue(result.grammar.isEmpty())
        assertTrue(result.truncated)
    }

    @Test fun repeatedOccurrencesAreDeduplicatedWithoutClaimingTruncation() {
        val result = EnglishStudyAnalyzer.analyze("I have gone. We have gone. They have gone. We gave up. They give up.")
        assertEquals(1, result.vocabulary.count { it.expression == "go" })
        assertEquals(1, result.vocabulary.count { it.expression == "give up" })
        assertEquals(1, result.grammar.count { it.title == "have/has/had + 과거분사" })
        assertFalse(result.truncated)
    }
}
