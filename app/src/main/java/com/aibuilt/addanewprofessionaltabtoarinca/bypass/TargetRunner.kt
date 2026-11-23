package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class TargetPlan(
    val target: BypassTarget,
    val previews: List<PatchPreview>,
    val notes: List<String>
) {
    val hasChanges: Boolean get() = previews.any { it.hasChanges }
    val unified: String get() = previews.joinToString("\n") { it.unified }
}

class TargetRunner(
    private val patcher: Patcher,
    private val integrity: Integrity,
    private val entitlement: Entitlement,
    private val log: suspend (String) -> Unit
) {

    private val adWords = Regex("(admob|applovin|unityads|ironsource|mopub|adcolony|vungle|chartboost|startapp|adSdk|loadAd|showInterstitial|showRewarded)")
    private val nsfwWords = Regex("(?i)(nsfw|isAdult|over18|ageGate|ageVerif|confirmAge|censored|unlockAdult)")
    private val updateWords = Regex("(forceUpdate|isUpdateRequired|needsUpdate|updateRequired|isUpdateNeeded|minVersionCode|softUpdate)")
    private val loginWords = Regex("(isLoggedIn|hasValidToken|isAuthenticated|requireLogin|needsLogin|isSignedIn|checkSession)")

    /** Builds the plan. Target arrival always starts with the integrity sweep when it has not run yet. */
    suspend fun plan(project: BypassProject, root: File, target: BypassTarget, argument: String?): TargetPlan =
        withContext(Dispatchers.IO) {
            val previews = ArrayList<PatchPreview>()
            val notes = ArrayList<String>()

            if (!project.integritySwept && target != BypassTarget.SIGNATURE_CHECK) {
                notes.add("Integrity sweep ran first so a pin or a signature mismatch cannot fake the result.")
                val pristineApk = File(File(root.parentFile, "pristine"), "base.apk")
                val sweep = integrity.sweep(root, pristineApk)
                previews.addAll(sweep.previews)
                notes.addAll(sweep.notes)
            } else if (target == BypassTarget.SIGNATURE_CHECK) {
                previews.add(integrity.neuterSignatureChecks(root))
            }

            when (target) {
                BypassTarget.SUBSCRIPTION -> {
                    val detection = entitlement.detect(root)
                    if (detection.vendors.isNotEmpty()) notes.add("Billing library detected: ${detection.vendors.joinToString(", ")}.")
                    notes.addAll(detection.notes)
                    previews.add(entitlement.fullPass(root))
                }
                BypassTarget.ADS -> {
                    previews.add(patcher.forceBooleanMethods(root, Regex("(isAdFree|adsRemoved|isAdFreeUser)"), true, "Force ad-free flags true"))
                    previews.add(patcher.forceBooleanMethods(root, Regex("(shouldShowAd|isAdLoaded|canShowAd|isInterstitialReady)"), false, "Stop ads from being shown"))
                    notes.add("Ad call sites stay but never show: the SDK classes are not deleted.")
                }
                BypassTarget.NSFW_GATE -> {
                    previews.add(patcher.forceBooleanMethods(root, nsfwWords, true, "Unlock the NSFW / age gate"))
                }
                BypassTarget.FORCE_UPDATE -> {
                    previews.add(patcher.forceBooleanMethods(root, updateWords, false, "Kill the force-update wall"))
                    notes.add("Version comparison methods keep returning false, so the update dialog never blocks.")
                }
                BypassTarget.LOGIN_WALL -> {
                    previews.add(patcher.forceBooleanMethods(root, loginWords, true, "Neutralise the login check"))
                    notes.add("The token store still holds whatever the app wrote; only the check is satisfied.")
                }
                BypassTarget.API_BASE_URL -> {
                    val swap = argument?.trim().orEmpty()
                    if (swap.isBlank()) {
                        notes.add("No replacement host given — pass one in the base-URL field.")
                    } else {
                        val inventory = patcher.endpointInventory(root)
                        val hosts = inventory.mapNotNull { hit -> runCatching { java.net.URI(hit.text).host }.getOrNull() }
                            .distinct()
                        if (hosts.isEmpty()) notes.add("No absolute endpoint host found to swap.")
                        hosts.forEach { host ->
                            previews.add(patcher.bulkReplace(root, host, swap, includeDex = true))
                        }
                        notes.add("Hosts replaced: ${hosts.joinToString(", ")}")
                    }
                }
                BypassTarget.SIGNATURE_CHECK -> {
                    notes.add("Signature checks neutered. Rebuild and the clone will start.")
                }
                BypassTarget.AD_SDK_REMOVAL -> {
                    val sdkClasses = patcher.findUsages(root, "loadAd(", 40).map { it.relPath }.distinct()
                    sdkClasses.forEach { rel -> previews.add(patcher.stubClass(root, rel)) }
                    notes.add("${sdkClasses.size} ad call site(s) stubbed out.")
                }
            }
            log("plan for $target: ${previews.sumOf { it.changed.size }} file diff(s)")
            TargetPlan(target, previews, notes)
        }

    /** The one-click toolbox. Each entry only previews; nothing is written until the person confirms. */
    suspend fun toolPreview(root: File, tool: QuickTool, argument: String?): PatchPreview =
        withContext(Dispatchers.IO) {
            when (tool) {
                QuickTool.DISABLE_PINNING -> {
                    val config = integrity.disablePinning(root)
                    val manifest = integrity.ensureConfigWired(root)
                    PatchPreview(
                        "tool-pinning-${System.currentTimeMillis() % 100000}",
                        "Disable certificate pinning",
                        config.diffs + manifest.diffs,
                        notes = config.notes + "Manifest now points at the permissive config."
                    )
                }
                QuickTool.FORCE_TRUE ->
                    patcher.forceBooleanMethods(root, Regex(argument?.ifBlank { "is" } ?: "is"), true, "Force ${argument ?: "is…"} to true")
                QuickTool.FORCE_FALSE ->
                    patcher.forceBooleanMethods(root, Regex(argument?.ifBlank { "is" } ?: "is"), false, "Force ${argument ?: "is…"} to false")
                QuickTool.NEUTRALIZE_AUTH -> patcher.forceBooleanMethods(root, loginWords, true, "Neutralise the auth check")
                QuickTool.KILL_FORCE_UPDATE -> patcher.forceBooleanMethods(root, updateWords, false, "Kill force update")
                QuickTool.UNLOCK_NSFW -> patcher.forceBooleanMethods(root, nsfwWords, true, "Unlock the NSFW gate")
                QuickTool.SWAP_BASE_URL -> {
                    val swap = argument?.trim().orEmpty()
                    if (swap.isBlank()) PatchPreview("tool-url", "Swap base URL", notes = listOf("Give a replacement host first."))
                    else {
                        val hits = patcher.endpointInventory(root)
                        val hosts = hits.mapNotNull { runCatching { java.net.URI(it.text).host }.getOrNull() }.distinct()
                        val diffs = hosts.flatMap { host -> patcher.bulkReplace(root, host, swap, false).diffs }
                        PatchPreview("tool-url-${System.currentTimeMillis() % 100000}",
                            "Repoint ${hosts.size} host(s) to $swap", diffs)
                    }
                }
                QuickTool.STUB_CLASS -> {
                    val rel = argument?.trim().orEmpty()
                    if (rel.isBlank()) PatchPreview("tool-stub", "Stub a class", notes = listOf("Pick a smali file first."))
                    else patcher.stubClass(root, rel)
                }
            }
        }

    suspend fun adSdkClasses(root: File): List<String> =
        withContext(Dispatchers.IO) { patcher.findUsages(root, "loadAd(", 200).map { it.relPath }.distinct() }
}
