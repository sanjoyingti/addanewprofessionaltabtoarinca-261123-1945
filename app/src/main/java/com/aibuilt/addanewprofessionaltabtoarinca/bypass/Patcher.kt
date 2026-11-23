=== ABOUT: The patch engine: finds methods, previews every change as a diff, writes through the patch diary, repairs the manifest, renames the package, checks the smali and adds the rescue hook ===
```kotlin
package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    val touchedPaths: List<String> get() = changed.map { it.relPath } + deletes
    val hasChanges: Boolean get() = changed.isNotEmpty() || deletes.isNotEmpty()
    val unified: String get() = changed.joinToString("\n") { it.unified() }
}

private const val TEXT_LIMIT = 260_000L

class Patcher(
    private val toolchain: Toolchain,
    private val log: suspend (String) -> Unit
) {

    // ---------------------------------------------------------------- reading

    fun workFile(root: File, rel: String): File = File(root, rel)

    fun read(root: File, rel: String): String =
        runCatching { workFile(root, rel).readText() }.getOrDefault("")

    fun isTextFile(rel: String): Boolean {
        val ext = rel.substringAfterLast('.', "").lowercase()
        return ext in setOf("smali", "xml", "json", "txt", "js", "html", "properties", "dart", "java", "yml", "yaml", "gradle", "so.txt")
    }

    fun tree(root: File, filter: String = "", limit: Int = 3000): List<String> {
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

    // ---------------------------------------------------------------- writing

    suspend fun writeFile(root: File, rel: String, content: String, ledger: Ledger, patchId: String, summary: String) =
        withContext(Dispatchers.IO) {
            ledger.record(root, rel, patchId, summary)
            val target = workFile(root, rel)
            target.parentFile?.mkdirs()
            target.writeText(content)
            log("patched $rel")
        }

    suspend fun apply(root: File, preview: PatchPreview, ledger: Ledger) = withContext(Dispatchers.IO) {
        preview.changed.forEach { diff ->
            ledger.record(root, diff.relPath, preview.patchId, preview.summary)
            val target = workFile(root, diff.relPath)
            target.parentFile?.mkdirs()
            target.writeText(diff.after)
        }
        preview.deletes.forEach { rel ->
            val target = workFile(root, rel)
            if (target.isFile) {
                ledger.record(root, rel, preview.patchId, preview.summary)
                target.delete()
            }
        }
        log("applied ${preview.summary} (${preview.touchedPaths.size} file(s))")
    }

    // ---------------------------------------------------------------- searching

    fun findUsages(root: File, needle: String, limit: Int = 400): List<TextHit> {
        val hits = ArrayList<TextHit>()
        if (!root.isDirectory) return hits
        for (file in root.walkTopDown().filter { it.isFile && it.extension in setOf("smali", "xml", "json") }) {
            if (hits.size >= limit) break
            val rel = file.relativeTo(root).invariantSeparatorsPath
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (hits.size < limit && line.contains(needle)) {
                            hits.add(TextHit(rel, index + 1, line.trim()))
                        }
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
            val ext = file.extension.lowercase()
            if (ext !in setOf("smali", "xml", "json", "js", "properties", "dart", "txt")) continue
            val rel = file.relativeTo(root).invariantSeparatorsPath
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (hits.size < limit) {
                            urlRegex.findAll(line).forEach { m ->
                                if (hits.size < limit) hits.add(TextHit(rel, index + 1, m.value))
                            }
                        }
                    }
                }
            }
        }
        return hits
    }

    // ---------------------------------------------------------------- method surgery

    private data class MethodBlock(val headerIndex: Int, val endIndex: Int, val signature: String, val body: List<String>)

    private fun parseMethods(lines: List<String>): List<MethodBlock> {
        val out = ArrayList<MethodBlock>()
        var start = -1
        var signature = ""
        for ((index, raw) in lines.withIndex()) {
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

    private fun registerDirective(body: List<String>): Pair<Int, Int>? {
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
        val methods = parseMethods(lines).sortedByDescending { it.headerIndex }
        for (method in methods) {
            val name = method.signature.substringAfterLast(' ').substringBefore('(')
            val descriptor = method.signature.substringAfter('(', "")
            if (!namePattern.containsMatchIn(name)) continue
            if (!descriptor.endsWith(")Z")) continue
            val directive = registerDirective(method.body) ?: continue
            val fixedBody = ArrayList<String>()
            var writtenDirective = false
            var index = 0
            // keep annotations/param lines above the register directive
            while (index < directive.first) {
                fixedBody.add(method.body[index]); index++
            }
            if (!writtenDirective) {
                val decl = method.body[directive.first].trim()
                val keyword = decl.substringBefore(' ')
                fixedBody.add("    $keyword ${maxOf(directive.second, 1)}")
                writtenDirective = true
            }
            fixedBody.add(if (value) "    const/4 v0, 0x1" else "    const/4 v0, 0x0")
            fixedBody.add("    return v0")
            val rebuilt = ArrayList<String>()
            rebuilt.addAll(lines.subList(method.headerIndex + 1, method.headerIndex + 1))
            lines.subList(method.headerIndex + 1, method.endIndex).clear()
            lines.addAll(method.headerIndex + 1, fixedBody)
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
            PatchPreview("stub", "Stub $rel", notes = listOf("$rel is empty or missing."))
        } else {
            val lines = before.lines().toMutableList()
            val methods = parseMethods(lines).sortedByDescending { it.headerIndex }
            for (method in methods) {
                val descriptor = method.signature.substringAfter('(', "")
                val directive = registerDirective(method.body) ?: continue
                val body = ArrayList<String>()
                var index = 0
                while (index < directive.first) { body.add(method.body[index]); index++ }
                val decl = method.body[directive.first].trim()
                val keyword = decl.substringBefore(' ')
                val needed = if (descriptor.endsWith(")V")) 0 else 1
                body.add("    $keyword ${maxOf(directive.second, needed)}")
                when {
                    descriptor.endsWith(")Z") -> { body.add("    const/4 v0, 0x0"); body.add("    return v0") }
                    descriptor.endsWith(")V") -> body.add("    return-void")
                    descriptor.endsWith(")Ljava/lang/String;") -> { body.add("    const-string v0, \"\""); body.add("    return-object v0") }
                    descriptor.endsWith(")I") || descriptor.endsWith(")J") -> { body.add("    const/4 v0, 0x0"); body.add("    return v0") }
                    descriptor.endsWith(")Ljava/util/List;") -> { body.add("    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;"); body.add("    move-result-object v0"); body.add("    return-object v0") }
                    else -> body.add("    return-void")
                }
                lines.subList(method.headerIndex + 1, method.endIndex).clear()
                lines.addAll(method.headerIndex + 1, body)
            }
            val after = lines.joinToString("\n")
            PatchPreview("stub-${rel.hashCode()}", "Stub every method in $rel",
                listOf(FileDiff(rel, before, after)),
                notes = listOf("${methods.size} method(s) neutralised."))
        }
    }

    fun deleteFilePreview(root: File, rel: String): PatchPreview {
        val before = read(root, rel)
        return PatchPreview("del-${rel.hashCode()}", "Delete $rel",
            listOf(FileDiff(rel, before, "")), deletes = listOf(rel))
    }

    // ---------------------------------------------------------------- bulk replace

    suspend fun bulkReplace(root: File, needle: String, replacement: String, includeDex: Boolean): PatchPreview =
        withContext(Dispatchers.IO) {
            val patchId = "bulk-${System.currentTimeMillis() % 100000}"
            val diffs = ArrayList<FileDiff>()
            for (file in root.walkTopDown().filter { it.isFile }) {
                if (!file.isTextFileLike()) continue
                if (file.length() > TEXT_LIMIT) continue
                val before = runCatching { file.readText() }.getOrNull() ?: continue
                if (!before.contains(needle)) continue
                diffs.add(FileDiff(file.relativeTo(root).invariantSeparatorsPath, before, before.replace(needle, replacement)))
            }
            val notes = ArrayList<String>()
            if (includeDex) {
                val dexes = root.walkTopDown().filter { it.isFile && it.extension == "dex" }.toList()
                var skipped = 0
                for (dex in dexes) {
                    val bytes = runCatching { dex.readBytes() }.getOrNull() ?: continue
                    if (needle.toByteArray().size != replacement.toByteArray().size) { skipped++; continue }
                    val patched = replaceSameLength(bytes, needle.toByteArray(), replacement.toByteArray())
                    if (patched != null) {
                        diffs.add(FileDiff(dex.relativeTo(root).invariantSeparatorsPath,
                            "binary dex ${bytes.size} bytes", "binary dex ${patched.size} bytes"))
                        dex.writeBytes(patched)
                    }
                }
                if (skipped > 0) notes.add("$skipped dex file(s) skipped: inside a dex the replacement must be the same byte length.")
            }
            if (diffs.isEmpty()) notes.add("No hit for \"$needle\".")
            PatchPreview(patchId, "Replace \"$needle\" with \"$replacement\"", diffs, notes = notes)
        }

    private fun File.isTextFileLike(): Boolean =
        extension.lowercase() in setOf("smali", "xml", "json", "txt", "js", "html", "properties", "dart", "yml", "yaml")

    private fun replaceSameLength(source: ByteArray, needle: ByteArray, replacement: ByteArray): ByteArray? {
        if (needle.isEmpty() || needle.size != replacement.size) return null
        val out = source.copyOf()
        var found = false
        var i = 0
        while (i <= out.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (out[i + j] != needle[j]) { match = false; break }
            }
            if (match) {
                found = true
                for (j in needle.indices) out[i + j] = replacement[j]
                i += needle.size
            } else i++
        }
        return if (found) out else null
    }

    // ---------------------------------------------------------------- manifest repairs

    private fun manifestFile(root: File): File = File(root, "AndroidManifest.xml")

    private fun ensureApplicationAttr(manifest: String, attr: String, value: String): String {
        if (manifest.contains(attr)) return manifest
        return manifest.replaceFirst(Regex("<application\\b"), "<application $attr=\"$value\"")
    }

    suspend fun manifestRepairs(root: File): PatchPreview = withContext(Dispatchers.IO) {
        val file = manifestFile(root)
        val before = runCatching { file.readText() }.getOrNull()
        if (before == null) {
            PatchPreview("manifest", "Manifest repairs", notes = listOf("AndroidManifest.xml not found — decode first."))
        } else {
            var after = before
            val notes = ArrayList<String>()
            // 1. exported on every component carrying an intent-filter
            val componentBlocks = Regex("<(activity|service|receiver|provider)\\b[^>]*>\\s*(<intent-filter>)").findAll(after).toList()
            for (block in componentBlocks) {
                val tag = block.value
                if (tag.contains("android:exported")) continue
                val patched = tag.replaceFirst(Regex("(<(activity|service|receiver|provider)\\b)"), "$1 android:exported=\"true\"")
                after = after.replaceFirst(tag, patched)
                notes.add("set exported on a component with an intent-filter")
            }
            // 2. targetSdk bump
            val targetMatch = Regex("android:targetSdkVersion=\"(\\d+)\"").find(after)
            if (targetMatch != null && (targetMatch.groupValues[1].toIntOrNull() ?: 0) < 33) {
                after = after.replace(targetMatch.value, "android:targetSdkVersion=\"33\"")
                notes.add("bumped targetSdk ${targetMatch.groupValues[1]} -> 33 so the clone installs")
            }
            // 3. cleartext when the app talks http
            val httpHit = endpointInventory(root, 40).any { it.text.startsWith("http://") }
            if (httpHit && !after.contains("usesCleartextTraffic")) {
                after = ensureApplicationAttr(after, "android:usesCleartextTraffic", "true")
                notes.add("enabled cleartext traffic because endpoints use http://")
            }
            if (after == before) notes.add("Manifest already clean — nothing to repair.")
            PatchPreview("manifest-repairs", "Manifest repairs", listOf(FileDiff("AndroidManifest.xml", before, after)), notes = notes)
        }
    }

    suspend fun ensureCleartextOrConfig(root: File, configName: String): PatchPreview = withContext(Dispatchers.IO) {
        val file = manifestFile(root)
        val before = runCatching { file.readText() }.getOrNull()
        if (before == null) return@withContext PatchPreview("nsc", "Point manifest at the network security config",
            notes = listOf("AndroidManifest.xml not found."))
        val after = ensureApplicationAttr(before, "android:networkSecurityConfig", "@xml/$configName")
        PatchPreview("nsc-manifest", "Point manifest at @xml/$configName",
            listOf(FileDiff("AndroidManifest.xml", before, after)))
    }

    // ---------------------------------------------------------------- webview bridge

    suspend fun patchWebViewBridge(root: File): PatchPreview = withContext(Dispatchers.IO) {
        val diffs = ArrayList<FileDiff>()
        val notes = ArrayList<String>()
        val call = "Landroid/webkit/WebView;->addJavascriptInterface"
        for (hit in findUsages(root, call, 40)) {
            val before = read(root, hit.relPath)
            if (before.isBlank()) continue
            // drop the "if-not-equals zero" guard that gates the bridge, by widening shouldOverrideUrlLoading to true
            diffs.add(FileDiff(hit.relPath, before, before))
        }
        val bridgeDiffs = ArrayList<FileDiff>()
        for (file in smaliFiles(root)) {
            if (file.length() > TEXT_LIMIT) continue
            val before = runCatching { file.readText() }.getOrNull() ?: continue
            if (!before.contains("->shouldOverrideUrlLoading(")) continue
            val forced = forceBooleanInFile(before, Regex("shouldOverrideUrlLoading"), true)
            if (forced != before) bridgeDiffs.add(FileDiff(file.relativeTo(root).invariantSeparatorsPath, before, forced))
        }
        if (diffs.isEmpty() && bridgeDiffs.isEmpty()) notes.add("No WebView JS bridge found.")
        if (diffs.isNotEmpty()) notes.add("${diffs.size} addJavascriptInterface call site(s) seen.")
        PatchPreview("webview-bridge", "Let every URL load inside the hybrid WebView", bridgeDiffs, notes = notes)
    }

    // ---------------------------------------------------------------- package rename

    suspend fun renamePackage(root: File, oldPackage: String, newPackage: String): PatchPreview =
        withContext(Dispatchers.IO) {
            val diffs = ArrayList<FileDiff>()
            val notes = ArrayList<String>()
            val oldPath = oldPackage.replace('.', '/')
            val newPath = newPackage.replace('.', '/')

            val manifest = manifestFile(root)
            val manifestBefore = runCatching { manifest.readText() }.getOrNull()
            if (manifestBefore != null) {
                var after = manifestBefore.replace("package=\"$oldPackage\"", "package=\"$newPackage\"")
                if (after == manifestBefore) notes.add("Manifest package attribute did not change — check the original package name.")
                // keep versionName / versionCode untouched on purpose
                diffs.add(FileDiff("AndroidManifest.xml", manifestBefore, after))
            } else notes.add("AndroidManifest.xml missing.")

            // move the smali package folders
            root.walkTopDown().filter { it.isDirectory && it.name.startsWith("smali") }.forEach { smaliRoot ->
                val from = File(smaliRoot, oldPath)
                if (from.isDirectory) {
                    from.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.relativeTo(smaliRoot).invariantSeparatorsPath
                        val newRel = rel.replaceFirst(oldPath, newPath)
                        diffs.add(FileDiff(rel, rel, newRel))
                    }
                    notes.add("package folder moves from $oldPath to $newPath (${smaliRoot.name})")
                }
            }

            // rewrite references everywhere in text
            for (file in root.walkTopDown().filter { it.isFile && it.extension in setOf("smali", "xml", "json", "txt") }) {
                if (file.length() > TEXT_LIMIT) continue
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (rel == "AndroidManifest.xml") continue
                if (diffs.any { it.relPath == rel && it.before != it.after }) continue
                val before = runCatching { file.readText() }.getOrNull() ?: continue
                if (!before.contains(oldPackage) && !before.contains(oldPath)) continue
                val after = before.replace(oldPath, newPath).replace(oldPackage, newPackage)
                diffs.add(FileDiff(rel, before, after))
            }
            notes.add("versionName / versionCode kept as they were.")
            PatchPreview("rename-${newPackage.hashCode()}", "Rename package $oldPackage -> $newPackage", diffs, notes = notes)
        }

    // ---------------------------------------------------------------- frida rescue

    suspend fun injectFridaGadget(root: File, abi: String): PatchPreview = withContext(Dispatchers.IO) {
        val arch = if (abi == "arm64") "arm64" else "arm"
        val version = "16.5.9"
        val url = "https://github.com/frida/frida/releases/download/$version/frida-gadget-$version-android-$arch.so.xz"
        val libDir = File(root, "lib/$arch-v${if (arch == "arm64") "8a" else "7a"}")
        libDir.mkdirs()
        val target = File(libDir, "libgadget.so")
        val result = toolchain.sh("curl -fsSL \"$url\" -o /tmp/gadget.xz && unxz -f /tmp/gadget.xz && cp /tmp/gadget \"${target.absolutePath}\"")
        val notes = ArrayList<String>()
        val diffs = ArrayList<FileDiff>()
        if (!target.isFile) {
            notes.add("Gadget download failed: ${result.err.ifBlank { result.out }}")
            return@withContext PatchPreview("frida", "Inject Frida gadget", notes = notes)
        }
        notes.add("Gadget staged at ${target.relativeTo(root).invariantSeparatorsPath}")
        val manifestFile = manifestFile(root)
        val manifest = runCatching { manifestFile.readText() }.getOrNull()
        if (manifest != null && !manifest.contains("extractNativeLibs")) {
            diffs.add(FileDiff("AndroidManifest.xml", manifest,
                ensureApplicationAttr(manifest, "android:extractNativeLibs", "true")))
        }
        val appClass = manifest?.let { Regex("android:name=\"([A-Za-z0-9_.$]+)\"").find(it)?.groupValues?.get(1) }
        val appSmali = appClass?.replace('.', '/')?.let { "smali/$it.smali" }
        if (appSmali != null && File(root, appSmali).isFile) {
            val before = read(root, appSmali)
            if (!before.contains("loadLibrary")) {
                val after = before.replaceFirst(Regex("(?m)^(\\.method .*attachBaseContext\\(.*\\n)"),
                    "$1    const-string v0, \"gadget\"\n    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n")
                if (after != before) diffs.add(FileDiff(appSmali, before, after))
                else notes.add("Could not place the loadLibrary call — add System.loadLibrary(\"gadget\") by hand.")
            }
        } else notes.add("Application class not found in smali — gadget stays unloaded.")
        PatchPreview("frida-gadget", "Inject Frida gadget ($arch)", diffs, notes = notes)
    }

    // ---------------------------------------------------------------- validation

    fun validate(root: File, maxFiles: Int = 4000): List<SmaliIssue> {
        val issues = ArrayList<SmaliIssue>()
        val registerUse = Regex("\\bv(\\d+)\\b")
        val invokeParam = Regex("invoke-[a-z/]+ \\{[^}]*}, \\S+\\(([^)]*)\\)")
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
                if (registers > 0) {
                    registerUse.findAll(line).forEach { m ->
                        if (!line.startsWith(".registers") && !line.startsWith(".locals")) {
                            val used = m.groupValues[1].toInt()
                            if (used >= registers) {
                                issues.add(SmaliIssue(rel, index + 1, "register v$used used but only $registers declared"))
                            }
                        }
                    }
                }
                if (line.startsWith("invoke-")) {
                    invokeParam.find(line)?.let { m ->
                        val params = m.groupValues[1]
                        val expected = params.chunked(1).count { true } - countObjects(params)
                        val actual = Regex("\\{[^}]*}").find(line)?.value?.count { it == 'v' || it == 'p' } ?: 0
                        if (expected != actual) {
                            issues.add(SmaliIssue(rel, index + 1, "invoke passes $actual register(s) but the signature wants $expected"))
                        }
                    }
                }
            }
            if (depth != 0) issues.add(SmaliIssue(rel, lines.size, "unbalanced .method/.end method"))
            if (issues.size > 400) break
        }
        return issues
    }

    private fun countObjects(params: String): Int {
        if (params.isEmpty()) return 0
        var count = 0
        var i = 0
        while (i < params.length) {
            when (params[i]) {
                'L' -> { count++; while (i < params.length && params[i] != ';') i++ }
                '[' -> { val start = i; while (params[i] == '[') i++; if (params[i] == 'L') while (params[i] != ';') i++; count += 0; i -= 1
                    if (start >= 0) { } }
                else -> count++
            }
            i++
        }
        return count
    }

    // ---------------------------------------------------------------- gadget download helper

    fun downloadTo(url: String, target: File): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        true
    }.getOrDefault(false)
}
