package dev.brantley.agentbridge.safety

enum class ConfirmMode {
    OFF,
    WRITES,
    EVERY,
    ;

    val wire: String
        get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ConfirmMode =
            when (value.lowercase()) {
                "off" -> OFF
                "every" -> EVERY
                else -> WRITES
            }
    }
}

data class SafetyConfig(
    val confirmMode: ConfirmMode = ConfirmMode.WRITES,
    val idleTimeoutMin: Int = 10,
    val sessionTimeoutMin: Int = 60,
    val disarmOnLock: Boolean = true,
    val screenshotsEnabled: Boolean = false,
    val removedDefaults: Set<String> = emptySet(),
    val extraDeny: Set<String> = emptySet(),
    /**
     * Local calendar day (yyyy-MM-dd) the owner approved "for today".
     * While this equals today, no confirm prompts appear. It is stored in
     * DataStore, so it survives re-arm, a new session and an app restart.
     * It stops working at local midnight; disarm does NOT clear it — only
     * Unpair or "Revoke today's approval" in Settings does.
     */
    val dailyGrantDate: String = "",
    /**
     * "Stay armed until I disarm". When true, the idle timeout and session cap do not
     * end the session. Stop, the tile, the kill switch, disarm-on-lock, confirm prompts,
     * the denylist, redaction, and per-action timeouts all still apply.
     */
    val stayArmed: Boolean = false,
)

object Policy {
    const val SELF = "dev.brantley.agentbridge"
    const val SETTINGS = "com.android.settings"
    val WRITES = setOf("tap", "type", "swipe", "scroll", "key", "launch", "volume")

    val STARTER_DENY =
        setOf(
            SELF,
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.chase.sig.android",
            "com.wf.wellsfargomobile",
            "com.infonow.bofa",
            "com.usaa.mobile.android.usaa",
            // Payment / banking apps — never launchable, and the agent cannot act
            // while one is in the foreground. Pakistani wallets/banks below were
            // confirmed installed on the owner's phone (2026-11-24, list_apps):
            "com.nayapay.app", // NayaPay
            "com.sadapay.app", // SadaPay
            "com.techlogix.mobilinkcustomer", // JazzCash
            "pk.com.telenor.phoenix", // easypaisa
            "app.com.brd", // UBL Digital
            "team.opay.pay.pakistan", // OPay
            "com.raqamidigital.cbt", // Raqami digital bank
            "com.digibop.mobile", // digiBOP (Bank of Punjab digital)
            // Defensive entries for payment apps not installed yet:
            "com.samsung.android.spay", // Samsung Pay
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "invo8.meezan.mb", // Meezan Mobile Banking
            "com.ofss.tx.meezan", // Meezan Mobile App 2.0 (legacy)
            // Password vault on this phone:
            "com.samsung.android.samsungpass", // Samsung Pass
            // Gallery — never open (owner order 2026-11-24):
            "com.sec.android.gallery3d", // Samsung Gallery
        )

    /** Local calendar day (yyyy-MM-dd) for an epoch-ms instant. The daily approval is keyed on this. */
    fun dayOf(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    /** Epoch-ms of the next local midnight after [epochMs] — when a daily approval stops working. */
    fun endOfDayMs(epochMs: Long): Long =
        java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun isSettings(packageName: String): Boolean =
        packageName == SETTINGS || packageName.startsWith("$SETTINGS.")

    fun isFloor(packageName: String): Boolean {
        if (packageName == SELF) return true
        val managed = packageName.startsWith("com.android.") || packageName.startsWith("com.google.android.")
        if (!managed) return false
        return packageName.contains("permissioncontroller") || packageName.contains("packageinstaller")
    }

    fun isDenied(packageName: String, config: SafetyConfig): Boolean {
        if (packageName.isBlank()) return false
        if (isFloor(packageName)) return true
        if (packageName in config.extraDeny) return true
        if (packageName in config.removedDefaults) return false
        return packageName in STARTER_DENY
    }

    fun displayDeny(config: SafetyConfig): List<Pair<String, Boolean>> {
        val floor = STARTER_DENY.filter(::isFloor)
        val editable = ((STARTER_DENY - floor.toSet()) - config.removedDefaults) + config.extraDeny
        return (floor.map { it to true } + editable.map { it to false })
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

    fun needsConfirm(
        method: String,
        foreground: String,
        launchTarget: String?,
        config: SafetyConfig,
        grantUntil: Long,
        now: Long,
        dailyGranted: Boolean = false,
    ): Boolean {
        if (method == "status") return false
        val write = method in WRITES
        val settings = isSettings(foreground) || (launchTarget != null && isSettings(launchTarget))
        if (settings) return true
        val granted = write && (now < grantUntil || dailyGranted)
        return when (config.confirmMode) {
            ConfirmMode.OFF -> false
            ConfirmMode.EVERY -> !granted
            ConfirmMode.WRITES -> write && !granted
        }
    }
}
