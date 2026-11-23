package io.mirr.shynigsy.player

import java.util.Locale

internal data class StudyVocabulary(val expression: String, val meaning: String, val partOfSpeech: String)
internal data class StudyGrammar(val title: String, val explanation: String, val example: String)
internal data class StudyExplanation(
    val sourceText: String,
    val vocabulary: List<StudyVocabulary>,
    val grammar: List<StudyGrammar>,
    val unknownWords: List<String>,
    val truncated: Boolean = false,
)

/** A bounded, device-only dictionary lookup and conservative pattern recognizer, not a translator. */
internal object EnglishStudyAnalyzer {
    private const val MaxCharacters = 4_000
    private const val MaxVocabulary = 24
    private const val MaxGrammar = 8
    private const val MaxUnknown = 20

    private data class Token(val word: String, val start: Int, val end: Int)
    private data class VocabularyHit(val position: Int, val entry: StudyLexiconEntry)
    private data class GrammarHit(val start: Int, val end: Int, val priority: Int, val id: String, val title: String, val explanation: String)
    private data class Phrase(val words: List<String>, val entry: StudyLexiconEntry)
    private data class PhraseHit(val index: Int, val phrase: Phrase)

    private val wordPattern = Regex("(?<![\\p{L}\\p{N}_])[A-Za-z]+(?:['’][A-Za-z]+)*(?![\\p{L}\\p{N}_])")
    private val spaceGap = Regex("[ \\t\\u00a0]+")
    private val relativeGap = Regex("[ ,\\t\\u00a0]+")
    private val sentenceBoundary = Regex("[.!?;:\\r\\n]")
    private val words by lazy {
        buildMap<String, StudyLexiconEntry> {
            EnglishStudyLexicon.words.forEach { entry ->
                putIfAbsent(normalize(entry.lemma), entry)
                entry.forms.forEach { putIfAbsent(normalize(it), entry) }
            }
        }
    }
    private val phrases by lazy {
        EnglishStudyLexicon.phrases.flatMap { entry ->
            (setOf(entry.lemma) + entry.forms).map { variant ->
                Phrase(tokenize(variant).map { it.word }, entry)
            }
        }.filter { it.words.isNotEmpty() }.sortedByDescending { it.words.size }
    }
    private val phrasesByFirstWord by lazy { phrases.groupBy { it.words.first() } }

    // Function words are still available to the grammar recognizer, without filling the vocabulary list.
    private val functionWords = setOf(
        "a", "an", "the", "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "mine", "yours", "hers", "ours", "theirs", "myself", "yourself",
        "himself", "herself", "itself", "ourselves", "yourselves", "themselves", "this", "that", "these", "those",
        "am", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did",
        "can", "could", "may", "might", "must", "shall", "should", "will", "would", "not", "no", "yes",
        "and", "or", "but", "if", "so", "as", "than", "to", "of", "in", "on", "at", "by", "for", "from",
        "with", "without", "into", "onto", "about", "up", "down", "out", "off", "over", "under", "through",
        "who", "whom", "whose", "which", "what", "when", "where", "why", "how", "there", "here",
        "some", "any", "all", "each", "every", "both", "either", "neither", "much", "many", "more", "most",
        "less", "least", "such", "very", "too", "also", "just", "only", "even", "ever", "never", "already",
        "still", "yet", "again", "then", "now", "well", "oh", "ah", "uh", "um", "hmm", "s", "'s", "'d",
    )
    private val subjects = setOf("i", "you", "he", "she", "it", "we", "they")
    private val objects = setOf("me", "you", "him", "her", "it", "us", "them")
    private val beForms = setOf("am", "is", "are", "was", "were", "be", "been", "being", "'s")
    private val perfectAuxiliaries = setOf("have", "has", "had", "'s", "'d")
    private val modals = setOf("can", "could", "may", "might", "must", "shall", "should", "will", "would", "'d")
    private val perfectModals = setOf("could", "may", "might", "must", "should", "would", "'d")
    private val adverbs = setOf("not", "already", "just", "never", "ever", "still", "probably", "possibly", "really", "always", "also", "even")
    private val infinitiveVerbs = setOf("want", "need", "hope", "plan", "decide", "try", "agree", "promise", "expect", "learn")
    private val comparatives = setOf("better", "worse", "bigger", "smaller", "older", "younger", "faster", "slower", "easier", "harder", "longer", "shorter", "higher", "lower", "stronger", "weaker", "closer", "further", "farther", "earlier", "later", "happier")

    // An irregular past form (went, saw, took) is NOT automatically a past participle.
    private val irregularParticiples = setOf(
        "been", "beaten", "become", "begun", "bitten", "blown", "broken", "brought", "built", "burnt", "bought", "caught", "chosen", "come", "cost",
        "cut", "dealt", "done", "drawn", "dreamt", "driven", "drunk", "eaten", "fallen", "fed", "felt", "fought", "found", "flown", "forgotten",
        "forgiven", "frozen", "got", "gotten", "given", "gone", "grown", "had", "hung", "heard", "held", "hidden", "hit",
        "hurt", "kept", "known", "laid", "led", "left", "lent", "let", "lain", "lit", "lost", "made", "meant", "met",
        "paid", "put", "quit", "read", "ridden", "risen", "run", "said", "seen", "sold", "sent", "set", "shaken", "shown",
        "shot", "shut", "sung", "sat", "slept", "smelt", "sought", "spoken", "spelt", "spent", "spread", "stood", "stolen", "stuck", "struck", "sworn",
        "swum", "taken", "taught", "told", "torn", "thought", "thrown", "understood", "woken", "worn", "won", "written",
    )
    private val irregularPast = setOf(
        "was", "were", "became", "began", "broke", "brought", "built", "bought", "caught", "chose", "came", "cost",
        "cut", "did", "drew", "drove", "drank", "ate", "fell", "felt", "found", "flew", "forgot", "forgave", "froze",
        "got", "gave", "went", "grew", "had", "heard", "held", "hid", "hit", "hurt", "kept", "knew", "laid", "led",
        "left", "lent", "let", "lay", "lost", "made", "meant", "met", "paid", "put", "read", "rode", "rose", "ran",
        "said", "saw", "sold", "sent", "set", "shook", "showed", "shot", "shut", "sang", "sat", "slept", "spoke",
        "spent", "stood", "stole", "stuck", "struck", "swore", "swam", "took", "taught", "told", "thought", "threw",
        "understood", "woke", "wore", "won", "wrote",
    )
    private val intransitive = setOf("go", "come", "arrive", "happen", "exist", "sleep", "fall", "rise", "die", "seem", "be", "become")
    private val contractions = buildMap {
        put("can't", listOf("can", "not"))
        put("cannot", listOf("can", "not"))
        put("won't", listOf("will", "not"))
        put("shan't", listOf("shall", "not"))
        listOf("is", "are", "was", "were", "has", "have", "had", "do", "does", "did", "could", "should", "would", "must", "might", "need").forEach {
            put("${it}n't", listOf(it, "not"))
        }
        put("let's", listOf("let", "us"))
        put("i'm", listOf("i", "am"))
        listOf("you", "we", "they").forEach { put("$it're", listOf(it, "are")) }
        listOf("i", "you", "we", "they").forEach { put("$it've", listOf(it, "have")) }
        listOf("he", "she", "it", "that", "there", "what", "who").forEach { put("$it's", listOf(it, "'s")) }
        listOf("i", "you", "he", "she", "it", "we", "they", "that", "there", "who").forEach {
            put("$it'll", listOf(it, "will"))
            put("$it'd", listOf(it, "'d"))
        }
        listOf("could", "should", "would", "might", "must").forEach { put("$it've", listOf(it, "have")) }
    }

    fun analyze(text: String, truncated: Boolean = false): StudyExplanation {
        val inputClipped = text.length > MaxCharacters
        var input = text.take(MaxCharacters)
        if (inputClipped && input.lastOrNull()?.isHighSurrogate() == true && text[MaxCharacters].isLowSurrogate()) {
            input = input.dropLast(1)
        }
        // Do not turn the cut-off half of an English word into a dictionary entry.
        if (inputClipped && input.lastOrNull()?.isLetter() == true && text[MaxCharacters].isLetter()) {
            input = input.replace(Regex("[A-Za-z’']+$"), "")
        }
        val source = cleanSubtitle(input).trim()
        val tokens = tokenize(source).flatMap { token ->
            (contractions[token.word] ?: listOf(token.word)).map { token.copy(word = it) }
        }
        if (tokens.isEmpty()) return StudyExplanation(source, emptyList(), emptyList(), emptyList(), truncated || inputClipped)

        val covered = BooleanArray(tokens.size)
        val vocabularyHits = mutableListOf<VocabularyHit>()
        val phraseHits = mutableListOf<PhraseHit>()
        // Index candidates by their first word so the bound does not multiply by the whole glossary.
        for (index in tokens.indices) {
            for (phrase in phrasesByFirstWord[tokens[index].word].orEmpty()) {
                val end = index + phrase.words.size
                if (end > tokens.size) continue
                if (phrase.words.indices.any { tokens[index + it].word != phrase.words[it] }) continue
                if (!connected(source, tokens, index, end - 1)) continue
                if (phrase.entry.lemma == "used to" && !isHabitualUsedTo(source, tokens, index)) continue
                phraseHits += PhraseHit(index, phrase)
            }
        }
        // Reserve longest phrases globally before words; finally restore their order in the passage.
        for ((index, phrase) in phraseHits.sortedWith(compareByDescending<PhraseHit> { it.phrase.words.size }.thenBy { it.index })) {
            val end = index + phrase.words.size
            if ((index until end).any { covered[it] }) continue
            (index until end).forEach { covered[it] = true }
            vocabularyHits += VocabularyHit(tokens[index].start, phrase.entry)
        }
        val unknown = linkedSetOf<String>()
        tokens.forEachIndexed { index, token ->
            if (covered[index]) return@forEachIndexed
            val entry = words[token.word]
            if (entry != null) {
                if (token.word !in functionWords && normalize(entry.lemma) !in functionWords) {
                    vocabularyHits += VocabularyHit(token.start, entry)
                }
            } else if (token.word !in functionWords && token.word !in contractions) {
                unknown += token.word
            }
        }
        val vocabulary = vocabularyHits.sortedBy { it.position }.distinctBy { normalize(it.entry.lemma) }
            .map { StudyVocabulary(it.entry.lemma, it.entry.meaning, it.entry.partOfSpeech) }
        val grammar = grammar(source, tokens)
        return StudyExplanation(
            sourceText = source,
            vocabulary = vocabulary.take(MaxVocabulary),
            grammar = grammar.take(MaxGrammar),
            unknownWords = unknown.take(MaxUnknown),
            truncated = truncated || inputClipped || vocabulary.size > MaxVocabulary || grammar.size > MaxGrammar || unknown.size > MaxUnknown,
        )
    }

    private fun normalize(value: String) = value.replace('’', '\'').lowercase(Locale.ROOT)
    private fun tokenize(value: String): List<Token> = wordPattern.findAll(value).map {
        Token(normalize(it.value), it.range.first, it.range.last + 1)
    }.toList()

    private fun cleanSubtitle(value: String): String {
        val withoutEffects = value
            .replace(Regex("\\{\\\\[^{}\\r\\n]*}"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(?:b|i|u|s|font|span|ruby|rt|c|v|lang)(?:[ .][^<>]*)?>"), "")
            .replace(Regex("<\\d{2}:\\d{2}(?::\\d{2})?\\.\\d{3}>"), "")
            .replace(Regex("(?i)\\[(?:music|applause|laughter|laughing|sighs?|gasps?|door closes|phone rings)\\]"), " ")
        return Regex("&(?:amp|lt|gt|quot|apos|nbsp|#\\d{1,7}|#x[0-9a-fA-F]{1,6});").replace(withoutEffects) { match ->
            when (val entity = match.value) {
                "&amp;" -> "&"
                "&lt;" -> "<"
                "&gt;" -> ">"
                "&quot;" -> "\""
                "&apos;" -> "'"
                "&nbsp;" -> " "
                else -> {
                    val body = entity.substring(2, entity.length - 1)
                    val number = if (body.startsWith("x")) body.drop(1).toIntOrNull(16) else body.toIntOrNull()
                    if (number != null && Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF && number >= 32) {
                        String(Character.toChars(number))
                    } else " "
                }
            }
        }
    }

    private fun connected(source: String, tokens: List<Token>, start: Int, end: Int): Boolean =
        (start until end).all { index ->
            val left = tokens[index]
            val right = tokens[index + 1]
            left.start == right.start || spaceGap.matches(source.substring(left.end, right.start))
        }

    private fun isVerb(word: String): Boolean = words[word]?.partOfSpeech?.contains("동사") == true
    private fun isBase(word: String): Boolean = isVerb(word) && normalize(words.getValue(word).lemma) == word
    private fun isAdjective(word: String): Boolean = words[word]?.partOfSpeech?.contains("형용사") == true
    private fun isNoun(word: String): Boolean = words[word]?.partOfSpeech?.contains("명사") == true
    private fun isIng(word: String): Boolean = isVerb(word) && word.endsWith("ing") && !isBase(word)
    private fun isParticiple(word: String): Boolean = isVerb(word) &&
        (word in irregularParticiples || word.endsWith("ed") && !isBase(word))
    private fun isPassiveParticiple(word: String): Boolean = isParticiple(word) && words[word]?.lemma !in intransitive
    private fun isFinite(word: String): Boolean = isVerb(word) &&
        (isBase(word) || word in irregularPast || word.endsWith("ed") || word.endsWith("s"))

    private fun isHabitualUsedTo(source: String, tokens: List<Token>, index: Int): Boolean {
        if (!isBase(tokens.getOrNull(index + 2)?.word.orEmpty())) return false
        // "be/get used to" means familiarity; even a following noun such as "work" can look like a verb.
        return (maxOf(0, index - 3) until index).none { previous ->
            connected(source, tokens, previous, index) &&
                (tokens[previous].word in beForms || words[tokens[previous].word]?.lemma in setOf("get", "become", "grow"))
        }
    }

    private fun grammar(source: String, tokens: List<Token>): List<StudyGrammar> {
        val hits = mutableListOf<GrammarHit>()
        fun word(index: Int) = tokens.getOrNull(index)?.word.orEmpty()
        fun add(start: Int, end: Int, priority: Int, id: String, title: String, explanation: String) {
            if (end < tokens.size && connected(source, tokens, start, end)) {
                hits += GrammarHit(start, end, priority, id, title, explanation)
            }
        }
        fun afterAdverbs(start: Int): Int {
            var next = start
            repeat(2) { if (word(next) in adverbs) next++ }
            return next
        }
        for (i in tokens.indices) {
            val current = word(i)
            val next = afterAdverbs(i + 1)
            if (current in perfectModals && word(next) == "have") {
                val participle = afterAdverbs(next + 1)
                if (isParticiple(word(participle))) add(i, participle, 0, "modal-perfect", "조동사 + have + 과거분사",
                    "과거 일에 대한 추측·가능성·아쉬움 등을 나타내는 형태입니다. should는 아쉬움이나 기대, could·might는 가능성, must는 강한 추측으로 쓰일 수 있으며 정확한 뜻은 문맥에 따라 달라집니다.")
            }
            if (current in perfectAuxiliaries && isParticiple(word(next))) {
                when {
                    word(next) == "been" && isIng(word(next + 1)) -> add(i, next + 1, 1, "perfect-continuous", "완료진행형",
                        "have/has/had + been + -ing는 기준 시점까지 이어진 동작이나 그 흔적을 나타냅니다. had라면 과거의 다른 시점보다 앞선 지속을 말합니다.")
                    word(next) == "been" && isPassiveParticiple(word(next + 1)) -> add(i, next + 1, 1, "perfect-passive", "완료 수동태",
                        "have/has/had + been + 과거분사는 어떤 일을 받은 결과나 경험을 기준 시점과 연결하는 형태입니다. 구체적인 시점과 의미는 문맥으로 확인해야 합니다.")
                    current == "'s" && word(next) != "been" -> add(i, next, 2, "ambiguous-s", "'s + 과거분사: 축약형 확인",
                        "'s는 has 또는 is의 축약일 수 있습니다. has라면 완료형, is라면 수동태나 상태 표현일 수 있으므로 이 짧은 형태만으로는 하나로 확정할 수 없습니다.")
                    current == "'d" && isBase(word(next)) -> add(i, next, 2, "ambiguous-d", "'d + 동사: 축약형 확인",
                        "'d는 had 또는 would의 축약일 수 있습니다. 뒤의 동사가 원형과 과거분사 형태가 같으므로 완료형인지 조동사 표현인지는 문맥을 확인해야 합니다.")
                    else -> add(i, next, 2, "perfect", "have/has/had + 과거분사",
                        "완료형에서 have/has는 과거의 경험·결과 등을 현재와 연결하고, had는 과거의 기준 시점보다 앞선 일을 나타냅니다. 경험·완료·지속 중 어느 뜻인지는 문맥에 따라 달라집니다.")
                }
            }
            if (current in beForms) {
                when {
                    word(next) == "going" && word(next + 1) == "to" -> {
                        if (isBase(word(next + 2))) add(i, next + 2, 0, "going-to", "be going to + 동사원형",
                            "계획·의도 또는 현재의 근거를 바탕으로 한 예측을 나타낼 때 쓰는 형태입니다. 어느 쪽인지는 앞뒤 문맥으로 판단합니다.")
                    }
                    isIng(word(next)) -> add(i, next, 4, "progressive", "be + -ing 진행형",
                        "be동사 + -ing는 기준 시점에 진행 중인 동작 등을 나타냅니다. 현재·과거의 진행이나 예정된 일을 말하는지는 be동사의 형태와 문맥에 따라 달라집니다.")
                    current != "'s" && isPassiveParticiple(word(next)) -> add(i, next, 4, "passive", "be + 과거분사: 수동·상태",
                        "주어가 어떤 일을 받는 수동태 형태입니다. 분사가 형용사처럼 쓰이면 동작보다 그 결과나 상태를 나타낼 수도 있습니다.")
                }
                if (word(i + 1) == "not" && isAdjective(word(i + 2))) add(i, i + 2, 5, "be-negative", "be동사 부정문",
                    "be동사 뒤의 not은 뒤에 오는 성질이나 상태를 부정합니다. not only처럼 다른 표현의 일부로 쓰이는 경우와 구별합니다.")
                if (word(i + 1) in subjects && isAdjective(word(i + 2)) && questionStart(source, tokens, i)) add(i, i + 2, 5, "be-question", "be동사 의문문 어순",
                    "be동사가 주어 앞에 놓인 의문문 어순입니다. 뒤의 형용사는 주어의 성질이나 상태를 설명합니다.")
            }
            if (current == "used" && word(i + 1) == "to" && isHabitualUsedTo(source, tokens, i)) {
                add(i, i + 2, 2, "used-to", "used to + 동사원형", "과거의 습관이나 상태를 말하며 현재와 달라졌음을 암시하는 표현입니다. be used to(~에 익숙하다)와는 다른 형태입니다.")
            }
            if (current in setOf("have", "has", "had") && word(i + 1) == "to" && isBase(word(i + 2))) add(i, i + 2, 2, "have-to", "have to + 동사원형",
                "의무나 필요를 나타내는 표현입니다. had to는 과거의 필요를 말하며, don't have to처럼 부정되면 보통 '할 필요가 없다'는 뜻입니다.")
            if (current in modals && isBase(word(next))) add(i, next, 7, "modal", "조동사 + 동사원형",
                "조동사 뒤에는 동사원형이 옵니다. 가능성·능력·허락·의무·의지 등의 의미가 있으며 조동사와 문맥에 따라 해석이 달라집니다. 'd는 여기서 would의 축약으로 읽을 수 있습니다.")
            if (current == "too" && isAdjective(word(i + 1)) && word(i + 2) == "to" && isBase(word(i + 3))) add(i, i + 3, 1, "too-to", "too + 형용사 + to부정사",
                "어떤 행동을 하기에는 정도가 지나치다는 뜻으로 자주 쓰입니다. 보통 그 행동이 어렵거나 불가능하다는 의미가 됩니다.")
            if (isAdjective(current) && word(i + 1) == "enough" && word(i + 2) == "to" && isBase(word(i + 3))) add(i, i + 3, 1, "enough-to", "형용사 + enough + to부정사",
                "어떤 행동을 하기에 충분한 정도라는 뜻입니다. enough가 형용사 뒤에 놓인다는 점을 확인하세요.")
            if (current == "as" && isAdjective(word(i + 1)) && word(i + 2) == "as") add(i, i + 2, 2, "as-as", "as + 형용사 + as 동등 비교",
                "두 대상의 성질이나 정도가 같다고 비교하는 형태입니다. 앞에 not이 있으면 보통 '그만큼 ~하지 않다'는 뜻이 됩니다.")
            if (current in setOf("more", "less") && isAdjective(word(i + 1)) && word(i + 2) == "than") add(i, i + 2, 2, "more-than", "more/less + 형용사 + than 비교",
                "than 뒤의 대상과 비교하여 성질이나 정도가 더 크거나 작음을 나타냅니다.")
            if (current in comparatives && word(i + 1) == "than") add(i, i + 1, 2, "comparative", "비교급 + than",
                "비교급 뒤의 than은 비교 대상을 이끕니다. better·worse처럼 원래 형용사와 모양이 다른 비교급도 있습니다.")
            if (current in setOf("do", "does", "did") && word(i + 1) == "not" && isBase(word(i + 2))) add(i, i + 2, 3, "do-negative", "do/does/did not + 동사원형",
                "일반동사를 부정할 때 do/does/did 뒤에 not을 놓고 동사원형을 씁니다. 시제와 주어에 따른 변화는 do/does/did가 담당합니다.")
            if (current in setOf("do", "does", "did") && word(i + 1) in subjects && isBase(word(i + 2)) && questionStart(source, tokens, i)) add(i, i + 2, 3, "do-question", "do/does/did 의문문 어순",
                "do/does/did + 주어 + 동사원형의 의문문 어순입니다. 의문사가 있으면 그 뒤에 이 어순이 이어질 수 있습니다.")
            if (words[current]?.lemma in infinitiveVerbs && word(i + 1) == "to" && isBase(word(i + 2))) add(i, i + 2, 5, "verb-infinitive", "동사 뒤의 to부정사",
                "이 동사 뒤에서 to + 동사원형은 원하거나 계획하거나 시도하는 행동 등의 내용을 나타낼 수 있습니다. 정확한 관계는 앞 동사의 뜻과 문맥으로 확인합니다.")
            if (current == "let" && word(i + 1) in objects && isBase(word(i + 2))) add(i, i + 2, 2, "let", "let + 목적어 + 동사원형",
                "누군가가 어떤 행동을 하도록 허용하는 형태입니다. let's(let us)는 함께 무엇을 하자는 제안으로 자주 쓰입니다.")
            if (current in setOf("had", "'d") && word(i + 1) == "better" && isBase(word(i + 2))) add(i, i + 2, 1, "had-better", "had better + 동사원형",
                "현재나 앞으로의 행동에 대해 '~하는 것이 좋겠다'고 권하는 표현입니다. 하지 않았을 때의 좋지 않은 결과를 암시할 수 있으며 had가 있어도 과거 시제로만 해석하지 않습니다.")
            if (current == "not" && word(i + 1) == "only") {
                val but = (i + 3..minOf(i + 10, tokens.lastIndex - 1)).firstOrNull { word(it) == "but" && word(it + 1) == "also" }
                if (but != null) add(i, but + 1, 1, "not-only", "not only ... but also 병렬 표현",
                    "'~뿐 아니라 ...도'라는 뜻으로 두 내용을 함께 강조합니다. 여기의 not only는 문장 전체를 단순히 부정하는 not과 다릅니다.")
            }
            if (current in setOf("who", "which", "that") && i > 0 && isNoun(word(i - 1))) {
                val end = if (word(i + 1) in subjects && isFinite(word(i + 2))) i + 2 else if (isFinite(word(i + 1))) i + 1 else -1
                if (end >= 0 && connected(source, tokens, i, end) && relativeGap.matches(source.substring(tokens[i - 1].end, tokens[i].start))) {
                    hits += GrammarHit(i - 1, end, 6, "relative", "명사 뒤의 관계절 후보",
                        "who/which/that 이하가 앞의 명사를 설명하는 관계절일 수 있습니다. 짧은 규칙 분석이므로 전체 문맥에서 문장 구조를 확인하세요.")
                }
            }
        }
        // Prefer complete constructions to their overlapping component rules, then preserve reading order.
        val selected = mutableListOf<GrammarHit>()
        hits.sortedWith(compareBy<GrammarHit> { it.priority }.thenByDescending { it.end - it.start }.thenBy { it.start }).forEach { hit ->
            if (selected.none { it.start <= hit.end && hit.start <= it.end }) selected += hit
        }
        return selected.sortedBy { it.start }.distinctBy { it.id }.map { hit ->
            StudyGrammar(hit.title, hit.explanation, source.substring(tokens[hit.start].start, tokens[hit.end].end))
        }
    }

    private fun questionStart(source: String, tokens: List<Token>, index: Int): Boolean {
        if (index == 0) return true
        val previous = tokens[index - 1]
        if (previous.word in setOf("what", "when", "where", "why", "how")) return true
        if (previous.end > tokens[index].start) return false
        return sentenceBoundary.containsMatchIn(source.substring(previous.end, tokens[index].start))
    }
}
