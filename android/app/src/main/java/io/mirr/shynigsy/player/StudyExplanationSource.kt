package io.mirr.shynigsy.player

internal data class StudyExplanationInput(val text: String, val truncated: Boolean = false)

/** Prefer an English-looking track over a translated track containing an occasional Latin name. */
internal fun selectStudyEnglishPassage(vararg candidates: StudyExplanationInput?): StudyExplanationInput? =
    candidates.filterNotNull().mapNotNull { candidate ->
        val text = candidate.text.take(4_000)
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val letters = text.count { it.isLetter() }
        if (latin < 2) null else candidate to latin.toDouble() / letters.coerceAtLeast(1)
    }.maxByOrNull { it.second }?.first
