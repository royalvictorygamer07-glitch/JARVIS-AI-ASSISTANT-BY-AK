package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.util.Locale

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

data class AppLaunchResult(
    val success: Boolean,
    val appName: String,
    val packageName: String? = null,
    val message: String
)

class AppManagerHelper(private val context: Context) {

    companion object {
        private const val TAG = "AppManagerHelper"
    }

    private val packageManager: PackageManager = context.packageManager
    private var cachedApps: List<InstalledAppInfo> = emptyList()
    private var lastScanTime: Long = 0

    init {
        scanInstalledApps()
    }

    fun scanInstalledApps(): List<InstalledAppInfo> {
        val now = System.currentTimeMillis()
        if (cachedApps.isNotEmpty() && (now - lastScanTime < 60_000)) {
            return cachedApps
        }

        val appList = mutableListOf<InstalledAppInfo>()
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)

            val seenPackages = mutableSetOf<String>()
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg in seenPackages) continue
                seenPackages.add(pkg)

                val label = try {
                    info.loadLabel(packageManager).toString().trim()
                } catch (e: Exception) {
                    pkg
                }

                val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                appList.add(InstalledAppInfo(label, pkg, isSystem))
            }

            if (appList.isEmpty()) {
                val allInstalled = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                for (appInfo in allInstalled) {
                    val pkg = appInfo.packageName
                    if (pkg in seenPackages) continue
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        seenPackages.add(pkg)
                        val label = try {
                            packageManager.getApplicationLabel(appInfo).toString().trim()
                        } catch (e: Exception) {
                            pkg
                        }
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        appList.add(InstalledAppInfo(label, pkg, isSystem))
                    }
                }
            }

            cachedApps = appList.sortedBy { it.label.lowercase(Locale.ROOT) }
            lastScanTime = now
            Log.d(TAG, "Detected ${cachedApps.size} installed apps on device.")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning apps: ${e.message}", e)
        }
        return cachedApps
    }

    fun findAndLaunchApp(query: String, isHindi: Boolean = true): AppLaunchResult {
        val cleanQuery = cleanAppName(query)
        if (cleanQuery.isBlank()) {
            return AppLaunchResult(
                success = false,
                appName = query,
                message = if (isHindi) "Kripya app ka naam batayein jise open karna hai." else "Please specify which app to open."
            )
        }

        handleSpecialSystemIntents(cleanQuery, isHindi)?.let { return it }

        val apps = scanInstalledApps()
        if (apps.isEmpty()) {
            val fallbackPackage = getKnownPackageAlias(cleanQuery)
            if (fallbackPackage != null) {
                return launchPackageDirectly(fallbackPackage, cleanQuery, isHindi)
            }
        }

        // 1. Check exact match
        var matchedApp = apps.firstOrNull {
            it.label.equals(cleanQuery, ignoreCase = true) ||
            cleanAppName(it.label).equals(cleanQuery, ignoreCase = true)
        }

        // 2. Check alias mapping and direct package launch
        val alias = getKnownPackageAlias(cleanQuery)
        if (matchedApp == null && alias != null) {
            matchedApp = apps.firstOrNull { it.packageName.equals(alias, ignoreCase = true) }
            if (matchedApp == null) {
                val directResult = tryDirectPackageLaunchOrSpecial(alias, cleanQuery, isHindi)
                if (directResult != null) return directResult
            }
        }

        // 3. Check contains / substring match
        if (matchedApp == null) {
            matchedApp = apps.firstOrNull {
                val cl = cleanAppName(it.label)
                cl.isNotEmpty() && (cl.contains(cleanQuery) || cleanQuery.contains(cl))
            }
        }

        // 4. Fuzzy similarity match
        if (matchedApp == null) {
            val queryWords = cleanQuery.split(" ").filter { it.length > 2 }
            if (queryWords.isNotEmpty()) {
                matchedApp = apps.maxByOrNull { app ->
                    val appWords = cleanAppName(app.label).split(" ")
                    queryWords.count { qw -> appWords.any { aw -> aw.contains(qw) || qw.contains(aw) } }
                }?.takeIf { app ->
                    val appWords = cleanAppName(app.label).split(" ")
                    queryWords.any { qw -> appWords.any { aw -> aw.contains(qw) || qw.contains(aw) } }
                }
            }
        }

        if (matchedApp != null) {
            return launchPackageDirectly(matchedApp.packageName, matchedApp.label, isHindi)
        }

        if (alias != null) {
            val directResult = tryDirectPackageLaunchOrSpecial(alias, cleanQuery, isHindi)
            if (directResult != null) return directResult
        }

        // Check if accessible screen can click it
        val screenService = JarvisAccessibilityService.instance
        if (screenService != null) {
            val clicked = screenService.clickByText(cleanQuery)
            if (clicked) {
                return AppLaunchResult(
                    success = true,
                    appName = cleanQuery,
                    message = if (isHindi) {
                        "Screen par '${cleanQuery}' par click kar diya hai."
                    } else {
                        "Found and clicked '${cleanQuery}' on screen."
                    }
                )
            }
        }

        return trySearchOnPlayStore(cleanQuery, isHindi)
    }

    private fun launchPackageDirectly(pkg: String, label: String, isHindi: Boolean): AppLaunchResult {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                context.startActivity(intent)
                AppLaunchResult(
                    success = true,
                    appName = label,
                    packageName = pkg,
                    message = if (isHindi) {
                        "$label app open kar diya hai."
                    } else {
                        "Launched $label."
                    }
                )
            } else {
                trySearchOnPlayStore(label, isHindi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $label: ${e.message}")
            AppLaunchResult(
                success = false,
                appName = label,
                packageName = pkg,
                message = if (isHindi) "App $label open karne me dikkat aayi: ${e.localizedMessage}" else "Could not open $label: ${e.localizedMessage}"
            )
        }
    }

    private fun handleSpecialSystemIntents(cleanQuery: String, isHindi: Boolean): AppLaunchResult? {
        when {
            "camera" in cleanQuery -> {
                return try {
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "Camera", null, if (isHindi) "Camera open kar diya gaya hai." else "Camera opened.")
                } catch (e: Exception) { null }
            }
            "setting" in cleanQuery -> {
                return try {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "Settings", null, if (isHindi) "Settings open kar di gayi hai." else "Settings opened.")
                } catch (e: Exception) { null }
            }
            "dialer" in cleanQuery || "dial pad" in cleanQuery || "call" == cleanQuery || "phone" == cleanQuery -> {
                return try {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "Phone Dialer", null, if (isHindi) "Phone dialer open kar diya hai." else "Phone dialer opened.")
                } catch (e: Exception) { null }
            }
            "play store" in cleanQuery || "playstore" in cleanQuery -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "Google Play Store", null, if (isHindi) "Google Play Store open kar diya hai." else "Play Store opened.")
                } catch (e: Exception) { null }
            }
        }
        return null
    }

    private fun tryDirectPackageLaunchOrSpecial(alias: String, query: String, isHindi: Boolean): AppLaunchResult? {
        val launchIntent = packageManager.getLaunchIntentForPackage(alias)
        if (launchIntent != null) {
            return launchPackageDirectly(alias, query, isHindi)
        }
        // Web fallback for known giants
        when {
            alias.contains("youtube") -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "YouTube", alias, if (isHindi) "YouTube open kar diya hai." else "Opened YouTube.")
                } catch (e: Exception) { null }
            }
            alias.contains("chrome") -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "Browser", alias, if (isHindi) "Browser open kar diya hai." else "Opened Browser.")
                } catch (e: Exception) { null }
            }
            alias.contains("whatsapp") -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppLaunchResult(true, "WhatsApp", alias, if (isHindi) "WhatsApp open kar diya hai." else "Opened WhatsApp.")
                } catch (e: Exception) { null }
            }
        }
        return null
    }

    private fun trySearchOnPlayStore(cleanQuery: String, isHindi: Boolean): AppLaunchResult {
        return try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$cleanQuery")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            AppLaunchResult(
                success = true,
                appName = cleanQuery,
                message = if (isHindi) {
                    "'$cleanQuery' phone me install nahi mila, Play Store par search kar diya hai."
                } else {
                    "'$cleanQuery' is not installed. Searching for it on Google Play Store."
                }
            )
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$cleanQuery+app")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            AppLaunchResult(
                success = true,
                appName = cleanQuery,
                message = if (isHindi) {
                    "'$cleanQuery' install nahi hai. Web par dhundh raha hoon."
                } else {
                    "'$cleanQuery' not installed. Searching web."
                }
            )
        }
    }

    private fun cleanAppName(name: String): String {
        var s = name.lowercase(Locale.ROOT).trim()
        val prefixes = listOf(
            "can you please open ", "can you open ", "please open ", "open ", "launch ", "start ", "run ",
            "kripya open karein ", "open kar do ", "open karo ", "chala do ", "chalao ", "chalu karo ",
            "chalu kar do ", "khol do ", "kholo ", "kholna hai "
        )
        for (p in prefixes) {
            if (s.startsWith(p)) {
                s = s.substring(p.length).trim()
                break
            }
        }
        val suffixes = listOf(
            " open kar do", " open karo", " khol do", " kholo", " chala do", " chalao", " chalu karo",
            " chalu kar do", " app open", " app kholo", " application open", " ko open karo",
            " ko kholo", " chalu", " laga do", " chalao please", " open", " please", " sir", " bhai",
            " kar do", " karo", " karna hai", " kholna"
        )
        for (sfx in suffixes) {
            if (s.endsWith(sfx)) {
                s = s.removeSuffix(sfx).trim()
                break
            }
        }
        s = s.replace(Regex("\\b(app|application|please|sir|bhai|mera|meri|mujhe|ko|ek)\\b"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun getKnownPackageAlias(query: String): String? {
        val q = query.replace(" ", "")
        return when {
            q.contains("youtube") || q == "yt" -> "com.google.android.youtube"
            q.contains("whatsapp") || q == "wa" -> "com.whatsapp"
            q.contains("instagram") || q.contains("insta") || q == "ig" -> "com.instagram.android"
            q.contains("telegram") || q == "tg" -> "org.telegram.messenger"
            q.contains("freefire") || q.contains("ff") -> "com.dts.freefireth"
            q.contains("freefiremax") -> "com.dts.freefiremax"
            q.contains("bgmi") || q.contains("pubg") -> "com.pubg.imobile"
            q.contains("facebook") || q == "fb" -> "com.facebook.katana"
            q.contains("chrome") -> "com.android.chrome"
            q.contains("snapchat") || q == "snap" -> "com.snapchat.android"
            q.contains("spotify") -> "com.spotify.music"
            q.contains("calculator") -> "com.google.android.calculator"
            q.contains("clock") -> "com.google.android.deskclock"
            q.contains("gallery") || q.contains("photos") -> "com.google.android.apps.photos"
            q.contains("files") || q.contains("filemanager") -> "com.google.android.apps.nbu.files"
            q.contains("paytm") -> "net.one97.paytm"
            q.contains("phonepe") -> "com.phonepe.app"
            q.contains("gpay") || q.contains("googlepay") -> "com.google.android.apps.nbu.paisa.user"
            else -> null
        }
    }
}
