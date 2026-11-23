package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aibuilt.addanewprofessionaltabtoarinca.ArinApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class BypassUi(
    val intaking: Boolean = false,
    val busyLabel: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val error: String? = null,
    val projects: List<BypassProject> = emptyList(),
    val project: BypassProject? = null,
    val findings: ScanResult? = null,
    val log: String = "",
    val tree: List<String> = emptyList(),
    val treeFilter: String = "",
    val openFile: String? = null,
    val editorText: String = "",
    val javaText: String? = null,
    val pendingTitle: String? = null,
    val pendingDiff: String = "",
    val pendingCount: Int = 0,
    val pendingIsLow: Boolean = false,
    val endpoints: List<TextHit> = emptyList(),
    val searchHits: List<TextHit> = emptyList(),
    val issues: List<SmaliIssue> = emptyList(),
    val linuxOk: Boolean = true,
    val toolchainReady: Boolean = false,
    val mockRunning: Boolean = false,
    val baseUrl: String = "https://",
    val methodName: String = "isPremium",
    val needle: String = "",
    val replacement: String = "",
    val apps: List<InstalledApp> = emptyList(),
    val packs: List<RulePack> = emptyList(),
    val packName: String = "",
    val exportPath: String? = null,
    val reportPath: String? = null,
    val build: BuildStatus = BuildStatus(),
    val captureReady: Boolean = false
)

class BypassViewModel(app: Application) : AndroidViewModel(app) {

    private val store: BypassStore = (app as? ArinApp)?.store ?: BypassStore(app)
    private val intake = ApkIntake(app)
    private val rulePacks = RulePacks(store)
    private val toolchain = Toolchain(app) { line -> log(line) }
    private val patcher = Patcher(toolchain) { line -> log(line) }
    private val scanner = Scanner({ line -> log(line) }, rulePacks)
    private val integrity = Integrity(toolchain, patcher) { line -> log(line) }
    private val entitlement = Entitlement(patcher) { line -> log(line) }
    private val runner = TargetRunner(patcher, integrity, entitlement) { line -> log(line) }
    private val repair = RepairLoop(app, patcher) { line -> log(line) }
    private val mock = MockServer({ line -> log(line) }, { line -> log("mock: $line") })

    private val _ui = MutableStateFlow(BypassUi())
    val ui: StateFlow<BypassUi> = _ui.asStateFlow()

    private var pending: List<PatchPreview> = emptyList()
    private var pendingTarget: BypassTarget? = null

    init {
        viewModelScope.launch { store.projects.collect { list -> _ui.value = _ui.value.copy(projects = list) } }
        viewModelScope.launch {
            BuildBus.state.collect { status ->
                _ui.value = _ui.value.copy(build = status)
                if (status.done && status.success && status.projectId == _ui.value.project?.id) {
                    val reloaded = store.load(status.projectId)
                    if (reloaded != null) _ui.value = _ui.value.copy(project = reloaded)
                    log("rebuild finished: ${status.output ?: ""}")
                }
            }
        }
        viewModelScope.launch { _ui.value = _ui.value.copy(linuxOk = toolchain.linuxAvailable()) }
        refreshProjects()
    }

    // ---------------------------------------------------------------- logging

    private suspend fun log(line: String) {
        val id = _ui.value.project?.id
        if (id == null) {
            _ui.value = _ui.value.copy(log = (_ui.value.log + line + "\n").takeLast(40_000))
            return
        }
        store.appendLog(id, line)
        _ui.value = _ui.value.copy(log = store.readLog(id).takeLast(40_000))
    }

    private fun busy(label: String) {
        _ui.value = _ui.value.copy(busyLabel = label, error = null)
    }

    private fun idle() {
        _ui.value = _ui.value.copy(busyLabel = null, progress = 0f)
    }

    private fun fail(text: String) {
        _ui.value = _ui.value.copy(error = text, busyLabel = null, progress = 0f)
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null, error = null)
    }

    fun setTreeFilter(value: String) {
        _ui.value = _ui.value.copy(treeFilter = value)
        viewModelScope.launch { refreshTree() }
    }

    fun setBaseUrl(value: String) {
        _ui.value = _ui.value.copy(baseUrl = value)
    }

    fun setMethodName(value: String) {
        _ui.value = _ui.value.copy(methodName = value)
    }

    fun setNeedle(value: String) {
        _ui.value = _ui.value.copy(needle = value)
    }

    fun setReplacement(value: String) {
        _ui.value = _ui.value.copy(replacement = value)
    }

    fun setPackName(value: String) {
        _ui.value = _ui.value.copy(packName = value)
    }

    fun setEditor(text: String) {
        _ui.value = _ui.value.copy(editorText = text)
    }

    fun markCaptureReady(ready: Boolean) {
        _ui.value = _ui.value.copy(captureReady = ready)
    }

    // ---------------------------------------------------------------- projects

    fun refreshProjects() = viewModelScope.launch {
        store.refresh()
        _ui.value = _ui.value.copy(projects = store.projects.value, intaking = true)
        _ui.value = _ui.value.copy(
            apps = runCatching { intake.installedApps() }.getOrDefault(emptyList()).take(300),
            packs = rulePacks.list(),
            intaking = false
        )
    }

    fun importUri(uri: Uri, wantedName: String) = viewModelScope.launch {
        busy("Reading the picked file")
        val temp = File(store.root, "incoming/${System.currentTimeMillis()}").apply { mkdirs() }
        val display = displayName(uri)
        val result = intake.fromUri(uri, temp, display)
        if (!result.ok) {
            temp.deleteRecursively()
            fail(result.message)
            return@launch
        }
        val project = store.create(
            name = wantedName.ifBlank { display?.substringBeforeLast('.') ?: "Imported APK" },
            sourceName = display ?: "picked.apk",
            sourceHash = result.hash,
            sourcePackage = result.packageName,
            sourceVersionName = result.versionName,
            sourceVersionCode = result.versionCode,
            splitWarning = result.warning
        )
        temp.listFiles()?.forEach { file ->
            runCatching { file.copyTo(File(store.pristineDir(project.id), file.name), overwrite = true) }
        }
        temp.deleteRecursively()
        _ui.value = _ui.value.copy(project = project)
        log("imported ${display ?: "file"} — sha256 ${result.hash.take(16)}")
        if (result.warning != null) _ui.value = _ui.value.copy(message = result.warning)
        idle()
        refreshProjects()
    }

    fun importInstalled(app: InstalledApp) = viewModelScope.launch {
        busy("Copying ${app.label}")
        val temp = File(store.root, "incoming/${System.currentTimeMillis()}").apply { mkdirs() }
        val result = intake.fromInstalled(app, temp)
        if (!result.ok) {
            temp.deleteRecursively()
            fail(result.message)
            return@launch
        }
        val project = store.create(
            name = app.label,
            sourceName = "${app.label} (${app.packageName})",
            sourceHash = result.hash,
            sourcePackage = result.packageName,
            sourceVersionName = result.versionName,
            sourceVersionCode = result.versionCode,
            splitWarning = result.warning
        )
        temp.listFiles()?.forEach { file ->
            runCatching { file.copyTo(File(store.pristineDir(project.id), file.name), overwrite = true) }
        }
        temp.deleteRecursively()
        _ui.value = _ui.value.copy(project = project)
        log("imported installed app ${app.packageName}")
        if (result.warning != null) _ui.value = _ui.value.copy(message = result.warning)
        idle()
        refreshProjects()
    }

    fun openProject(id: String) = viewModelScope.launch {
        val project = store.load(id) ?: return@launch
        pending = emptyList()
        pendingTarget = null
        _ui.value = _ui.value.copy(
            project = project,
            findings = null,
            pendingTitle = null,
            pendingDiff = "",
            pendingCount = 0,
            openFile = null,
            editorText = "",
            javaText = null,
            issues = emptyList(),
            searchHits = emptyList(),
            log = store.readLog(id),
            linuxOk = toolchain.linuxAvailable(),
            mockRunning = mock.running
        )
        refreshTree()
        loadEndpoints()
        val packs = rulePacks.list()
        _ui.value = _ui.value.copy(packs = packs)
    }

    fun closeProject() {
        _ui.value = _ui.value.copy(project = null, pendingTitle = null, pendingDiff = "", pendingCount = 0)
        pending = emptyList()
        pendingTarget = null
    }

    fun deleteProject(id: String) = viewModelScope.launch {
        store.delete(id)
        if (_ui.value.project?.id == id) closeProject()
        refreshProjects()
    }

    fun updateProject(project: BypassProject) = viewModelScope.launch {
        store.save(project)
        _ui.value = _ui.value.copy(project = project)
    }

    // ---------------------------------------------------------------- toolchain

    fun installToolchain() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        base()
        busy("Preparing the toolchain")
        val result = toolchain.installAll(store.projectDir(project.id)) { progress, message ->
            _ui.value = _ui.value.copy(progress = progress, busyLabel = message)
        }
        _ui.value = _ui.value.copy(toolchainReady = result.ok, message = result.message)
        idle()
        refreshTree()
    }

    private fun base() {
        _ui.value = _ui.value.copy(
            findings = _ui.value.findings,
            linuxOk = toolchain.linuxAvailable()
        )
    }

    // ---------------------------------------------------------------- decode

    fun decode() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (!toolchain.linuxAvailable()) {
            fail("No Linux box found under the app's files folder. Install it first — apktool and JADX cannot run without it.")
            return@launch
        }
        busy("Checking the toolchain")
        val toolchainResult = toolchain.installAll(store.projectDir(project.id)) { progress, message ->
            _ui.value = _ui.value.copy(progress = progress, busyLabel = message)
        }
        _ui.value = _ui.value.copy(toolchainReady = toolchainResult.ok)
        if (!toolchainResult.ok) {
            fail(toolchainResult.message)
            return@launch
        }

        val base = File(store.pristineDir(project.id), "base.apk")
        if (!base.isFile) {
            fail("The pristine base.apk is gone. Import the APK again.")
            return@launch
        }
        val work = store.workDir(project.id)
        work.mkdirs()

        busy("Decoding resources and smali")
        val apktoolResult = toolchain.sh(
            "\"${toolchain.apktool()}\" d -f --no-debug-info -o \"${work.absolutePath}\" \"${base.absolutePath}\"",
            timeoutMs = 900_000
        )
        if (!File(work, "AndroidManifest.xml").isFile) {
            log("apktool failed, falling back to a raw unzip: ${apktoolResult.err.take(300)}")
            _ui.value = _ui.value.copy(busyLabel = "Falling back to a raw unzip")
            intake.extractTree(base, work)
        }
        File(work, "META-INF").listFiles()?.forEach { file ->
            if (file.extension.lowercase() in setOf("rsa", "dsa", "ec", "sf", "mf")) file.delete()
        }

        _ui.value = _ui.value.copy(busyLabel = "Decompiling the Java view")
        val javaDir = store.javaDir(project.id)
        toolchain.sh(
            "\"${toolchain.jadx()}\" -d \"${javaDir.absolutePath}\" --no-res -r -q \"${base.absolutePath}\"",
            timeoutMs = 900_000
        )

        val multiDex = work.listFiles()?.count { it.name.startsWith("smali") } ?: 0
        log("decoded into ${work.absolutePath} ($multiDex smali root(s), Java view in ${javaDir.name})")
        store.save(project.copy(stage = Stage.DECODED))
        _ui.value = _ui.value.copy(project = store.load(project.id) ?: project, message = "Decoded. Run the analysis next.")
        // apk-mitm on the pristine copy, so pins baked into native code are gone too.
        val mitigated = File(store.pristineDir(project.id), "base_mitm.apk")
        if (!mitigated.isFile) integrity.apkMitm(base, mitigated)
        idle()
        refreshTree()
        scan()
    }

    // ---------------------------------------------------------------- analysis

    fun scan() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        busy("Scanning")
        val result = scanner.scan(store.workDir(project.id))
        _ui.value = _ui.value.copy(
            findings = result,
            message = "${result.high.size} high, ${result.medium.size} medium and ${result.low.size} low-confidence finding(s)."
        )
        if (project.stage == Stage.DECODED) store.save(project.copy(stage = Stage.ANALYSED))
        _ui.value = _ui.value.copy(project = store.load(project.id) ?: project)
        idle()
        loadEndpoints()
    }

    fun loadEndpoints() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val hits = patcher.endpointInventory(store.workDir(project.id))
        _ui.value = _ui.value.copy(endpoints = hits)
    }

    fun findUsages() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val needle = _ui.value.methodName.ifBlank { return@launch }
        busy("Finding $needle")
        val hits = patcher.findUsages(store.workDir(project.id), needle)
        _ui.value = _ui.value.copy(searchHits = hits)
        log("find $needle: ${hits.size} hit(s) across all dex")
        idle()
    }

    // ---------------------------------------------------------------- editing

    private suspend fun refreshTree() {
        val project = _ui.value.project ?: return
        val filter = _ui.value.treeFilter
        val tree = patcher.tree(store.workDir(project.id), filter, 400)
        _ui.value = _ui.value.copy(tree = tree)
    }

    fun openFile(rel: String) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        base()
        val text = patcher.read(store.workDir(project.id), rel)
        val javaRel = run {
            val slash = rel.indexOf('/')
            val inner = if (slash >= 0) rel.substring(slash + 1) else rel
            inner.removeSuffix(".smali") + ".java"
        }
        val javaFile = File(File(store.javaDir(project.id), "sources"), javaRel)
        val java = if (javaFile.isFile) runCatching { javaFile.readText() }.getOrNull() else null
        _ui.value = _ui.value.copy(openFile = rel, editorText = text, javaText = java)
    }

    fun saveFile() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val rel = _ui.value.openFile ?: return@launch
        val work = store.workDir(project.id)
        val ledger = store.ledger(project.id)
        if (!ledger.isTouched(rel)) ledger.record(work, rel, "manual-${UUID.randomUUID().toString().take(6)}", "Manual edit of $rel")
        File(work, rel).writeText(_ui.value.editorText)
        log("saved manual edit of $rel")
        _ui.value = _ui.value.copy(message = "Saved $rel to the ledger.")
        store.save(project.copy(stage = Stage.PATCHED, lastDiff = ledger.fullDiff(work)))
        _ui.value = _ui.value.copy(project = store.load(project.id) ?: project)
    }

    fun validate() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        busy("Validating smali")
        val issues = patcher.validate(store.workDir(project.id))
        _ui.value = _ui.value.copy(issues = issues)
        log("validation: ${issues.size} issue(s)")
        idle()
    }

    fun bulkReplacePreview() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        busy("Collecting hits")
        val needle = _ui.value.needle
        val replacement = _ui.value.replacement
        val preview = patcher.bulkReplace(store.workDir(project.id), needle, replacement, includeDex = true)
        showPending(listOf(preview), "Replace \"$needle\"", null)
        idle()
    }

    // ---------------------------------------------------------------- patching

    private fun showPending(previews: List<PatchPreview>, title: String, target: BypassTarget?, low: Boolean = false) {
        pending = previews
        pendingTarget = target
        val merged = previews.joinToString("\n") { it.unified }
        _ui.value = _ui.value.copy(
            pendingTitle = title,
            pendingDiff = if (merged.isBlank()) "No file would change." else merged,
            pendingCount = previews.sumOf { it.changed.size } + previews.sumOf { it.deletes.size },
            pendingIsLow = low
        )
    }

    fun requestTarget(target: BypassTarget) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (project.stage == Stage.INTAKE) {
            fail("Decode the APK first.")
            return@launch
        }
        busy("Planning ${target.title}")
        val work = store.workDir(project.id)
        val argument = if (target == BypassTarget.API_BASE_URL) _ui.value.baseUrl else null
        val plan = runner.plan(project, work, target, argument)
        plan.notes.forEach { log(it) }
        showPending(plan.previews.filter { it.hasChanges }.ifEmpty { plan.previews }, target.title, target)
        if (!plan.hasChanges) _ui.value = _ui.value.copy(message = "Nothing to change for ${target.title}. ${plan.notes.firstOrNull() ?: ""}")
        idle()
    }

    fun runTool(tool: QuickTool) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (project.stage == Stage.INTAKE) {
            fail("Decode the APK first.")
            return@launch
        }
        busy("Preparing ${tool.name.lowercase().replace('_', ' ')}")
        val argument = when (tool) {
            QuickTool.FORCE_TRUE, QuickTool.FORCE_FALSE -> _ui.value.methodName
            QuickTool.SWAP_BASE_URL -> _ui.value.baseUrl
            QuickTool.STUB_CLASS -> _ui.value.openFile
            else -> null
        }
        val preview = runner.toolPreview(store.workDir(project.id), tool, argument)
        preview.notes.forEach { log(it) }
        showPending(listOf(preview), preview.summary, null)
        idle()
    }

    fun requestStubOpenFile() = viewModelScope.launch {
        val rel = _ui.value.openFile ?: run {
            fail("Open a smali file first.")
            return@launch
        }
        runToolFor(QuickTool.STUB_CLASS, rel)
    }

    private suspend fun runToolFor(tool: QuickTool, argument: String?) {
        val project = _ui.value.project ?: return
        val preview = runner.toolPreview(store.workDir(project.id), tool, argument)
        preview.notes.forEach { log(it) }
        showPending(listOf(preview), preview.summary, null)
    }

    fun manifestRepairs() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (project.stage == Stage.INTAKE) {
            fail("Decode the APK first.")
            return@launch
        }
        busy("Checking the manifest")
        val preview = patcher.manifestRepairs(store.workDir(project.id))
        preview.notes.forEach { log(it) }
        showPending(listOf(preview), preview.summary, null)
        idle()
    }

    fun webViewBridge() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        busy("Looking for a WebView gate")
        val preview = patcher.patchWebViewBridge(store.workDir(project.id))
        preview.notes.forEach { log(it) }
        showPending(listOf(preview), preview.summary, null)
        idle()
    }

    fun renamePackage() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val source = project.sourcePackage
        if (source.isNullOrBlank()) {
            fail("The source package name is unknown, so no rename is possible.")
            return@launch
        }
        busy("Preparing the rename")
        val clone = "$source.arinclone"
        val preview = patcher.renamePackage(store.workDir(project.id), source, clone)
        preview.notes.forEach { log(it) }
        store.save(project.copy(clonePackage = clone))
        _ui.value = _ui.value.copy(project = store.load(project.id) ?: project)
        showPending(listOf(preview), preview.summary, null)
        idle()
    }

    fun injectFrida() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        busy("Fetching the gadget")
        val preview = patcher.injectFridaGadget(store.workDir(project.id), "arm64")
        preview.notes.forEach { log(it) }
        showPending(listOf(preview), preview.summary, null)
        store.save(project.copy(fridaGadget = preview.hasChanges))
        idle()
    }

    fun applyPending() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (pending.isEmpty()) return@launch
        val work = store.workDir(project.id)
        val ledger = store.ledger(project.id)
        busy("Writing the patch")
        val before = ledger.entries().size
        pending.forEach { preview -> patcher.apply(work, preview, ledger) }
        val created = ledger.entries().drop(before)
        val title = _ui.value.pendingTitle ?: "Patch"
        val targets = if (pendingTarget != null) (project.appliedTargets + pendingTarget!!).distinct() else project.appliedTargets
        val records = project.applied + created.map {
            ApplyRecord(id = it.id, time = it.time, title = it.summary, patchId = it.patchId)
        }
        val updated = store.save(
            project.copy(
                stage = Stage.PATCHED,
                appliedTargets = targets,
                lastTarget = pendingTarget ?: project.lastTarget,
                lastDiff = ledger.fullDiff(work),
                applied = records,
                integritySwept = project.integritySwept || pendingTarget != null
            )
        )
        _ui.value = _ui.value.copy(project = updated, pendingTitle = null, pendingDiff = "", pendingCount = 0)
        pending = emptyList()
        pendingTarget = null
        _ui.value = _ui.value.copy(message = "$title applied (${created.size} file(s) in the ledger).")
        idle()
        refreshTree()
        scan()
    }

    fun discardPending() {
        pending = emptyList()
        pendingTarget = null
        _ui.value = _ui.value.copy(pendingTitle = null, pendingDiff = "", pendingCount = 0)
    }

    fun revertEntry(entryId: String) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val work = store.workDir(project.id)
        val ledger = store.ledger(project.id)
        if (ledger.revert(work, entryId)) {
            log("reverted ledger entry $entryId")
            store.save(project.copy(lastDiff = ledger.fullDiff(work)))
            _ui.value = _ui.value.copy(project = store.load(project.id) ?: project, message = "One patch reverted.")
            refreshTree()
        }
    }

    fun revertAll() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val work = store.workDir(project.id)
        store.ledger(project.id).revertAll(work)
        log("every patch reverted")
        store.save(project.copy(stage = Stage.DECODED, appliedTargets = emptyList(), lastDiff = "", applied = emptyList()))
        _ui.value = _ui.value.copy(project = store.load(project.id) ?: project, message = "All patches reverted.")
        refreshTree()
    }

    fun restorePristine() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        busy("Restoring the pristine original")
        store.workDir(project.id).deleteRecursively()
        store.javaDir(project.id).deleteRecursively()
        File(store.projectDir(project.id), "ledger.json").delete()
        File(store.projectDir(project.id), "backups").deleteRecursively()
        store.save(
            project.copy(
                stage = Stage.INTAKE,
                appliedTargets = emptyList(),
                lastDiff = "",
                applied = emptyList(),
                rebuiltApkPath = null,
                integritySwept = false
            )
        )
        log("restored to the pristine original")
        _ui.value = _ui.value.copy(project = store.load(project.id) ?: project, message = "Pristine original restored. Decode again.")
        idle()
        refreshTree()
    }

    // ---------------------------------------------------------------- rebuild & test

    fun rebuild() {
        val project = _ui.value.project ?: return
        if (!toolchain.linuxAvailable()) {
            fail("The Linux box is missing, so the clone cannot be signed.")
            return
        }
        BypassBuildService.start(getApplication(), project.id)
    }

    fun cancelBuild() {
        BypassBuildService.cancel(getApplication())
    }

    fun installClone() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val apk = project.rebuiltApkPath?.let { File(it) }
        if (apk == null || !apk.isFile) {
            fail("Rebuild the clone first.")
            return@launch
        }
        busy("Installing the clone")
        val ok = Installer.install(getApplication(), apk)
        log(if (ok) "clone installed" else "install failed")
        if (!ok) fail("The installer refused the clone. Check that the package rename was applied and the signature is valid.")
        else _ui.value = _ui.value.copy(message = "Clone installed.")
        idle()
    }

    fun startCapture(code: Int, data: Intent) {
        val project = _ui.value.project ?: return
        ScreenCaptureService.start(getApplication(), code, data, store.screenshotsDir(project.id).absolutePath)
        _ui.value = _ui.value.copy(captureReady = true)
    }

    fun runRound(offline: Boolean = false) = viewModelScope.launch {
        var project = _ui.value.project ?: return@launch
        val packageName = project.clonePackage
        if (packageName.isNullOrBlank()) {
            fail("Rename the package first so the clone can install beside the original.")
            return@launch
        }
        val apk = project.rebuiltApkPath?.let { File(it) }
        if (apk == null || !apk.isFile) {
            fail("Rebuild the clone first.")
            return@launch
        }
        if (!BypassAccessibilityService.serviceEnabled(getApplication())) {
            fail("Turn Arin Bypass on in Accessibility settings — without it crashes and paywalls are invisible.")
            return@launch
        }
        if (offline) log("offline proof round: switch networking off now")
        busy("Round ${project.roundsDone + 1}")

        try {
            while (project.roundsDone < project.maxRounds) {
                val round = project.roundsDone + 1
                _ui.value = _ui.value.copy(busyLabel = "Round $round: installing")
                val installed = Installer.install(getApplication(), apk)
                if (!installed) {
                    val record = RoundRecord(
                        round = round,
                        time = System.currentTimeMillis(),
                        outcome = WatchResult.INSTALL_FAILED,
                        detail = "The clone could not be installed.",
                        diff = project.lastDiff.orEmpty(),
                        confidence = Confidence.HIGH
                    )
                    project = store.save(project.copy(rounds = project.rounds + record, stage = Stage.FAILED))
                    _ui.value = _ui.value.copy(project = project, error = "Round $round: install failed.")
                    break
                }
                if (project.screenshots && ScreenCaptureService.active) {
                    ScreenCaptureService.capture(getApplication())
                }
                _ui.value = _ui.value.copy(busyLabel = "Round $round: launching")
                if (!Installer.launch(getApplication(), packageName)) {
                    fail("The clone has no launcher activity.")
                    break
                }
                _ui.value = _ui.value.copy(busyLabel = "Round $round: watching the screen")
                val watch = repair.watch(packageName, project.roundTimeoutMs, project.stuckThreshold) { status ->
                    _ui.value = _ui.value.copy(busyLabel = "Round $round: $status")
                }
                val shot = ScreenCaptureService.lastShotPath
                var diff = ""
                var fixNote: String? = null
                var confidence = Confidence.MEDIUM

                if (watch.ok) {
                    project = store.save(
                        project.copy(
                            rounds = project.rounds + RoundRecord(
                                round = round,
                                time = System.currentTimeMillis(),
                                outcome = watch.outcome,
                                detail = watch.detail,
                                diff = "",
                                confidence = Confidence.HIGH,
                                offline = offline,
                                screenshot = shot
                            ),
                            roundsDone = round,
                            usableScreenReached = true,
                            offlineProofDone = project.offlineProofDone || offline,
                            stage = Stage.DONE
                        )
                    )
                    _ui.value = _ui.value.copy(
                        project = project,
                        message = if (offline) "Offline proof passed: the clone opened with no network." else "Usable screen reached."
                    )
                    break
                }

                val fix = repair.generateFix(store.workDir(project.id), watch.className, watch.text)
                if (fix == null) {
                    confidence = Confidence.LOW
                    fixNote = "No confident fix — stopped so the guess is not hidden."
                } else {
                    if (watch.className == null) confidence = Confidence.LOW
                    fixNote = fix.summary
                    val before = store.ledger(project.id).entries().size
                    patcher.apply(store.workDir(project.id), fix, store.ledger(project.id))
                    diff = store.ledger(project.id).entries().drop(before).joinToString("\n") {
                        store.ledger(project.id).diff(store.workDir(project.id), it.id)
                    }
                    showPending(listOf(fix), "Round $round fix: ${fix.summary}", null, low = confidence == Confidence.LOW)
                }

                project = store.save(
                    project.copy(
                        rounds = project.rounds + RoundRecord(
                            round = round,
                            time = System.currentTimeMillis(),
                            outcome = watch.outcome,
                            detail = watch.detail,
                            diff = diff.ifBlank { project.lastDiff.orEmpty() },
                            fix = fixNote,
                            confidence = confidence,
                            offline = offline,
                            screenshot = shot
                        ),
                        roundsDone = round,
                        lastDiff = diff.ifBlank { project.lastDiff }
                    )
                )
                _ui.value = _ui.value.copy(project = project)

                if (confidence == Confidence.LOW) {
                    _ui.value = _ui.value.copy(error = "Round $round: $fixNote")
                    break
                }

                _ui.value = _ui.value.copy(busyLabel = "Round $round: rebuilding")
                val rebuilt = Rebuilder.run(getApplication(), store, toolchain, patcher, project.id) { progress, message ->
                    _ui.value = _ui.value.copy(progress = progress, busyLabel = "Round $round: $message")
                }
                if (!rebuilt.first) {
                    _ui.value = _ui.value.copy(error = "Round $round rebuild failed: ${rebuilt.second}")
                    break
                }
                val fresh = store.load(project.id)
                if (fresh != null) {
                    project = fresh
                    _ui.value = _ui.value.copy(project = fresh)
                }
                apk.takeIf { it.isFile }
                val next = project.rebuiltApkPath?.let { File(it) }
                if (next != null) {
                    @Suppress("NAME_SHADOWING") val apk2 = next
                    if (!apk2.isFile) break
                }
            }
        } finally {
            idle()
        }
    }

    fun offlineProof() {
        _ui.value = _ui.value.copy(message = "Turn Wi-Fi and mobile data off, then the round decides: opens = airtight, dies = the check is server-live.")
        runRound(offline = true)
    }

    // ---------------------------------------------------------------- mock server

    fun toggleMock() {
        if (mock.running) {
            mock.stop()
            _ui.value = _ui.value.copy(mockRunning = false)
        } else {
            mock.start(viewModelScope, MockServer.DEFAULT_PORT)
            _ui.value = _ui.value.copy(mockRunning = true)
        }
    }

    // ---------------------------------------------------------------- notes, packs, reports

    fun addNote(text: String) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (text.isBlank()) return@launch
        val note = TestNote(UUID.randomUUID().toString().take(8), text.trim(), System.currentTimeMillis())
        val updated = store.save(project.copy(notes = project.notes + note))
        _ui.value = _ui.value.copy(project = updated)
        log("test note added: ${note.text}")
    }

    fun tapNote(id: String) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val notes = project.notes.map { if (it.id == id) it.copy(taps = it.taps + 1) else it }
        val updated = store.save(project.copy(notes = notes))
        _ui.value = _ui.value.copy(project = updated)
        log("test note repeated: ${notes.firstOrNull { it.id == id }?.text}")
    }

    fun removeNote(id: String) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val updated = store.save(project.copy(notes = project.notes.filterNot { it.id == id }))
        _ui.value = _ui.value.copy(project = updated)
    }

    fun loadPacks() = viewModelScope.launch {
        _ui.value = _ui.value.copy(packs = rulePacks.list())
    }

    fun savePack() = viewModelScope.launch {
        val name = _ui.value.packName
        val pack = rulePacks.saveAs(name)
        rulePacks.clear()
        _ui.value = _ui.value.copy(packs = rulePacks.list(), packName = "", message = "Rule pack \"${pack.name}\" saved.")
    }

    fun loadPack(id: String) = viewModelScope.launch {
        val pack = rulePacks.load(id)
        _ui.value = _ui.value.copy(message = if (pack == null) "Pack could not be read." else "Pack \"${pack.name}\" loaded.")
    }

    fun deletePack(id: String) = viewModelScope.launch {
        rulePacks.delete(id)
        _ui.value = _ui.value.copy(packs = rulePacks.list())
    }

    fun exportLog() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val file = store.exportLog(project.id)
        _ui.value = _ui.value.copy(exportPath = file.absolutePath, message = "Log exported.")
    }

    fun exportReport() = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        if (!project.exportUnlocked) {
            fail("The gate is still up — reach a usable screen once before exporting.")
            return@launch
        }
        busy("Writing the report")
        val work = store.workDir(project.id)
        val ledger = store.ledger(project.id)
        val text = BypassReport.render(
            project,
            _ui.value.findings?.findings ?: emptyList(),
            ledger.entries(),
            ledger.fullDiff(work),
            _ui.value.endpoints
        )
        val file = BypassReport.write(
            File(store.reportsDir(project.id), "report_${System.currentTimeMillis()}.md"),
            text
        )
        log("report written to ${file.name}")
        _ui.value = _ui.value.copy(reportPath = file.absolutePath, message = "Report written.")
        idle()
    }

    fun setScreenshots(on: Boolean) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val updated = store.save(project.copy(screenshots = on))
        _ui.value = _ui.value.copy(project = updated)
    }

    fun setAdSdkRemoval(on: Boolean) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val updated = store.save(project.copy(adSdkRemoval = on))
        _ui.value = _ui.value.copy(project = updated)
    }

    fun setDryRun(on: Boolean) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val updated = store.save(project.copy(dryRun = on))
        _ui.value = _ui.value.copy(project = updated)
    }

    fun setRoundBudget(rounds: Int, timeoutMs: Long, stuck: Int) = viewModelScope.launch {
        val project = _ui.value.project ?: return@launch
        val updated = store.save(project.copy(maxRounds = rounds, roundTimeoutMs = timeoutMs, stuckThreshold = stuck))
        _ui.value = _ui.value.copy(project = updated)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    override fun onCleared() {
        mock.stop()
        super.onCleared()
    }
}
