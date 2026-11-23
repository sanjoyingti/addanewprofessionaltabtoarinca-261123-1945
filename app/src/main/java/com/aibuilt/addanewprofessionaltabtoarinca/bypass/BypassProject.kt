=== ABOUT: Adds the missing project settings the requirements ask for: dry run on by default, traffic capture, offline proof and an optional custom keystore ===
```kotlin
package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.serialization.Serializable

@Serializable
enum class BypassTarget(val title: String, val blurb: String) {
    SUBSCRIPTION("Subscription / premium", "Make the app resolve purchases and plans as owned."),
    ADS("Ads", "Stop the ad gate from blocking screens."),
    NSFW_GATE("NSFW / age gate", "Unlock the 18+ confirm wall."),
    FORCE_UPDATE("Force update", "Kill the update wall so the old build opens."),
    LOGIN_WALL("Login wall", "Let the app past the login prompt."),
    API_BASE_URL("API base URL swap", "Repoint every endpoint at another host."),
    SIGNATURE_CHECK("Signature check", "Neuter the self-signature check."),
    AD_SDK_REMOVAL("Ad SDK removal (optional)", "Strip ad SDK load and show calls.")
}

@Serializable
enum class Confidence { HIGH, MEDIUM, LOW }

@Serializable
enum class Stage { INTAKE, TOOLCHAIN, DECODED, ANALYSED, PATCHED, REBUILT, TESTING, DONE, FAILED }

@Serializable
enum class QuickTool {
    DISABLE_PINNING,
    FORCE_TRUE,
    FORCE_FALSE,
    NEUTRALIZE_AUTH,
    KILL_FORCE_UPDATE,
    UNLOCK_NSFW,
    SWAP_BASE_URL,
    STUB_CLASS
}

@Serializable
data class Finding(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val file: String,
    val confidence: Confidence
)

@Serializable
data class TestNote(
    val id: String,
    val text: String,
    val created: Long,
    val taps: Int = 0
)

@Serializable
data class RoundRecord(
    val round: Int,
    val time: Long,
    val outcome: String,
    val detail: String,
    val diff: String,
    val fix: String? = null,
    val confidence: Confidence = Confidence.MEDIUM,
    val offline: Boolean = false,
    val screenshot: String? = null
)

@Serializable
data class ApplyRecord(
    val id: String,
    val time: Long,
    val title: String,
    val patchId: String
)

@Serializable
data class BypassProject(
    val id: String,
    val name: String,
    val created: Long,
    val updated: Long,
    val sourceName: String,
    val sourceHash: String,
    val sourcePackage: String? = null,
    val sourceVersionName: String? = null,
    val sourceVersionCode: Long? = null,
    val splitWarning: String? = null,
    val stage: Stage = Stage.INTAKE,
    val appliedTargets: List<BypassTarget> = emptyList(),
    val lastTarget: BypassTarget? = null,
    val clonePackage: String? = null,
    val rebuiltApkPath: String? = null,
    val usableScreenReached: Boolean = false,
    val integritySwept: Boolean = false,
    val roundsDone: Int = 0,
    val maxRounds: Int = 6,
    val roundTimeoutMs: Long = 45_000L,
    val stuckThreshold: Int = 8,
    val fridaGadget: Boolean = false,
    val screenshots: Boolean = false,
    val adSdkRemoval: Boolean = false,
    val dryRun: Boolean = true,
    val captureTraffic: Boolean = false,
    val offlineProofDone: Boolean = false,
    val customKeystorePath: String? = null,
    val trafficAtGate: String? = null,
    val lastDiff: String? = null,
    val notes: List<TestNote> = emptyList(),
    val rounds: List<RoundRecord> = emptyList(),
    val applied: List<ApplyRecord> = emptyList(),
    val rulePacks: List<String> = emptyList(),
    val buildLogPath: String? = null
) {
    val exportUnlocked: Boolean get() = usableScreenReached
}

@Serializable
data class RulePattern(
    val label: String,
    val needle: String,
    val confidence: Confidence = Confidence.MEDIUM,
    val kind: String = "custom"
)

@Serializable
data class RulePack(
    val id: String,
    val name: String,
    val created: Long,
    val patterns: List<RulePattern> = emptyList()
)

object BypassWarnings {
    val deadEnds: List<String> = listOf(
        "Server receipt revalidation: a Play purchase that the server re-checks with Google can still fail. The clone cannot forward a real receipt, so the app may re-lock on the next launch.",
        "Play Integrity: the clone is signed with a new key and is not installed from Play. Apps that require a Play Integrity verdict can refuse to start.",
        "Firebase / Google login: a renamed clone needs its own google-services.json and the new SHA-1 registered in the OAuth console. Without that, sign-in fails.",
        "Split APKs: only base.apk is patched. A bundle that needs its splits crashes on resource lookups once renamed."
    )
}
