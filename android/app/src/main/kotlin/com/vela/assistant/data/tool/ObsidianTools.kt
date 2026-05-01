package com.vela.assistant.data.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Read/write access to the user's Obsidian vault at /sdcard/Obsidian.
//
// On Android 11+ this needs MANAGE_EXTERNAL_STORAGE — scoped storage's READ_EXTERNAL_STORAGE
// only grants access to MediaStore-indexed files (images/audio/video), not the .md files in
// an Obsidian vault. The user grants it from a Settings page reachable via the SettingsSheet
// row; without it, every tool here returns a structured permission error the model can
// verbalize.
@Singleton
class ObsidianTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Lists Obsidian notes (relative paths). Call before reading to discover what exists.")
    fun listObsidianNotes(
        @ToolParam(description = "Subfolder, or empty for whole vault.") folder: String = "",
    ): String {
        Timber.i("TOOL listObsidianNotes(folder='$folder')")
        val vault = vaultDir() ?: return "Obsidian vault not found at /sdcard/Obsidian. Tell the user to create that folder or move their vault there."
        if (!hasStorageAccess()) return permissionMessage()
        val target = resolveInsideVault(vault, folder)
            ?: return "Folder '$folder' is outside the vault and was rejected."
        if (!target.exists()) return "Folder '$folder' does not exist in the vault."
        if (!target.isDirectory) return "'$folder' is a file, not a folder."

        val notes = mutableListOf<String>()
        target.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .take(MAX_LIST_ENTRIES)
            .forEach { notes += vault.toRelativeString(it).replace(File.separatorChar, '/') }

        return when {
            notes.isEmpty() -> "No markdown notes found in '${if (folder.isEmpty()) "(vault root)" else folder}'."
            notes.size == MAX_LIST_ENTRIES -> "Listing capped at $MAX_LIST_ENTRIES entries:\n" + notes.joinToString("\n")
            else -> notes.joinToString("\n")
        }
    }

    @Tool(description = "Reads an Obsidian note by relative path.")
    fun readObsidianNote(
        @ToolParam(description = "Relative .md path, e.g. 'Daily/2026-05-01.md'.") path: String,
    ): String {
        Timber.i("TOOL readObsidianNote(path='$path')")
        val vault = vaultDir() ?: return "Obsidian vault not found at /sdcard/Obsidian."
        if (!hasStorageAccess()) return permissionMessage()
        val file = resolveInsideVault(vault, path)
            ?: return "Path '$path' is outside the vault and was rejected."
        if (!file.exists()) return "Note '$path' not found."
        if (!file.isFile) return "'$path' is a folder, not a note."

        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return "Could not read '$path': ${it.message}" }
        return if (text.length > MAX_READ_CHARS) {
            text.take(MAX_READ_CHARS) + "\n\n[truncated — note is ${text.length} characters, showing first $MAX_READ_CHARS]"
        } else {
            text
        }
    }

    @Tool(description = "Searches Obsidian notes by filename and content. Returns up to 10 hits with snippets.")
    fun searchObsidianNotes(
        @ToolParam(description = "Case-insensitive query.") query: String,
    ): String {
        Timber.i("TOOL searchObsidianNotes(query='$query')")
        if (query.isBlank()) return "Error: search query is empty."
        val vault = vaultDir() ?: return "Obsidian vault not found at /sdcard/Obsidian."
        if (!hasStorageAccess()) return permissionMessage()

        val q = query.trim()
        val hits = mutableListOf<String>()
        var scanned = 0
        vault.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .takeWhile { scanned < MAX_SEARCH_FILES && hits.size < MAX_SEARCH_HITS }
            .forEach { file ->
                scanned++
                val rel = vault.toRelativeString(file).replace(File.separatorChar, '/')
                if (file.nameWithoutExtension.contains(q, ignoreCase = true)) {
                    hits += "• $rel — (filename match)"
                    return@forEach
                }
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                val idx = text.indexOf(q, ignoreCase = true)
                if (idx >= 0) {
                    val from = (idx - 40).coerceAtLeast(0)
                    val to = (idx + q.length + 80).coerceAtMost(text.length)
                    val snippet = text.substring(from, to).replace('\n', ' ').trim()
                    hits += "• $rel — …$snippet…"
                }
            }

        return if (hits.isEmpty()) {
            "No Obsidian notes matched '$query' (scanned $scanned files)."
        } else {
            "Found ${hits.size} match(es):\n" + hits.joinToString("\n")
        }
    }

    @Tool(description = "Writes (overwrites) an Obsidian note. Creates folders as needed. Use appendObsidianNote to add to existing content.")
    fun writeObsidianNote(
        @ToolParam(description = "Relative path; .md added if missing.") path: String,
        @ToolParam(description = "Markdown body.") content: String,
    ): String {
        Timber.i("TOOL writeObsidianNote(path='$path', contentLen=${content.length})")
        val vault = ensureVaultDir() ?: return "Could not create the Obsidian vault folder at /sdcard/Obsidian."
        if (!hasStorageAccess()) return permissionMessage()
        val withExt = if (path.endsWith(".md", ignoreCase = true)) path else "$path.md"
        val file = resolveInsideVault(vault, withExt)
            ?: return "Path '$path' is outside the vault and was rejected."

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            "Saved note '${vault.toRelativeString(file).replace(File.separatorChar, '/')}' (${content.length} chars)."
        } catch (e: Exception) {
            Timber.w(e, "writeObsidianNote failed")
            "Could not write '$path': ${e.message}"
        }
    }

    @Tool(description = "Appends to an Obsidian note (creates if missing). Useful for journals.")
    fun appendObsidianNote(
        @ToolParam(description = "Relative path; .md added if missing.") path: String,
        @ToolParam(description = "Markdown to append.") content: String,
    ): String {
        Timber.i("TOOL appendObsidianNote(path='$path', contentLen=${content.length})")
        val vault = ensureVaultDir() ?: return "Could not create the Obsidian vault folder at /sdcard/Obsidian."
        if (!hasStorageAccess()) return permissionMessage()
        val withExt = if (path.endsWith(".md", ignoreCase = true)) path else "$path.md"
        val file = resolveInsideVault(vault, withExt)
            ?: return "Path '$path' is outside the vault and was rejected."

        return try {
            file.parentFile?.mkdirs()
            val needsSeparator = file.exists() && file.length() > 0 && !file.readText(Charsets.UTF_8).endsWith("\n\n")
            val prefix = if (needsSeparator) "\n\n" else ""
            file.appendText(prefix + content, Charsets.UTF_8)
            "Appended ${content.length} chars to '${vault.toRelativeString(file).replace(File.separatorChar, '/')}'."
        } catch (e: Exception) {
            Timber.w(e, "appendObsidianNote failed")
            "Could not append to '$path': ${e.message}"
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────────────────

    private fun vaultDir(): File? {
        val vault = File(Environment.getExternalStorageDirectory(), "Obsidian")
        return if (vault.isDirectory) vault else null
    }

    private fun ensureVaultDir(): File? {
        val vault = File(Environment.getExternalStorageDirectory(), "Obsidian")
        if (vault.isDirectory) return vault
        return runCatching { if (vault.mkdirs()) vault else null }.getOrNull()
    }

    // Path-traversal guard. The model can hand us any string; canonicalize and verify the
    // resolved file still lives under the vault root.
    private fun resolveInsideVault(vault: File, relative: String): File? {
        val cleaned = relative.trimStart('/', '\\').ifEmpty { "." }
        val candidate = File(vault, cleaned)
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val canonicalVault = runCatching { vault.canonicalFile }.getOrNull() ?: return null
        if (canonicalCandidate != canonicalVault &&
            !canonicalCandidate.path.startsWith(canonicalVault.path + File.separator)
        ) return null
        return canonicalCandidate
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun permissionMessage(): String =
        "Obsidian access not granted. Tell the user to enable 'All files access' for Vela in Settings → Apps → Vela → Permissions, or via the Obsidian row in Vela's Settings sheet."

    companion object {
        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_READ_CHARS = 64_000
        private const val MAX_SEARCH_FILES = 500
        private const val MAX_SEARCH_HITS = 10
    }
}
