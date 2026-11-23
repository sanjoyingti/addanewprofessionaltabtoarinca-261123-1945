=== ABOUT: Finds the billing library and the one method that decides "is this paid", then flips that producer and the stored defaults instead of touching every screen ===
```kotlin
package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class BillingDetection(
    val vendors: List<String>,
    val producerFiles: List<String>,
    val notes: List<String>
)

class Entitlement(
    private val patcher: Patcher,
    private val log: suspend (String) -> Unit
) {

    private val producerNames = Regex(
        "(isPremium|isSubscribed|hasActiveSub\\w*|hasSubscription|isPro\\b|isPaid|isVip|hasPremium|hasEntitlement|canAccessPro|isLicensed)"
    )
    private val premiumKey = Regex(
        "(?i)(premium|subscri|purchase|entitle|is_paid|isPro\\b|plan[_ ]?type|vip|license)"
    )
    private val paywallRedirect = Regex("(showPaywall|navigateToPaywall|openPaywall|showSubscription|goToPaywall)")

    private val vendors = mapOf(
        "Play Billing" to Regex("com/android/billingclient"),
        "RevenueCat" to Regex("com/revenuecat|Purchases\\."),
        "Adapty" to Regex("com/adapty"),
        "Qonversion" to Regex("com/qonversion|Qonversion"),
        "Superwall" to Regex("superwall|Superwall"),
        "Home-made billing" to Regex("(purchaseToken|skuDetails|querySkuDetails|onBillingServiceDisconnected)")
    )

    suspend fun detect(root: File): BillingDetection = withContext(Dispatchers.IO) {
        val found = ArrayList<String>()
        val producerFiles = ArrayList<String>()
        val notes = ArrayList<String>()
        val files = patcher.smaliFiles(root).take(6000)
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            vendors.forEach { (name, regex) ->
                if (regex.containsMatchIn(text) && name !in found) {
                    found.add(name)
                    log("billing vendor seen: $name in ${file.name}")
                }
            }
            if (producerNames.containsMatchIn(text)) {
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (producerFiles.size < 40) producerFiles.add(rel)
            }
        }
        if (found.isEmpty()) notes.add("No known billing SDK seen — the app may gate content on a plain boolean field.")
        if (producerFiles.isEmpty()) notes.add("No producer method matched by name. Use the manual editor to pick one.")
        BillingDetection(found, producerFiles, notes)
    }

    /** Flips the single producer and every boolean validator that guards it. */
    suspend fun patchProducers(root: File): PatchPreview {
        val preview = patcher.forceBooleanMethods(root, producerNames, true, "Force the premium producer to true")
        val extra = patcher.forceBooleanMethods(
            root,
            Regex("(checkPurchase|verifyPurchase|isPurchaseValid|purchaseIsActive|validateReceipt)"),
            true,
            "Force the purchase validator to true"
        )
        val combined = PatchPreview(
            patchId = "entitlement-${System.currentTimeMillis() % 100000}",
            summary = "Force premium producer + purchase validator to true",
            diffs = preview.diffs + extra.diffs,
            notes = preview.notes + extra.notes + listOf("Call sites are untouched: only the method that produces the state changes.")
        )
        log("entitlement: producers flipped (${combined.changed.size} file(s))")
        return combined
    }

    /** Seeds the stored defaults: prefs booleans, DataStore getBoolean defaults and DB defaults. */
    suspend fun seedDefaults(root: File): PatchPreview = withContext(Dispatchers.IO) {
        val diffs = ArrayList<FileDiff>()
        val notes = ArrayList<String>()
        for (file in patcher.smaliFiles(root).take(6000)) {
            if (file.length() > 250_000) continue
            val before = runCatching { file.readText() }.getOrNull() ?: continue
            if (!premiumKey.containsMatchIn(before)) continue
            val lines = before.lines().toMutableList()
            var changed = false
            for (index in lines.indices) {
                val line = lines[index].trim()
                if (!line.startsWith("const-string")) continue
                val literal = line.substringAfter(',', "").trim().trim('"')
                if (!premiumKey.containsMatchIn(literal)) continue
                for (ahead in (index + 1)..minOf(index + 14, lines.lastIndex)) {
                    val probe = lines[ahead].trim()
                    if (probe.startsWith("const/4 v") && probe.endsWith(", 0x0")) {
                        lines[ahead] = lines[ahead].replace(", 0x0", ", 0x1")
                        changed = true
                        notes.add("default of key \"$literal\" seeded to true in ${file.name}")
                        break
                    }
                    if (probe.startsWith("const/16 v") && probe.endsWith(", 0x0")) {
                        lines[ahead] = lines[ahead].replace(", 0x0", ", 0x1")
                        changed = true
                        break
                    }
                    if (probe.startsWith("const-wide/16 v") && probe.endsWith(", 0x0")) {
                        lines[ahead] = lines[ahead].replace(", 0x0", ", 0x1")
                        changed = true
                        break
                    }
                    if (probe.startsWith("const-string")) break
                }
            }
            if (changed) diffs.add(FileDiff(file.relativeTo(root).invariantSeparatorsPath, before, lines.joinToString("\n")))
        }
        if (diffs.isEmpty()) notes.add("No stored premium default found — nothing seeded.")
        PatchPreview("seed-defaults", "Seed premium defaults in prefs / DataStore / DB", diffs, notes = notes)
    }

    /** Rewrites the token/expiry validator so a local token never expires. */
    suspend fun patchTokenValidator(root: File): PatchPreview =
        patcher.forceBooleanMethods(
            root,
            Regex("(isTokenValid|tokenExpired|isExpired|hasExpired|isSessionValid|checkExpiry|isExpiryValid)"),
            true,
            "Make the local token / expiry validator say valid"
        )

    /** Keeps the app on the local state instead of bouncing to the paywall: gates become true. */
    suspend fun fallBackToLocalState(root: File): PatchPreview {
        val gates = patcher.forceBooleanMethods(
            root,
            Regex("(isEntitled|isPaywalled|shouldShowPaywall|canUsePremium|needsPurchase|mustSubscribe|isLocked)"),
            true,
            "Stop the paywall redirect by satisfying the local gate"
        )
        val notes = gates.notes + "Redirect methods (${paywallRedirect.pattern}) are left intact so the route stays consistent."
        return gates.copy(patchId = "local-fallback-${System.currentTimeMillis() % 100000}", notes = notes)
    }

    /** Full entitlement pass: producer, validators, seeds, local fallback. */
    suspend fun fullPass(root: File): PatchPreview {
        val producer = patchProducers(root)
        val token = patchTokenValidator(root)
        val fallback = fallBackToLocalState(root)
        val seeds = seedDefaults(root)
        return PatchPreview(
            patchId = "entitlement-full-${System.currentTimeMillis() % 100000}",
            summary = "Entitlement at source (producer, validator, seeds, local fallback)",
            diffs = producer.diffs + token.diffs + fallback.diffs + seeds.diffs,
            notes = producer.notes + token.notes + fallback.notes + seeds.notes
        )
    }
}
