=== ABOUT: Keeps every project, log, traffic capture and rule pack on disk so work survives closing the app ===
```kotlin
package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BypassStore(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    val root: File = File(appContext.filesDir, "bypass")
    val projectsDir: File = File(root, "projects")
    val rulesDir: File = File(root, "rules")
    val exportsDir: File = File(root, "exports")

    private val _projects = MutableStateFlow<List<BypassProject>>(emptyList())
    val projects: StateFlow<List<BypassProject>> = _projects.asStateFlow()

    init {
        projectsDir.mkdirs()
        rulesDir.mkdirs()
        exportsDir.mkdirs()
        scope.launch { refresh() }
    }

    fun projectDir(id: String): File = File(projectsDir, id)

    /** Untouched copy of the picked APK — the restore point. */
    fun pristineDir(id: String): File = File(projectDir(id), "pristine").apply { mkdirs() }

    /** Decoded / patched working tree. */
    fun workDir(id: String): File = File(projectDir(id), "work").apply { mkdirs() }

    fun reportsDir(id: String): File = File(projectDir(id), "reports").apply { mkdirs() }

    fun screenshotsDir(id: String): File = File(projectDir(id), "shots").apply { mkdirs() }

    fun trafficDir(id: String): File = File(projectDir(id), "traffic").apply { mkdirs() }

    fun trafficFile(id: String, name: String): File = File(trafficDir(id), name)

    fun keystoreFile(id: String): File = File(projectDir(id), "bypass.keystore")

    fun buildOutDir(id: String): File = File(projectDir(id), "build").apply { mkdirs() }

    fun ledger(id: String): Ledger = Ledger(projectDir(id))

    private fun projectJson(id: String): File = File(projectDir(id), "project.json")

    private fun logFile(id: String): File = File(projectDir(id), "log.txt")

    fun logPath(id: String): String = logFile(id).absolutePath

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = ArrayList<BypassProject>()
        projectsDir.listFiles()?.forEach { dir ->
            val f = File(dir, "project.json")
            if (f.isFile) {
                runCatching { json.decodeFromString<BypassProject>(f.readText()) }.getOrNull()?.let { list.add(it) }
            }
        }
        _projects.value = list.sortedByDescending { it.updated }
    }

    suspend fun load(id: String): BypassProject? = withContext(Dispatchers.IO) {
        val f = projectJson(id)
        if (!f.isFile) null else runCatching { json.decodeFromString<BypassProject>(f.readText()) }.getOrNull()
    }

    suspend fun save(project: BypassProject) = withContext(Dispatchers.IO) {
        val dir = projectDir(project.id)
        dir.mkdirs()
        projectJson(project.id).writeText(json.encodeToString(project.copy(updated = System.currentTimeMillis())))
        refresh()
    }

    suspend fun create(
        name: String,
        sourceName: String,
        sourceHash: String,
        sourcePackage: String?,
        sourceVersionName: String?,
        sourceVersionCode: Long?,
        splitWarning: String?
    ): BypassProject = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        projectDir(id).mkdirs()
        val project = BypassProject(
            id = id,
            name = name.ifBlank { sourcePackage ?: "APK $id" },
            created = System.currentTimeMillis(),
            updated = System.currentTimeMillis(),
            sourceName = sourceName,
            sourceHash = sourceHash,
            sourcePackage = sourcePackage,
            sourceVersionName = sourceVersionName,
            sourceVersionCode = sourceVersionCode,
            splitWarning = splitWarning
        )
        projectJson(id).writeText(json.encodeToString(project))
        appendLog(id, "Project ${project.name} created from $sourceName (${sourceHash.take(12)})")
        if (splitWarning != null) appendLog(id, "WARNING: $splitWarning")
        refresh()
        project
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        projectDir(id).deleteRecursively()
        refresh()
    }

    suspend fun appendLog(id: String, line: String) = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logFile(id).appendText("[$stamp] $line\n")
    }

    fun readLog(id: String): String = runCatching { logFile(id).readText() }.getOrDefault("")

    fun isEmptyLog(id: String): Boolean = !logFile(id).isFile || logFile(id).length() == 0L

    /** Copies the log into the exports folder and returns the file, so it can be shared. */
    suspend fun exportLog(id: String): File = withContext(Dispatchers.IO) {
        exportsDir.mkdirs()
        val out = File(exportsDir, "log_${id}_${System.currentTimeMillis()}.txt")
        out.writeText(readLog(id))
        out
    }

    suspend fun listRulePacks(): List<RulePack> = withContext(Dispatchers.IO) {
        rulesDir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            runCatching { json.decodeFromString<RulePack>(f.readText()) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun loadRulePack(id: String): RulePack? = withContext(Dispatchers.IO) {
        val f = File(rulesDir, "$id.json")
        if (!f.isFile) null else runCatching { json.decodeFromString<RulePack>(f.readText()) }.getOrNull()
    }

    suspend fun saveRulePack(pack: RulePack) = withContext(Dispatchers.IO) {
        rulesDir.mkdirs()
        File(rulesDir, "${pack.id}.json").writeText(json.encodeToString(pack))
    }

    suspend fun deleteRulePack(id: String) = withContext(Dispatchers.IO) {
        File(rulesDir, "$id.json").delete()
    }
}
