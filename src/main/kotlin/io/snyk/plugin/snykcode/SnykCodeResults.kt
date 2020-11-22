package io.snyk.plugin.snykcode

import ai.deepcode.javaclient.core.SuggestionForFile
import com.intellij.psi.PsiFile

class SnykCodeResults(
    private val file2suggestions: Map<PsiFile, List<SuggestionForFile>> = emptyMap()
) {
    fun suggestions(file: PsiFile): List<SuggestionForFile> = file2suggestions[file] ?: emptyList()

    val files: Set<PsiFile>
        get() = file2suggestions.keys

    val totalCount: Int
        get() {
            //TODO
            return 5555
        }
}

val SuggestionForFile.severityAsString: String
    get() = when (this.severity) {
        3 -> "high"
        2 -> "medium"
        1 -> "low"
        else -> "low"
    }
