package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class InstalledApp(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val versionName: String?
)

data class IntakeResult(
    val ok: Boolean,
    val baseApk: File? = null,
    val splits: List<String> = emptyList(),
    val hash: String = "",
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val warning: String? = null,
    val message: String = ""
)

class ApkIntake(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    fun installedApps(): List<InstalledApp> {
        val flags = PackageManager.GET_META_DATA
        return pm.getInstalledApplications(flags)
            .asSequence()
            .filter { it.sourceDir != null && File(it.sourceDir).isFile }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                val vName = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(info.packageName, 0).versionName
                }.getOrNull()
                InstalledApp(label, info.packageName, info.sourceDir, vName)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Splits that exist for an installed app but are not the base APK. */
    fun installedSplits(packageName: String): List<String> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        }
        info.applicationInfo?.splitSourceDirs?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun fromInstalled(app: InstalledApp, pristineDir: File): IntakeResult =
        withContext(Dispatchers.IO) {
            val src = File(app.apkPath)
            if (!src.isFile) return@withContext IntakeResult(false, message = "APK file not readable: ${app.apkPath}")
            pristineDir.mkdirs()
            val splits = installedSplits(app.packageName).filter { File(it).isFile }
            copyTo(src, File(pristineDir, "base.apk"))
            splits.forEach { copyTo(File(it), File(pristineDir, File(it).name)) }
            val warning = if (splits.isNotEmpty()) {
                "This install has ${splits.size} split APKs. Only base.apk is patched — missing splits can break the app once renamed."
            } else null
            IntakeResult(
                ok = true,
                baseApk = File(pristineDir, "base.apk"),
                splits = splits.map { File(it).name },
                hash = hash(File(pristineDir, "base.apk")),
                packageName = app.packageName,
                versionName = app.versionName,
                versionCode = null,
                warning = warning,
                message = "Imported installed app ${app.label}"
            )
        }

    suspend fun fromUri(uri: Uri, pristineDir: File, displayName: String?): IntakeResult =
        withContext(Dispatchers.IO) {
            pristineDir.mkdirs()
            val name = (displayName ?: "picked.apk").lowercase()
            if (!(name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".zip"))) {
                return@withContext IntakeResult(false, message = "Pick a .apk, .apks or .xapk file.")
            }
            val incoming = File(pristineDir, "incoming_${name.replace(Regex("[^a-z0-9.]"), "_")}")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    incoming.outputStream().use { out -> input.copyTo(out) }
                } ?: return@withContext IntakeResult(false, message = "Could not open the picked file.")
            }.onFailure { return@withContext IntakeResult(false, message = "Read failed: ${it.message}") }

            if (name.endsWith(".apk")) {
                val base = File(pristineDir, "base.apk")
                incoming.renameTo(base)
                val pkg = packageNameOf(base)
                val (vName, vCode) = versionInfo(base)
                return@withContext IntakeResult(
                    ok = true,
                    baseApk = base,
                    hash = hash(base),
                    packageName = pkg,
                    versionName = vName,
                    versionCode = vCode,
                    message = "Imported base.apk"
                )
            }

            var splits = emptyList<String>()
            var baseEntry: String? = null
            runCatching {
                ZipFile(incoming).use { zip ->
                    val names = zip.entries().toList().map { it.name }.filter { it.endsWith(".apk") }
                    baseEntry = names.firstOrNull { it.substringAfterLast('/') == "base.apk" }
                        ?: names.firstOrNull { it.contains("base") }
                    splits = names.filter { it != baseEntry }
                    baseEntry?.let { entry ->
                        val out = File(pristineDir, "base.apk")
                        zip.getInputStream(zip.getEntry(entry)).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        splits.forEach { splitName ->
                            val target = File(pristineDir, splitName.substringAfterLast('/'))
                            runCatching {
                                zip.getInputStream(zip.getEntry(splitName)).use { input ->
                                    target.outputStream().use { input.copyTo(it) }
                                }
                            }
                        }
                    }
                }
            }.onFailure { return@withContext IntakeResult(false, message = "Bundle unzip failed: ${it.message}") }

            val base = File(pristineDir, "base.apk")
            if (!base.isFile) return@withContext IntakeResult(false, message = "Bundle had no base.apk inside.")
            incoming.delete()
            val (vName, vCode) = versionInfo(base)
            val warning = if (splits.isNotEmpty()) {
                "The bundle carries ${splits.size} split(s) — kept for reference, but only base.apk gets patched. Missing splits can break the clone."
            } else null
            IntakeResult(
                ok = true,
                baseApk = base,
                splits = splits.map { it.substringAfterLast('/') },
                hash = hash(base),
                packageName = packageNameOf(base),
                versionName = vName,
                versionCode = vCode,
                warning = warning,
                message = "Imported base.apk from bundle"
            )
        }

    /** Raw unzip of the pristine base APK — the fallback decode and the restore path. */
    suspend fun extractTree(baseApk: File, workDir: File): Int =
        withContext(Dispatchers.IO) {
            workDir.mkdirs()
            var count = 0
            runCatching {
                ZipFile(baseApk).use { zip ->
                    zip.entries().toList().forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        val target = File(workDir, entry.name)
                        target.parentFile?.mkdirs()
                        runCatching {
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                            count++
                        }
                    }
                }
            }
            count
        }

    fun packageNameOf(apk: File): String? = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA)?.packageName
    }.getOrNull()

    fun versionInfo(apk: File): Pair<String?, Long?> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, 0)
        }
        val name = info?.versionName
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info?.longVersionCode else null
        name to code
    }.getOrDefault(null to null)

    private fun copyTo(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
    }

    fun hash(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
