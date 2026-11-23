=== ABOUT: Fixed the code fences: the patch engine that previews every change as a diff, repairs the manifest, renames the clone, checks the smali and can inject the Frida rescue hook ===
```kotlin
package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class FileDiff(val relPath: String, val before: String, val after: String) {
    val changed: Boolean get() = before != after
    fun unified(): String = Diff.unified(relPath, before, after)
}

data class TextHit(val relPath: String, val line: Int, val text: String)

data class SmaliIssue(val relPath: String, val line: Int, val message: String)

data class PatchPreview(
    val patchId: String,
    val summary: String,
    val diffs: List<FileDiff> = emptyList(),
    val deletes: List<String> = emptyList(),
    val notes: List<String> = emptyList()
) {
    val changed: List<FileDiff> get() = diffs.filter { it.changed }
    val hasChanges: Boolean get() = changed.isNotEmpty() || deletes.isNotEmpty()
    val unified: String get() = changed.joinToString("\n") { it.unified() }
}

private const val TEXT_LIMIT = 260_000L

class Patcher(private val toolchain: Toolchain, private val log: suspend (String) -> Unit) {

    fun read(root: File, rel: String): String =
        runCatching { File(root, rel).readText() }.getOrDefault("")

    fun tree(root: File, filter: String = "", limit: Int = 2000): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
            .take(limit)
            .toList()
    }

    fun smaliFiles(root: File): List<File> =
        if (!root.isDirectory) emptyList()
        else root.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()

    suspend fun apply(root: File, preview: PatchPreview, ledger: Ledger) = withContext(Dispatchers.IO) {
        preview.changed.forEach { diff ->
            ledger.record(root, diff.relPath, preview.patchId, preview.summary)
            val target = File(root, diff.relPath)
            target.parentFile?.mkdirs()
            target.writeText(diff.after)
        }
        preview.deletes.forEach { rel ->
            val target = File(root, rel)
            if (target.isFile) {
                ledger.record(root, rel, preview.patchId, preview.summary)
                target.delete()
            }
        }
        log("applied ${preview.summary} (${preview.changed.size} changed, ${preview.deletes.size} removed)")
    }

    fun findUsages(root: File, needle: String, limit: Int = 400): List<TextHit> {
        val hits = ArrayList<TextHit>()
        if (!root.isDirectory) return hits
        for (file in root.walkTopDown().filter { it.isFile && it.extension in setOf("smali", "xml", "json") }) {
            if (hits.size >= limit) break
            val rel = file.relativeTo(root).invariantSeparatorsPath
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (hits.size < limit && line.contains(needle)) hits.add(TextHit(rel, index + 1, line.trim()))
                    }
                }
            }
        }
        return hits
    }

    fun endpointInventory(root: File, limit: Int = 800): List<TextHit> {
        val hits = ArrayList<TextHit>()
        if (!root.isDirectory) return hits
        val urlRegex = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")
        for (file in root.walkTopDown().filter { it.isFile }) {
            if (hits.size >= limit) break
            if (file.extension.lowercase() !in setOf("smali", "xml", "json", "js", "properties", "dart", "txt")) continue
            val rel = file.relativeTo(root).invariantSeparatorsPath
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        urlRegex.findAll(line).forEach { match ->
                            if (hits.size < limit) hits.add(TextHit(rel, index + 1, match.value))
                        }
                    }
                }
            }
        }
        return hits
    }

    private data class MethodBlock(val start: Int, val end: Int, val signature: String, val body: List<String>)

    private fun parseMethods(lines: List<String>): List<MethodBlock> {
        val out = ArrayList<MethodBlock>()
        var start = -1
        var signature = ""
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.startsWith(".method ")) {
                start = index
                signature = line
            } else if (line == ".end method" && start >= 0) {
                out.add(MethodBlock(start, index, signature, lines.subList(start + 1, index)))
                start = -1
            }
        }
        return out
    }

    private fun registerLine(body: List<String>): Pair<Int, Int>? {
        body.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.startsWith(".registers ") || line.startsWith(".locals ")) {
                val count = line.substringAfter(' ').trim().toIntOrNull() ?: return null
                return index to count
            }
        }
        return null
    }

    private fun forceBooleanInFile(content: String, namePattern: Regex, value: Boolean): String {
        val lines = content.lines().toMutableList()
        parseMethods(lines).sortedByDescending { it.start }.forEach { method ->
            val name = method.signature.substringAfterLast(' ').substringBefore('(')
            if (!namePattern.containsMatchIn(name)) return@forEach
            if (!method.signature.substringAfter('(', "").endsWith(")Z")) return@forEach
            val reg = registerLine(method.body) ?: return@forEach
            val body = ArrayList<String>()
            for (index in 0 until reg.first) body.add(method.body[index])
            val keyword = method.body[reg.first].trim().substringBefore(' ')
            body.add("    $keyword ${maxOf(reg.second, 1)}")
            body.add(if (value) "    const/4 v0, 0x1" else "    const/4 v0, 0x0")
            body.add("    return v0")
            lines.subList(method.start + 1, method.end).clear()
            lines.addAll(method.start + 1, body)
        }
        return lines.joinToString("\n")
    }

    suspend fun forceBooleanMethods(root: File, namePattern: Regex, value: Boolean, summary: String): PatchPreview =
        withContext(Dispatchers.IO) {
            val patchId = "force-${if (value) "true" else "false"}-${System.currentTimeMillis() % 100000}"
            val diffs = ArrayList<FileDiff>()
            for (file in smaliFiles(root)) {
                if (file.length() > TEXT_LIMIT) continue
                val before = runCatching { file.readText() }.getOrNull() ?: continue
                val after = forceBooleanInFile(before, namePattern, value)
                if (after != before) diffs.add(FileDiff(file.relativeTo(root).invariantSeparatorsPath, before, after))
            }
            val notes = if (diffs.isEmpty()) listOf("No matching boolean method was found for ${namePattern.pattern}.") else emptyList()
            PatchPreview(patchId, summary, diffs, notes = notes)
        }

    suspend fun stubClass(root: File, rel: String): PatchPreview = withContext(Dispatchers.IO) {
        val before = read(root, rel)
        if (before.isBlank()) {
            PatchPreview("stub-${rel.hashCode()}", "Stub $rel", notes = listOf("$rel is empty or missing."))
        } else {
            val lines = before.lines().toMutableList()
            val methods = parseMethods(lines).sortedByDescending { it.start }
            methods.forEach { method ->
                val descriptor = method.signature.substringAfter('(', "")
                val reg = registerLine(method.body) ?: return@forEach
                val body = ArrayList<String>()
                for (index in 0 until reg.first) body.add(method.body[index])
                val keyword = method.body[reg.first].trim().substringBefore(' ')
                val needed = if (descriptor.endsWith(")V")) 0 else 1
                body.add("    $keyword ${maxOf(reg.second, needed)}")
                when {
                    descriptor.endsWith(")Z") -> { body.add("    const/4 v0, 0x0"); body.add("    return v0") }
                    descriptor.endsWith(")V") -> body.add("    return-void")
                    descriptor.endsWith(")Ljava/lang/String;") -> { body.add("    const-string v0, \"\""); body.add("    return-object v0") }
                    descriptor.endsWith(")I") || descriptor.endsWith(")J") -> { body.add("    const/4 v0, 0x0"); body.add("    return v0") }
                    descriptor.endsWith(")Ljava/util/List;") -> {
                        body.add("    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;")
                        body.add("    move-result-object v0")
                        body.add("    return-object v0")
                    }
                    else -> body.add("    return-void")
                }
                lines.subList(method.start + 1, method.end).clear()
                lines.addAll(method.start + 1, body)
            }
            PatchPreview(
                "stub-${rel.hashCode()}", "Stub every method in $rel",
                listOf(FileDiff(rel, before, lines.joinToString("\n"))),
                notes = listOf("${methods.size} method(s) neutralised.")
            )
        }
    }

    fun deleteFilePreview(root: File, rel: String): PatchPreview =
        PatchPreview("del-${rel.hashCode()}", "Delete $rel", listOf(FileDiff(rel, read(root, rel), "")), deletes = listOf(rel))

    suspend fun bulkReplace(root: File, needle: String, replacement: String, includeDex: Boolean): PatchPreview =
        withContext(Dispatchers.IO) {
            val patchId = "bulk-${System.currentTimeMillis() % 100000}"
            val diffs = ArrayList<FileDiff>()
            val notes = ArrayList<String>()
            if (needle.isBlank()) return@withContext PatchPreview(patchId, "Replace nothing", notes = listOf("Give a needle first."))
            for (file in root.walkTopDown().filter { it.isFile }) {
                if (file.extension.lowercase() !in setOf("smali", "xml", "json", "txt", "js", "html", "properties", "dart")) continue
                if (file.length() > TEXT_LIMIT) continue
                val before = runCatching { file.readText() }.getOrNull() ?: continue
                if (!before.contains(needle)) continue
                diffs.add(FileDiff(file.relativeTo(root).invariantSeparatorsPath, before, before.replace(needle, replacement)))
            }
            if (includeDex) {
                var skipped = 0
                for (dex in root.walkTopDown().filter { it.isFile && it.extension == "dex" }) {
                    val bytes = runCatching { dex.readBytes() }.getOrNull() ?: continue
                    if (needle.toByteArray().size != replacement.toByteArray().size) { skipped++; continue }
                    val patched = replaceSameLength(bytes, needle.toByteArray(), replacement.toByteArray())
                    if (patched != null) {
                        dex.writeBytes(patched)
                        diffs.add(FileDiff(dex.relativeTo(root).invariantSeparatorsPath, "dex ${bytes.size} bytes", "dex ${patched.size} bytes (rewritten)"))
                    }
                }
                if (skipped > 0) notes.add("$skipped dex file(s) skipped: inside a dex the replacement must be the same byte length.")
            }
            if (diffs.isEmpty()) notes.add("No hit for \"$needle\".")
            PatchPreview(patchId, "Replace \"$needle\" with \"$replacement\"", diffs, notes = notes)
        }

    private fun replaceSameLength(source: ByteArray, needle: ByteArray, replacement: ByteArray): ByteArray? {
        if (needle.isEmpty() || needle.size != replacement.size) return null
        val out = source.copyOf()
        var found = false
        var i = 0
        while (i <= out.size - needle.size) {
            var match = true
            for (j in needle.indices) if (out[i + j] != needle[j]) { match = false; break }
            if (match) {
                found = true
                for (j in needle.indices) out[i + j] = replacement[j]
                i += needle.size
            } else i++
        }
        return if (found) out else null
    }

    suspend fun manifestRepairs(root: File): PatchPreview = withContext(Dispatchers.IO) {
        val file = File(root, "AndroidManifest.xml")
        val before = runCatching { file.readText() }.getOrNull()
        if (before == null) {
            PatchPreview("manifest", "Manifest repairs", notes = listOf("AndroidManifest.xml not found — decode first."))
        } else {
            var after = before
            val notes = ArrayList<String>()
            Regex("<(activity|service|receiver|provider)\\b[^>]*>\\s*<intent-filter>").findAll(after).forEach { block ->
                if (!block.value.contains("android:exported")) {
                    after = after.replaceFirst(block.value,
                        block.value.replaceFirst(Regex("<(activity|service|receiver|provider)\\b"), "$1 android:exported=\"true\""))
                    notes.add("set exported on a component with an intent-filter")
                }
            }
            val target = Regex("android:targetSdkVersion=\"(\\d+)\"").find(after)
            if (target != null && (target.groupValues[1].toIntOrNull() ?: 0) < 33) {
                after = after.replace(target.value, "android:targetSdkVersion=\"33\"")
                notes.add("bumped targetSdk ${target.groupValues[1]} to 33 so the clone installs")
            }
            if (endpointInventory(root, 40).any { it.text.startsWith("http://") } && !after.contains("usesCleartextTraffic")) {
                after = after.replaceFirst(Regex("<application\\b"), "<application android:usesCleartextTraffic=\"true\"")
                notes.add("enabled cleartext traffic because endpoints use http://")
            }
            if (after == before) notes.add("Manifest already clean — nothing to repair.")
            PatchPreview("manifest-repairs", "Manifest repairs", listOf(FileDiff("AndroidManifest.xml", before, after)), notes = notes)
        }
    }

    suspend fun ensureConfigWired(root: File, configName: String): PatchPreview = withContext(Dispatchers.IO) {
        val file = File(root, "AndroidManifest.xml")
        val before = runCatching { file.readText() }.getOrNull()
            ?: return@withContext PatchPreview("nsc", "Wire the network config", notes = listOf("AndroidManifest.xml not found."))
        val after = if (before.contains("networkSecurityConfig")) before
        else before.replaceFirst(Regex("<application\\b"), "<application android:networkSecurityConfig=\"@xml/$configName\"")
        PatchPreview("nsc-manifest", "Point manifest at @xml/$configName", listOf(FileDiff("AndroidManifest.xml", before, after)))
    }

    suspend fun patchWebViewBridge(root: File): PatchPreview = withContext(Dispatchers.IO) {
        val diffs = ArrayList<FileDiff>()
        val notes = ArrayList<String>()
        for (file in smaliFiles(root)) {
            if (file.length() > TEXT_LIMIT) continue
            val before = runCatching { file.readText() }.getOrNull() ?: continue
            if (!before.contains("->shouldOverrideUrlLoading(")) continue
            val after = forceBooleanInFile(before, Regex("shouldOverrideUrlLoading"), true)
            if (after != before) diffs.add(FileDiff(file.relativeTo(root).invariantSeparatorsPath, before, after))
        }
        if (diffs.isEmpty()) notes.add("No WebView URL gate found.")
        notes.add("addJavascriptInterface call sites stay untouched; only the URL gate is widened.")
        PatchPreview("webview-bridge", "Let every URL load inside the hybrid WebView", diffs, notes = notes)
    }

    suspend fun renamePackage(root: File, oldPackage: String, newPackage: String): PatchPreview =
        withContext(Dispatchers.IO) {
            val oldPath = oldPackage.replace('.', '/')
            val newPath = newPackage.replace('.', '/')
            val diffs = ArrayList<FileDiff>()
            val deletes = ArrayList<String>()
            val notes = ArrayList<String>()
            val manifest = File(root, "AndroidManifest.xml")
            if (manifest.isFile) {
                val before = manifest.readText()
                val after = before.replace("package=\"$oldPackage\"", "package=\"$newPackage\"")
                if (after == before) notes.add("Manifest package attribute was not $oldPackage — check it by hand.")
                diffs.add(FileDiff("AndroidManifest.xml", before, after))
            } else notes.add("AndroidManifest.xml missing.")
            for (file in smaliFiles(root)) {
                if (file.length() > TEXT_LIMIT) continue
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val before = runCatching { file.readText() }.getOrNull() ?: continue
                val after = before.replace(oldPath, newPath).replace(oldPackage, newPackage)
                if (!rel.contains(oldPath)) {
                    if (after != before) diffs.add(FileDiff(rel, before, after))
                } else {
                    diffs.add(FileDiff(rel.replaceFirst(oldPath, newPath), "", after))
                    deletes.add(rel)
                }
            }
            notes.add("versionName / versionCode are kept as they were.")
            notes.add("The clone is signed with the project keystore so it installs beside the original.")
            PatchPreview("rename-${newPackage.hashCode()}", "Rename $oldPackage to $newPackage", diffs, deletes, notes)
        }

    suspend fun injectFridaGadget(root: File, abi: String): PatchPreview = withContext(Dispatchers.IO) {
        val arch = if (abi == "arm64") "arm64" else "arm"
        val version = "16.5.9"
        val url = "https://github.com/frida/frida/releases/download/$version/frida-gadget-$version-android-$arch.so.xz"
        val libDir = File(root, "lib/$arch-v${if (arch == "arm64") "8a" else "7a"}")
        libDir.mkdirs()
        val target = File(libDir, "libgadget.so")
        val notes = ArrayList<String>()
        val diffs = ArrayList<FileDiff>()
        val result = toolchain.sh("curl -fsSL \"$url\" -o /tmp/gadget.xz && unxz -f /tmp/gadget.xz && cp /tmp/gadget \"${target.absolutePath}\"")
        if (!target.isFile) {
            notes.add("Gadget download failed: ${result.err.ifBlank { result.out }.take(160)}")
            return@withContext PatchPreview("frida", "Inject Frida gadget", notes = notes)
        }
        notes.add("Gadget staged at ${target.relativeTo(root).invariantSeparatorsPath}")
        val manifest = File(root, "AndroidManifest.xml")
        val manifestText = runCatching { manifest.readText() }.getOrNull()
        if (manifestText != null && !manifestText.contains("extractNativeLibs")) {
            diffs.add(FileDiff("AndroidManifest.xml", manifestText,
                manifestText.replaceFirst(Regex("<application\\b"), "<application android:extractNativeLibs=\"true\"")))
        }
        val appClass = manifestText?.let {
            Regex("android:name=\"([A-Za-z0-9_.$]+)\"").find(it)?.groupValues?.get(1)
        }
        val appSmali = appClass?.replace('.', '/')?.let { "smali/$it.smali" }
        if (appSmali != null && File(root, appSmali).isFile) {
            val before = read(root, appSmali)
            if (!before.contains("loadLibrary")) {
                val after = before.replaceFirst(Regex("(?m)^(\\.method .*attachBaseContext\\(.*\\n)"),
                    "$1    const-string v0, \"gadget\"\n    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n")
                if (after != before) diffs.add(FileDiff(appSmali, before, after))
                else notes.add("Could not place the loadLibrary call — add it by hand.")
            }
        } else notes.add("Application class not found — the gadget stays unloaded.")
        PatchPreview("frida-gadget", "Inject Frida gadget ($arch)", diffs, notes = notes)
    }

    fun validate(root: File, maxFiles: Int = 3000): List<SmaliIssue> {
        val issues = ArrayList<SmaliIssue>()
        val registerUse = Regex("\\bv(\\d+)\\b")
        val invokeWire = Regex("invoke-[a-z/]+ \\{([^}]*)\\}, \\S+\\(([^)]*)\\)")
        for (file in smaliFiles(root).take(maxFiles)) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            var depth = 0
            var registers = -1
            for ((index, raw) in lines.withIndex()) {
                val line = raw.trim()
                when {
                    line.startsWith(".method ") -> {
                        if (depth > 0) issues.add(SmaliIssue(rel, index + 1, "method opened before the previous one ended"))
                        depth++
                    }
                    line == ".end method" -> {
                        depth--
                        if (depth < 0) issues.add(SmaliIssue(rel, index + 1, "stray .end method"))
                    }
                    line.startsWith(".registers ") || line.startsWith(".locals ") ->
                        registers = line.substringAfter(' ').trim().toIntOrNull() ?: registers
                }
                if (registers > 0 && !line.startsWith(".registers") && !line.startsWith(".locals")) {
                    registerUse.findAll(line).forEach { match ->
                        if ((match.groupValues[1].toIntOrNull() ?: 0) >= registers) {
                            issues.add(SmaliIssue(rel, index + 1, "register v${match.groupValues[1]} used but only $registers declared"))
                        }
                    }
                }
                if (line.startsWith("invoke-")) {
                    val wire = invokeWire.find(line)
                    if (wire != null) {
                        val supplied = wire.groupValues[1].split(',').count { it.isNotBlank() }
                        val wanted = wire.groupValues[2].count { it == 'L' || it == ';' } / 2 + wire.groupValues[2].replace("L", "").count { it == ';' }
                        if (supplied != wanted) issues.add(SmaliIssue(rel, index + 1, "invoke passes $supplied register(s) but the signature wants $wanted"))
                    }
                }
            }
            if (depth != 0) issues.add(SmaliIssue(rel, lines.size, "unbalanced .method / .end method"))
            if (issues.size > 400) break
        }
        return issues
    }
}
