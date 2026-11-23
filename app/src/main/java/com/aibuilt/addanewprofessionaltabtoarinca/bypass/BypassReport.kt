package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the per-project Markdown report. Pure text, so it can be shared or stored. */
object BypassReport {

    private fun stamp(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))

    fun render(
        project: BypassProject,
        findings: List<Finding>,
        ledger: List<LedgerEntry>,
        diff: String,
        endpoints: List<TextHit>
    ): String {
        val sb = StringBuilder()
        sb.append("# APK Bypass report — ${project.name}\n\n")
        sb.append("- Project id: `${project.id}`\n")
        sb.append("- Source: `${project.sourceName}`\n")
        sb.append("- Source SHA-256: `${project.sourceHash}`\n")
        sb.append("- Package: `${project.sourcePackage ?: "unknown"}`")
        if (project.clonePackage != null) sb.append(" → `${project.clonePackage}`")
        sb.append('\n')
        sb.append("- Stage: ${project.stage}\n")
        sb.append("- Rounds run: ${project.roundsDone} / ${project.maxRounds}\n")
        sb.append("- Usable screen reached: ${if (project.usableScreenReached) "yes" else "no"}\n")
        sb.append("- Offline proof round: ${if (project.offlineProofDone) "passed" else "not passed"}\n\n")

        sb.append("## What was found\n\n")
        if (findings.isEmpty()) sb.append("_No scan was run yet._\n\n")
        else {
            findings.forEach { finding ->
                sb.append("- **${finding.confidence}** · ${finding.title} — `${finding.file}` — ${finding.detail}\n")
            }
            sb.append('\n')
        }

        sb.append("## Endpoints seen\n\n")
        if (endpoints.isEmpty()) sb.append("_None recorded._\n\n")
        else {
            endpoints.distinctBy { it.text }.take(120).forEach { sb.append("- `${it.text}` (`${it.relPath}`)\n") }
            sb.append('\n')
        }

        sb.append("## What was changed\n\n")
        if (ledger.isEmpty()) sb.append("_Nothing was written yet._\n\n")
        else {
            ledger.forEach { entry ->
                sb.append("- ${stamp(entry.time)} · `${entry.relPath}` · ${entry.summary} (patch `${entry.patchId}`)\n")
            }
            sb.append('\n')
        }
        sb.append("Targets applied: ")
        sb.append(if (project.appliedTargets.isEmpty()) "_none_" else project.appliedTargets.joinToString { it.title })
        sb.append("\n\n")

        sb.append("## What is still broken\n\n")
        val broken = project.rounds.filter { it.outcome != WatchResult.OK }
        if (broken.isEmpty()) sb.append("_No failing round recorded._\n\n")
        else {
            broken.forEach { round ->
                sb.append("- Round ${round.round} (${round.confidence}): ${round.outcome} — ${round.detail}\n")
            }
            sb.append('\n')
        }

        sb.append("## Known dead ends that apply here\n\n")
        if (project.splitWarning != null) sb.append("- Split APKs: ${project.splitWarning}\n")
        BypassWarnings.deadEnds.forEach { sb.append("- $it\n") }
        sb.append('\n')

        sb.append("## Test notes\n\n")
        if (project.notes.isEmpty()) sb.append("_None._\n\n")
        else project.notes.forEach { note -> sb.append("- ${note.text} (repeated ${note.taps} time(s))\n") }
        sb.append('\n')

        sb.append("## Full diff\n\n")
        sb.append("```diff\n")
        sb.append(diff.take(400_000).ifBlank { "no changes" })
        sb.append("\n```\n")
        return sb.toString()
    }

    fun write(target: File, text: String): File {
        target.parentFile?.mkdirs()
        target.writeText(text)
        return target
    }
}
