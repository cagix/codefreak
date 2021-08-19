package org.codefreak.codefreak.service.evaluation

import java.io.InputStream
import org.codefreak.codefreak.entity.Feedback
import org.springframework.stereotype.Component

/**
 * This format is only used by Visual Studio but there are a lot of tools that can generate VS compatible reports
 * e.g. ESLint or cpplint.
 *
 * The format follows the pattern (taken from MS documentation):
 *
 *   {filename(line# \[, column#\]) | toolname} : \[ any text \] {*error* | *warning*} code+number:localizable string \[ any text \]
 *   Where:
 *   - {a | b} is a choice of either a or b.
 *   - \[item\] is an optional string or parameter.
 *   - *item* represents a literal.
 *
 *   Examples:
 *   - C:\sourcefile.cpp(134) : error C2143: syntax error : missing ';' before '}'
 *   - LINK : fatal error LNK1104: cannot open file 'somelib.lib'
 *
 * There is also a very similar (if not the same) format generated by dotnet-format CLI (not its json reports)
 * and other Roslyn analyzers that may add a project-name in braces at the end of the file. This parser will truncate
 * this additional information.
 *
 * @see <a href="https://docs.microsoft.com/en-us/cpp/build/formatting-the-output-of-a-custom-build-step-or-build-event?redirectedfrom=MSDN&view=msvc-160">MS Documentation</a>
 * @see <a href="https://github.com/cpplint/cpplint/blob/6b1d29874dc5d7c3c9201b70e760b3eb9468a60d/cpplint.py#L1708">cpplint "vs7" formatting</a>
 * @see <a href="https://eslint.org/docs/user-guide/formatters/#visualstudio">ESLint "visualstudio" formatting</a>
 * @see <a href="https://github.com/dotnet/format/blob/624afb5283ab901837c7ed7af734586d21a8f8ed/src/Logging/MSBuildIssueFormatter.cs#L8">dotnet-format report formatting</a>
 */
@Component
class VisualStudioReportFormatParser : EvaluationReportFormatParser {
  override val id = "visual-studio"
  override val title = "Visual Studio"

  /**
   * This regex will try to match a line against the format specified above.
   * It stores the following groups:
   * - filename (might not be present if toolname is specified)
   * - line (might not be present if toolname is specified)
   * - column (might not be present if toolname is specified)
   * - toolname (might not be present if filename is specified)
   * - severity (warning or error)
   * - code
   * - message
   *
   * It ignores possible strings before severity like "fatal" and everything after the last double colon is taken
   * as "message" ignoring "localizable string".
   */
  private val vscodeMessageRegex = Regex("^(?:(?<filename>[^)]+)\\((?<line>\\d+)(?:,(?<column>\\d+))?\\)|(?<toolname>[^:]+))\\s*:\\s*(.*? )?(?<severity>error|warning)\\s+(?<code>[^:]+):\\s*(?<message>.+?)(?: \\[[^\\[]+\\])?\$")

  override fun parse(exitCode: Int, stdout: String, fileContent: InputStream): List<Feedback> {
    return fileContent.bufferedReader().lineSequence().mapNotNull {
      vscodeMessageRegex.matchEntire(it)
    }.mapNotNull {
      matchResultToFeedback(it)
    }.toList()
  }

  private fun matchResultToFeedback(matchResult: MatchResult): Feedback? {
    val message = matchResult.groups["message"]?.value?.trim() ?: return null
    return Feedback(summary = message).apply {
      status = Feedback.Status.FAILED
      severity = if (matchResult.groups["severity"]?.value?.trim() == "error") Feedback.Severity.MAJOR else Feedback.Severity.MINOR
      group = matchResult.groups["code"]?.value?.trim()
      fileContext = createFileContext(matchResult)
    }
  }

  private fun createFileContext(matchResult: MatchResult): Feedback.FileContext? {
    val fileName = matchResult.groups["filename"]?.value ?: return null
    return Feedback.FileContext(
        path = fileName,
        lineStart = matchResult.groups["line"]?.value?.run { toIntOrNull() },
        columnStart = matchResult.groups["column"]?.value?.run { toIntOrNull() }
    )
  }
}
