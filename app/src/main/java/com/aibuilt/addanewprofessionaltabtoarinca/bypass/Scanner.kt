package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ScanResult(
    val findings: List<Finding>,
    val high: List<Finding>,
    val medium: List<Finding>,
    val low: List<Finding>
)

class Scanner(
    private val log: suspend (String) -> Unit,
    private val rulePacks: RulePacks
) {

    private data class Rule(
        val kind: String,
        val title: String,
        val regex: Regex,
        val confidence: Confidence,
        val extensions: Set<String> = setOf("smali", "xml", "json")
    )

    private val rules: List<Rule> = listOf(
        Rule("pinning", "TrustManager override (pinning)", Regex("Ljavax/net/ssl/X509TrustManager;|checkServerTrusted"), Confidence.HIGH),
        Rule("pinning", "OkHttp CertificatePinner", Regex("Lokhttp3/CertificatePinner;"), Confidence.HIGH),
        Rule("pinning", "network_security_config pins", Regex("<pin-set|<pin "), Confidence.HIGH, setOf("xml")),
        Rule("pinning", "TrustKit pinning", Regex("com/datatheorem/trustkit|TrustKit"), Confidence.MEDIUM),
        Rule("pinning", "pinned certificate strings in a native lib", Regex("sha256/[A-Za-z0-9+/=]{20,}"), Confidence.LOW, setOf("txt")),
        Rule("network", "OkHttp client", Regex("Lokhttp3/OkHttpClient;"), Confidence.HIGH),
        Rule("network", "Retrofit interface", Regex("Lretrofit2/Retrofit;|retrofit2/http"), Confidence.HIGH),
        Rule("network", "HttpURLConnection", Regex("Ljava/net/HttpURLConnection;|openConnection"), Confidence.HIGH),
        Rule("network", "Volley", Regex("com/android/volley"), Confidence.HIGH),
        Rule("network", "Ktor client", Regex("io/ktor/client"), Confidence.MEDIUM),
        Rule("network", "Flutter / Dart", Regex("io/flutter/embedding|libflutter.so"), Confidence.MEDIUM),
        Rule("network", "React Native bridge", Regex("com/facebook/react|ReactNativeHost"), Confidence.MEDIUM),
        Rule("endpoint", "Hard-coded API endpoint", Regex("https?://[A-Za-z0-9.-]+\\.[a-z]{2,}[^\"'\\s]*"), Confidence.MEDIUM),
        Rule("auth", "Token / auth validator", Regex("(isLoggedIn|hasValidToken|checkToken|isTokenValid|validateSession|isAuthenticated)"), Confidence.HIGH),
        Rule("auth", "Bearer / OAuth strings", Regex("Bearer |Authorization:|oauth2"), Confidence.LOW),
        Rule("remote_config", "Remote config / feature flag", Regex("(RemoteConfig|featureFlag|getBoolean\\(\"enable|FirebaseRemoteConfig)"), Confidence.MEDIUM),
        Rule("entitlement", "Licence / premium / subscription check", Regex("(isPremium|hasActiveSub|isSubscribed|isPro\\b|plan\\s*==|billingclient|revenuecat|qonversion|adapty|superwall)"), Confidence.HIGH),
        Rule("nsfw", "NSFW / age gate", Regex("(nsfw|isAdult|over18|ageGate|ageVerif|18\\+)", RegexOption.IGNORE_CASE), Confidence.MEDIUM),
        Rule("update", "Force-update logic", Regex("(forceUpdate|isUpdateRequired|needsUpdate|updateRequired|minVersionCode)"), Confidence.HIGH),
        Rule("signature", "Self signature check", Regex("(getSignature|signatures|signingInfo|GET_SIGNATURES|apkSignature)"), Confidence.HIGH),
        Rule("obfuscation", "DexGuard", Regex("DexGuard|com/guardsquare"), Confidence.HIGH),
        Rule("obfuscation", "pairip / licensing VM", Regex("pairip|pairipcore|com/pairip"), Confidence.HIGH),
        Rule("obfuscation", "R8 single-letter rename", Regex("smali/[a-z]{1,2}/[a-z]{1,2}/[a-z]{1,2}\\.smali"), Confidence.LOW, setOf("txt")),
        Rule("webview", "WebView JS bridge", Regex("addJavascriptInterface"), Confidence.HIGH),
        Rule("billing", "Play Billing client", Regex("com/android/billingclient"), Confidence.HIGH),
        Rule("billing", "RevenueCat", Regex("revenuecat|Purchases\\.configure"), Confidence.HIGH)
    )

    suspend fun scan(root: File, maxFindings: Int = 250): ScanResult = withContext(Dispatchers.IO) {
        val extra = rulePacks.activeRules().map {
            Rule(it.kind, it.label, runCatching { Regex(it.needle) }.getOrDefault(Regex(Regex.escape(it.needle))), it.confidence)
        }
        val all = rules + extra
        val findings = ArrayList<Finding>()
        val seen = HashSet<String>()
        if (!root.isDirectory) {
            log("scan: work tree missing")
            return@withContext ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("smali", "xml", "json", "txt", "properties") && it.length() < 400_000 }
            .take(12_000)
            .toList()
        log("scanning ${files.size} file(s)")

        val nativeStrings = StringBuilder()
        root.walkTopDown().filter { it.isFile && it.extension == "so" }.take(30).forEach { lib ->
            runCatching { nativeStrings.append(bytesToStrings(lib.readBytes())) }
        }

        for (file in files) {
            if (findings.size >= maxFindings) break
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            for (rule in all) {
                if (file.extension !in rule.extensions) continue
                val match = rule.regex.find(text) ?: continue
                val key = rule.kind + rule.title
                if (!seen.add(key)) continue
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                findings.add(
                    Finding(
                        id = UUID.randomUUID().toString().take(8),
                        kind = rule.kind,
                        title = rule.title,
                        detail = "${match.value.take(120)} (line $line)",
                        file = rel,
                        confidence = rule.confidence
                    )
                )
            }
        }
        if (nativeStrings.isNotEmpty()) {
            for (rule in all) {
                if (findings.size >= maxFindings) break
                if (rule.extensions.none { it == "txt" }) continue
                val match = rule.regex.find(nativeStrings) ?: continue
                val key = rule.kind + rule.title + "native"
                if (!seen.add(key)) continue
                findings.add(
                    Finding(
                        id = UUID.randomUUID().toString().take(8),
                        kind = rule.kind,
                        title = rule.title,
                        detail = "found in a native .so: ${match.value.take(100)}",
                        file = "lib/**/*.so",
                        confidence = rule.confidence
                    )
                )
            }
        }
        log("scan finished: ${findings.size} finding(s)")
        ScanResult(
            findings = findings,
            high = findings.filter { it.confidence == Confidence.HIGH },
            medium = findings.filter { it.confidence == Confidence.MEDIUM },
            low = findings.filter { it.confidence == Confidence.LOW }
        )
    }

    private fun bytesToStrings(bytes: ByteArray, min: Int = 6): String {
        val sb = StringBuilder()
        val current = StringBuilder()
        for (byte in bytes) {
            val c = byte.toInt() and 0xFF
            if (c in 32..126) current.append(c.toChar()) else {
                if (current.length >= min) sb.append(current).append('\n')
                current.setLength(0)
            }
        }
        return sb.toString()
    }
}
