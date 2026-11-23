package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Integrity(
    private val toolchain: Toolchain,
    private val patcher: Patcher,
    private val log: suspend (String) -> Unit
) {

    private val configPath = "res/xml/network_security_config.xml"

    private val permissiveConfig = """
        <?xml version="1.0" encoding="utf-8"?>
        <network-security-config>
            <base-config cleartextTrafficPermitted="true">
                <trust-anchors>
                    <certificates src="system" />
                    <certificates src="user" />
                </trust-anchors>
            </base-config>
            <debug-overrides>
                <trust-anchors>
                    <certificates src="system" />
                    <certificates src="user" />
                </trust-anchors>
            </debug-overrides>
        </network-security-config>
    """.trimIndent()

    /** Rewrites the network security config so user CAs are trusted and no pins survive. */
    suspend fun disablePinning(root: File): PatchPreview = withContext(Dispatchers.IO) {
        val before = patcher.read(root, configPath)
        val preview = PatchPreview(
            patchId = "pinning-${System.currentTimeMillis() % 100000}",
            summary = "Trust user certificates and drop every pin",
            diffs = listOf(FileDiff(configPath, before, permissiveConfig)),
            notes = buildList {
                add("Pins in the old config are gone; user-installed certificates are trusted.")
                if (before.isBlank()) add("No network_security_config.xml existed, one is created.")
            }
        )
        log("pinning: config prepared (${if (before.isBlank()) "created" else "rewritten"})")
        preview
    }

    /** Points the manifest at the config file, adding the attribute if the app never had one. */
    suspend fun ensureConfigWired(root: File): PatchPreview = patcher.ensureConfigWired(root, "network_security_config")

    /** Makes every boolean signature comparison say "genuine". */
    suspend fun neuterSignatureChecks(root: File): PatchPreview =
        patcher.forceBooleanMethods(
            root,
            Regex("(checkSignature|verifySignature|isSignatureValid|checkSignatures|verifySignatures|isGenuine|checkSigningCertificate)"),
            true,
            "Neuter self-signature checks"
        )

    /** apk-mitm pass on the pristine APK — rewrites pinning baked into native code too. */
    suspend fun apkMitm(apk: File, outApk: File): ShellResult {
        if (!toolchain.linuxAvailable()) {
            val message = "Linux missing — apk-mitm skipped."
            log(message)
            return ShellResult(-1, "", message)
        }
        val result = toolchain.sh("apk-mitm \"${apk.absolutePath}\" -o \"${outApk.absolutePath}\"", timeoutMs = 600_000)
        if (result.ok) log("apk-mitm finished") else log("apk-mitm failed: ${result.err.take(300)}")
        return result
    }

    data class SweepOutcome(val previews: List<PatchPreview>, val notes: List<String>)

    /**
     * The whole sweep, in the order that keeps later tests honest:
     * config rewrite -> manifest wiring -> signature neutering -> apk-mitm on the source.
     */
    suspend fun sweep(root: File, apk: File): SweepOutcome {
        log("integrity sweep started")
        val previews = ArrayList<PatchPreview>()
        val notes = ArrayList<String>()
        previews.add(disablePinning(root))
        previews.add(ensureConfigWired(root))
        val signature = neuterSignatureChecks(root)
        previews.add(signature)
        if (signature.changed.isEmpty()) notes.add("No self-signature check method matched — the app may not check its own signature.")
        if (apk.isFile) {
            val mitmOut = File(apk.parentFile, "base_mitm.apk")
            val result = apkMitm(apk, mitmOut)
            if (result.ok) notes.add("apk-mitm rewritten copy produced at ${mitmOut.name}.")
            else notes.add("apk-mitm could not run (${result.err.take(120)}); the in-tree pinning rewrite still applies.")
        }
        log("integrity sweep finished")
        return SweepOutcome(previews, notes)
    }
}
