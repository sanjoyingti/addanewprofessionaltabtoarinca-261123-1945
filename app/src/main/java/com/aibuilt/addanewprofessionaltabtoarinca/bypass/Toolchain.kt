=== ABOUT: Sets up the little Linux toolkit (Java, apktool, JADX, signer, apk-mitm) once, skipping anything already there ===
```kotlin
package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
    fun report(): String = buildString {
        if (out.isNotBlank()) append(out.trim()).append('\n')
        if (err.isNotBlank()) append(err.trim()).append('\n')
        append("exit ").append(code)
    }
}

object Shell {
    fun run(cmd: List<String>, cwd: File? = null, timeoutMs: Long = 240_000L): ShellResult {
        return try {
            val pb = ProcessBuilder(cmd)
            if (cwd != null) pb.directory(cwd)
            pb.environment()["PATH"] = PATH
            pb.environment()["HOME"] = pb.environment()["HOME"] ?: "/data/local/tmp"
            val process = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
            val tErr = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
            tOut.start(); tErr.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                tOut.join(800); tErr.join(800)
                return ShellResult(-1, out.toString(), err.toString() + "\ntimed out after ${timeoutMs}ms")
            }
            tOut.join(1500); tErr.join(1500)
            ShellResult(process.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            ShellResult(-2, "", e.message ?: "could not start process")
        }
    }

    const val PATH = "/usr/local/bin:/usr/bin:/bin:/system/bin:/system/xbin:/data/local/tmp"
}

data class ToolStep(val id: String, val label: String, val probe: List<String>, val install: List<List<String>>)

data class ToolchainResult(
    val ok: Boolean,
    val message: String,
    val installed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<String> = emptyList()
)

class Toolchain(private val context: Context, private val log: suspend (String) -> Unit) {

    private val linuxRoot: File get() = File(context.filesDir, "linux")
    private val rootfs: File get() = File(linuxRoot, "rootfs")
    private val proot: File get() = File(linuxRoot, "proot")

    val java: String get() = bin("java")
    val keytool: String get() = bin("keytool")
    val apktool: String get() = bin("apktool")
    val jadx: String get() = bin("jadx")
    val apkEditor: String get() = bin("APKEditor")
    val apkSigner: String get() = bin("uber-apk-signer")
    val apkMitm: String get() = bin("apk-mitm")

    fun linuxAvailable(): Boolean =
        rootfs.isDirectory && (File(rootfs, "bin/bash").isFile || File(rootfs, "bin/sh").isFile)

    private fun bin(name: String): String {
        val candidates = listOf(
            File(rootfs, "usr/local/bin/$name"),
            File(rootfs, "usr/bin/$name"),
            File(rootfs, "bin/$name")
        )
        return candidates.firstOrNull { it.isFile }?.absolutePath ?: name
    }

    fun shellCommand(command: String): List<String> =
        if (linuxAvailable() && proot.isFile) {
            listOf(
                proot.absolutePath, "-r", rootfs.absolutePath, "-0", "-w", "/root",
                "/usr/bin/env", "PATH=${Shell.PATH}", "HOME=/root",
                "/bin/bash", "-lc", command
            )
        } else {
            listOf("sh", "-c", command)
        }

    suspend fun sh(command: String, cwd: File? = null, timeoutMs: Long = 240_000L): ShellResult =
        withContext(Dispatchers.IO) {
            log("\$ $command")
            val result = Shell.run(shellCommand(command), cwd, timeoutMs)
            log(result.report())
            result
        }

    private fun steps(): List<ToolStep> = listOf(
        ToolStep(
            "java", "Java 21",
            probe = listOf("java -version"),
            install = listOf(
                listOf("apt-get update -y"),
                listOf("DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk-headless")
            )
        ),
        ToolStep(
            "apktool", "apktool",
            probe = listOf("command -v apktool", "apktool --version"),
            install = listOf(
                listOf("apt-get install -y aapt zip unzip || true"),
                listOf("curl -fsSL https://raw.githubusercontent.com/iBotPeaches/Apktool/master/scripts/linux/apktool -o /usr/local/bin/apktool && chmod +x /usr/local/bin/apktool"),
                listOf("curl -fsSL https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar -o /opt/apktool.jar || curl -fsSL https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar -o /opt/apktool.jar")
            )
        ),
        ToolStep(
            "jadx", "JADX",
            probe = listOf("command -v jadx", "jadx --version"),
            install = listOf(
                listOf("curl -fsSL https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip -o /tmp/jadx.zip && mkdir -p /opt/jadx && unzip -o /tmp/jadx.zip -d /opt/jadx && ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx")
            )
        ),
        ToolStep(
            "apkeditor", "APKEditor",
            probe = listOf("command -v APKEditor"),
            install = listOf(
                listOf("curl -fsSL https://github.com/REAndroid/APKEditor/releases/download/V1.4.4/APKEditor-1.4.4.jar -o /opt/APKEditor.jar && printf '#!/bin/sh\\nexec java -jar /opt/APKEditor.jar \"\\$@\"\\n' > /usr/local/bin/APKEditor && chmod +x /usr/local/bin/APKEditor")
            )
        ),
        ToolStep(
            "uber-apk-signer", "uber-apk-signer",
            probe = listOf("command -v uber-apk-signer"),
            install = listOf(
                listOf("curl -fsSL https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar -o /opt/uber-apk-signer.jar && printf '#!/bin/sh\\nexec java -jar /opt/uber-apk-signer.jar \"\\$@\"\\n' > /usr/local/bin/uber-apk-signer && chmod +x /usr/local/bin/uber-apk-signer")
            )
        ),
        ToolStep(
            "apk-mitm", "apk-mitm (npm)",
            probe = listOf("command -v apk-mitm"),
            install = listOf(
                listOf("command -v node >/dev/null || (apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm)"),
                listOf("npm install -g apk-mitm")
            )
        )
    )

    suspend fun installAll(project: BypassProject, onProgress: (Float, String) -> Unit): ToolchainResult {
        if (!linuxAvailable()) {
            val message = "No Linux under the app's files folder (linux/rootfs + proot). Install Arin's Linux box first — without it apktool, JADX and the signer cannot run."
            log(message)
            return ToolchainResult(false, message)
        }
        val installed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        val all = steps()
        all.forEachIndexed { index, step ->
            onProgress(index.toFloat() / all.size.coerceAtLeast(1), "Checking ${step.label}")
            val present = step.probe.any { probe ->
                val r = sh(probe, timeoutMs = 20_000)
                r.ok
            }
            if (present) {
                skipped.add(step.label)
                log("${step.label}: already present, skipped")
                onProgress((index + 1).toFloat() / all.size, "${step.label} present")
                return@forEachIndexed
            }
            onProgress(index.toFloat() / all.size, "Installing ${step.label}")
            var stepOk = false
            for (command in step.install) {
                val r = sh(command)
                if (r.ok) { stepOk = true; break }
            }
            if (stepOk) installed.add(step.label) else {
                failed.add(step.label)
                log("${step.label}: install failed — continuing")
            }
            onProgress((index + 1).toFloat() / all.size, "Done with ${step.label}")
        }
        val keystore = ensureKeystore(project)
        if (!keystore.first) failed.add("keystore")
        val ok = installed.isNotEmpty() || skipped.isNotEmpty()
        val message = buildString {
            append(if (ok) "Toolchain ready. " else "Toolchain could not be prepared. ")
            if (installed.isNotEmpty()) append("Installed: ${installed.joinToString(", ")}. ")
            if (skipped.isNotEmpty()) append("Skipped: ${skipped.joinToString(", ")}. ")
            if (failed.isNotEmpty()) append("Failed: ${failed.joinToString(", ")}.")
        }
        log(message)
        return ToolchainResult(ok, message, installed, skipped, failed)
    }

    suspend fun ensureKeystore(project: BypassProject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val store = File(context.filesDir, "bypass/projects/${project.id}/bypass.keystore")
        if (store.isFile) return@withContext true to "keystore already present"
        store.parentFile?.mkdirs()
        val password = PASSWORD
        val command = "\"${keytool}\" -genkeypair -v -keystore \"${store.absolutePath}\" -storepass $password -keypass $password " +
            "-alias bypass -keyalg RSA -keysize 2048 -validity 10000 " +
            "-dname \"CN=Arin Bypass, OU=Bypass, O=Arin, L=Local, S=Local, C=ZZ\""
        val result = sh(command)
        if (store.isFile) true to "keystore generated"
        else false to "keystore failed: ${result.err.ifBlank { result.out }}"
    }

    companion object {
        const val PASSWORD = "arinbypass"
        const val ALIAS = "bypass"
    }
}
