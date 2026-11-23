package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class LedgerEntry(
    val id: String,
    val time: Long,
    val relPath: String,
    val patchId: String,
    val summary: String,
    val backup: String,
    val existed: Boolean
)

/**
 * Every write goes through here: the original bytes are parked in backups/ first,
 * so a single patch, all patches, or the pristine APK can always be restored.
 */
class Ledger(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val ledgerFile = File(dir, "ledger.json")
    private val backupDir = File(dir, "backups")

    fun entries(): List<LedgerEntry> {
        if (!ledgerFile.isFile) return emptyList()
        return runCatching { json.decodeFromString<List<LedgerEntry>>(ledgerFile.readText()) }.getOrDefault(emptyList())
    }

    private fun write(entries: List<LedgerEntry>) {
        dir.mkdirs()
        ledgerFile.writeText(json.encodeToString(entries))
    }

    fun isTouched(relPath: String): Boolean = entries().any { it.relPath == relPath }

    fun patches(): List<String> = entries().map { it.patchId }.distinct().sorted()

    fun record(root: File, relPath: String, patchId: String, summary: String): LedgerEntry {
        val target = File(root, relPath)
        val backupName = "${UUID.randomUUID().toString().take(8)}_${relPath.replace('/', '_')}.orig"
        backupDir.mkdirs()
        val existed = target.isFile
        if (existed) target.copyTo(File(backupDir, backupName), overwrite = true)
        val entry = LedgerEntry(
            id = UUID.randomUUID().toString().take(8),
            time = System.currentTimeMillis(),
            relPath = relPath,
            patchId = patchId,
            summary = summary,
            backup = backupName,
            existed = existed
        )
        write(entries() + entry)
        return entry
    }

    fun diff(root: File, entryId: String): String {
        val entry = entries().firstOrNull { it.id == entryId } ?: return "no such entry"
        val backup = File(backupDir, entry.backup)
        val old = if (entry.existed && backup.isFile) backup.readText() else ""
        val now = File(root, entry.relPath).let { if (it.isFile) it.readText() else "" }
        return Diff.unified(entry.relPath, old, now)
    }

    fun fullDiff(root: File): String =
        entries().joinToString("\n") { diff(root, it.id) }

    fun revert(root: File, entryId: String): Boolean {
        val entry = entries().firstOrNull { it.id == entryId } ?: return false
        val target = File(root, entry.relPath)
        val backup = File(backupDir, entry.backup)
        if (entry.existed && backup.isFile) {
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
        } else {
            target.delete()
        }
        write(entries().filterNot { it.id == entryId })
        return true
    }

    fun revertPatch(root: File, patchId: String) {
        entries().filter { it.patchId == patchId }.forEach { revert(root, it.id) }
    }

    fun revertAll(root: File) {
        entries().toList().forEach { revert(root, it.id) }
        write(emptyList())
    }
}

object Diff {

    fun unified(path: String, old: String, new: String): String {
        val a = old.lines()
        val b = new.lines()
        val sb = StringBuilder()
        sb.append("--- a/").append(path).append('\n')
        sb.append("+++ b/").append(path).append('\n')
        if (a.size.toLong() * b.size.toLong() > 4_000_000L) {
            a.forEach { sb.append('-').append(it).append('\n') }
            b.forEach { sb.append('+').append(it).append('\n') }
            return sb.toString()
        }
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { sb.append(' ').append(a[i]).append('\n'); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { sb.append('-').append(a[i]).append('\n'); i++ }
                else -> { sb.append('+').append(b[j]).append('\n'); j++ }
            }
        }
        while (i < n) { sb.append('-').append(a[i]).append('\n'); i++ }
        while (j < m) { sb.append('+').append(b[j]).append('\n'); j++ }
        return sb.toString()
    }
}
