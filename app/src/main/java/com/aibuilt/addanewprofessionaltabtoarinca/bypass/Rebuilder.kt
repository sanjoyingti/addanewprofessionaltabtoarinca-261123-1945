package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Validates the smali, packs the work tree, zipaligns and signs with the per-project keystore.
 * Nothing is written outside the project's build folder.
 */
object Rebuilder {

    private val oldSignature = Regex("^META-INF/[^/]+\\.(RSA|DSA|EC|SF|MF)$", RegexOption.IGNORE_CASE)
    private val stored = setOf("so", "png", "jpg", "jpeg", "webp", "ogg", "mp3", "mp4", "ttf", "otf")

    suspend fun run(
        context: Context,
        store: BypassStore,
        toolchain: Toolchain,
        patcher: Patcher,
        projectId: String,
        onProgress: suspend (Float, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val project = store.load(projectId) ?: return@withContext false to "Project not found."
        val root = store.workDir(projectId)
        if (!File(root, "AndroidManifest.xml").isFile) {
            return@withContext false to "Nothing decoded yet — decode the APK first."
        }
        if (!toolchain.linuxAvailable()) {
            return@withContext false to "The Linux box is missing, so zipalign and apksigner cannot run."
        }
        onProgress(0.05f, "Validating smali")
        val issues = patcher.validate(root)
        if (issues.isNotEmpty()) {
            val text = issues.take(6).joinToString("; ") { "${it.relPath}:${it.line} ${it.message}" }
            return@withContext false to "Smali validation failed (${issues.size} issue(s)): $text"
        }

        val dir = store.buildOutDir(projectId)
        val unsigned = File(dir, "unsigned.apk")
        onProgress(0.2f, "Packing the patched tree")
        val packed = runCatching { zipTree(root, unsigned) }.getOrElse {
            return@withContext false to "Packing failed: ${it.message}"
        }
        if (!packed) return@withContext false to "Nothing to pack — the work tree is empty."

        onProgress(0.4f, "Aligning")
        val aligned = File(dir, "aligned.apk")
        val align = toolchain.sh(
            "\"${toolchain.zipalign()}\" -p -f 4 \"${unsigned.absolutePath}\" \"${aligned.absolutePath}\"",
            timeoutMs = 180_000
        )
        val alignedFile = if (align.ok && aligned.isFile) aligned else unsigned
        if (!align.ok) onProgress(0.45f, "zipalign unavailable, signing the packed file")

        onProgress(0.6f, "Signing with the project key")
        val keystore = store.keystoreFile(projectId)
        if (!keystore.isFile) {
            val made = toolchain.ensureKeystore(store.projectDir(projectId))
            if (!made.first) return@withContext false to "No keystore: ${made.second}"
        }
        val signed = File(dir, "clone.apk")
        signed.delete()
        val args = "--ks \"${keystore.absolutePath}\" --ks-pass pass:${Toolchain.PASSWORD} " +
            "--key-pass pass:${Toolchain.PASSWORD} --ks-key-alias ${Toolchain.ALIAS}"
        val sign = toolchain.sh(
            "\"${toolchain.apksigner()}\" sign $args --v2-signing-enabled true --v3-signing-enabled true " +
                "--out \"${signed.absolutePath}\" \"${alignedFile.absolutePath}\"",
            timeoutMs = 240_000
        )
        if (!signed.isFile) {
            onProgress(0.7f, "apksigner failed, trying uber-apk-signer")
            val before = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            toolchain.sh(
                "\"${toolchain.apkSigner()}\" --apks \"${alignedFile.absolutePath}\" $args --out \"${dir.absolutePath}\"",
                timeoutMs = 240_000
            )
            val produced = dir.listFiles()
                ?.filter { it.name !in before && it.extension == "apk" }
                ?.maxByOrNull { it.lastModified() }
            if (produced != null) produced.copyTo(signed, overwrite = true)
        }
        if (!signed.isFile) {
            return@withContext false to "Signing failed: ${sign.err.ifBlank { sign.out }.take(300)}"
        }

        onProgress(0.9f, "Verifying the signature")
        val verify = toolchain.sh("\"${toolchain.apksigner()}\" verify \"${signed.absolutePath}\"", timeoutMs = 90_000)
        if (!verify.ok) {
            return@withContext false to "Signature verification failed: ${verify.err.ifBlank { verify.out }.take(300)}"
        }
        store.appendLog(projectId, "signed clone at ${signed.absolutePath}")
        store.save(project.copy(stage = Stage.REBUILT, rebuiltApkPath = signed.absolutePath))
        onProgress(1f, "Signed clone ready")
        true to signed.absolutePath
    }

    private fun zipTree(root: File, out: File): Boolean {
        out.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (oldSignature.matches(rel)) return@forEach
                val entry = ZipEntry(rel)
                if (rel == "resources.arsc" || file.extension.lowercase() in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.length()
                    entry.compressedSize = file.length()
                    entry.crc = crc(file)
                }
                zip.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count > 0
    }

    private fun crc(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
