package com.poke.discordsplit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * Fallback token sources when the okhttp hooks haven't seen an authenticated
 * request yet (e.g. pane opened right after launch). Everything is read from
 * inside the hooked Discord process, so we can use its own data dir directly.
 *
 * Token formats: "mfa.<84 chars>" (2FA accounts) or "<24-28>.<6-7>.<27+>"
 * (standard user tokens).
 */
object TokenStore {

    private val tokenRegex =
        Regex("mfa\\.[\\w-]{20,}|[\\w-]{20,30}\\.[\\w-]{4,10}\\.[\\w-]{20,}")

    private const val MAX_SCAN_BYTES = 4 * 1024 * 1024

    fun findToken(context: Context): String? {
        DiscordApiTracker.authToken?.let { return it }
        val dataDir = File(context.applicationInfo.dataDir)
        scanSharedPrefs(dataDir)?.let { return it }
        scanAsyncStorage(dataDir)?.let { return it }
        scanBlobFiles(dataDir)?.let { return it }
        return null
    }

    private fun scanSharedPrefs(dataDir: File): String? {
        val dir = File(dataDir, "shared_prefs")
        dir.listFiles()?.forEach { file ->
            runCatching {
                tokenRegex.find(file.readText())?.value?.let { return it }
            }
        }
        return null
    }

    private fun scanAsyncStorage(dataDir: File): String? {
        val dbDir = File(dataDir, "databases")
        dbDir.listFiles()?.forEach { file ->
            if (file.isDirectory || file.name.endsWith("-journal") || file.name.endsWith("-wal")) {
                return@forEach
            }
            runCatching {
                SQLiteDatabase.openDatabase(
                    file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='catalystLocalStorage'",
                        null,
                    ).use { c -> if (!c.moveToFirst()) return@use }
                    db.rawQuery(
                        "SELECT value FROM catalystLocalStorage WHERE key LIKE '%token%'",
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            tokenRegex.find(c.getString(0) ?: "")?.value?.let { return it }
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("[DiscordSplitView] storage scan skipped ${file.name}: $it")
            }
        }
        return null
    }

    private fun scanBlobFiles(dataDir: File): String? {
        // MMKV and friends keep the token as plain bytes inside otherwise binary
        // files, so a byte-level regex works without parsing the format.
        val roots = listOf(File(dataDir, "files"), File(dataDir, "shared_prefs"))
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.length() < MAX_SCAN_BYTES }
                .forEach { file ->
                    runCatching {
                        val text = String(file.readBytes(), Charsets.ISO_8859_1)
                        tokenRegex.find(text)?.value?.let { return it }
                    }
                }
        }
        return null
    }
}
