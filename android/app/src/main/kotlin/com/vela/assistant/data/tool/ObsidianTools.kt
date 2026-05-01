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
// Two non-obvious things:
//
// 1. Sanitization. litertlm's automatic tool-result encoder SIGABRT'd whenever our return
//    contained non-BMP code points (emoji surrogate pairs). sanitizeForModel() replaces those
//    with '_' before we hand the string back across JNI; the resolver below does the inverse
//    on lookup so a path the model echoes back without the emoji still finds the real file.
//
// 2. Index-based reads. Gemma 4 E2B is small enough that it consistently mangled long
//    relative paths between turns (e.g. echoed back '../../..'). Search/list responses now
//    number their results "[1] foo.md / [2] bar.md ...". The read/append/write tools accept
//    either a literal path or an "[N]" index that's looked up in a small per-instance cache
//    of the last results. The model only has to remember a single digit.
//
// Hidden directories like .stversions, .trash, .git, .obsidian are walked over — Syncthing's
// .stversions can hold thousands of duplicate notes that drown out real results.
@Singleton
class ObsidianTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    // Cache of the most recent search/list result, keyed positionally. Read tools resolve
    // "[N]" arguments against this. Volatile because tool calls can in principle arrive on
    // different threads from litertlm's worker pool, even though practically they're
    // sequential within a single conversation turn.
    @Volatile private var lastResults: List<String> = emptyList()

    @Tool(description = "Lists Obsidian notes as a numbered index. The model should remember the [N] indices to pass to readObsidianNote later.")
    fun listObsidianNotes(
        @ToolParam(description = "Subfolder, or empty for the whole vault.") folder: String = "",
    ): String = safe("listObsidianNotes") {
        Timber.i("TOOL listObsidianNotes(folder='$folder')")
        val vault = vaultDir() ?: return@safe "Obsidian vault not found at /sdcard/Obsidian. Tell the user to create that folder or move their vault there."
        if (!hasStorageAccess()) return@safe permissionMessage()
        val target = resolveInsideVault(vault, folder)
            ?: return@safe "error: '$folder' is outside the vault."
        if (!target.exists()) return@safe "error: folder '$folder' does not exist."
        if (!target.isDirectory) return@safe "error: '$folder' is a file, not a folder."

        val paths = walkVaultMarkdown(target, vault)
            .take(MAX_LIST_ENTRIES)
            .map { sanitizeForModel(vault.toRelativeString(it).replace(File.separatorChar, '/')) }
            .toList()
        recordResults(paths)

        if (paths.isEmpty()) return@safe "count: 0"
        val capped = if (paths.size == MAX_LIST_ENTRIES) " (capped at $MAX_LIST_ENTRIES)" else ""
        sanitizeForModel("count: ${paths.size}$capped\n" + numberedLines(paths)).take(MAX_RESPONSE_CHARS)
    }

    @Tool(description = "Reads an Obsidian note. Pass either an [N] index from a prior listObsidianNotes/searchObsidianNotes result, or a literal relative path; never use '..' navigation.")
    fun readObsidianNote(
        @ToolParam(description = "Either '[N]' (e.g. '[1]') referring to a prior list/search result, or an exact relative path verbatim.") path: String,
    ): String = safe("readObsidianNote") {
        Timber.i("TOOL readObsidianNote(path='$path')")
        val vault = vaultDir() ?: return@safe "Obsidian vault not found at /sdcard/Obsidian."
        if (!hasStorageAccess()) return@safe permissionMessage()
        val resolvedPath = resolveIndexOrPath(path)
            ?: return@safe "error: '$path' is not a known [N] index. Call searchObsidianNotes or listObsidianNotes first."
        Timber.i("readObsidianNote resolved '$path' -> '$resolvedPath'")
        if (containsParentTraversal(resolvedPath)) {
            return@safe "error: path uses '..' navigation. Use an [N] index from a prior list/search result instead."
        }
        val file = resolveInsideVault(vault, resolvedPath)
            ?: return@safe "error: '$resolvedPath' is outside the vault."
        if (!file.exists()) return@safe "error: note '$resolvedPath' not found."
        if (!file.isFile) return@safe "error: '$resolvedPath' is a folder, not a note."
        if (file.length() > MAX_FILE_BYTES) {
            return@safe "error: note is ${file.length() / 1_000_000} MB; refusing to read files larger than ${MAX_FILE_BYTES / 1_000_000} MB."
        }

        val text = readBoundedUtf8(file, MAX_READ_BYTES)
        val withTrailer = if (file.length() > MAX_READ_BYTES) {
            text + "\n\n[truncated; note is ${file.length()} bytes, showing first $MAX_READ_BYTES]"
        } else {
            text
        }
        sanitizeForModel(withTrailer).take(MAX_RESPONSE_CHARS)
    }

    @Tool(description = "Searches Obsidian notes by filename AND content. Returns matches as a numbered index with a short snippet from each note's body — usually enough to answer 'what does my note about X say?' without a separate read. Use readObsidianNote with [N] only if the user asks for a full note.")
    fun searchObsidianNotes(
        @ToolParam(description = "Case-insensitive query matched against filenames and note bodies.") query: String,
    ): String = safe("searchObsidianNotes") {
        Timber.i("TOOL searchObsidianNotes(query='$query')")
        if (query.isBlank()) return@safe "error: query is empty."
        val vault = vaultDir() ?: return@safe "Obsidian vault not found at /sdcard/Obsidian."
        if (!hasStorageAccess()) return@safe permissionMessage()

        val q = query.trim()
        // Scan ALL eligible files (up to MAX_SEARCH_FILES) before truncating, so a filename
        // match deeper in the walk (e.g. '📋 PORTFOLIO IGNACIO.TECH.md') can still beat earlier
        // content-only matches when ranking. Stopping at hits.size >= MAX_SEARCH_HITS would
        // starve out the most relevant file when content-only matches happen to come first
        // in walk order.
        val nameHits = mutableListOf<Hit>()
        val contentHits = mutableListOf<Hit>()
        var scanned = 0
        var skippedTooLarge = 0
        for (file in walkVaultMarkdown(vault, vault)) {
            if (scanned >= MAX_SEARCH_FILES) break
            scanned++

            if (file.length() > MAX_FILE_BYTES) {
                skippedTooLarge++
                continue
            }
            val nameMatch = file.nameWithoutExtension.contains(q, ignoreCase = true)
            // Skip body read if name already matched AND we already have enough name hits to
            // satisfy MAX_SEARCH_HITS — saves the per-file 16 KB head-read on the long tail.
            val canSkipBodyRead = nameMatch && nameHits.size >= MAX_SEARCH_HITS
            val text = if (canSkipBodyRead) "" else runCatching { readBoundedUtf8(file, MAX_SEARCH_READ_BYTES) }.getOrNull() ?: ""
            val contentIdx = if (nameMatch) -1 else text.indexOf(q, ignoreCase = true)
            if (!nameMatch && contentIdx < 0) continue

            // Internal: full sanitized relative path (used for [N] -> readObsidianNote lookup).
            // Display: just the filename without extension — keeps the model's verbalization
            // tidy and stops Gemma 4 E2B from substituting long paths with '../..' tokens.
            val fullPath = sanitizeForModel(relativeStringFromVault(vault, file))
            val displayName = sanitizeForModel(file.nameWithoutExtension)
            if (nameMatch) {
                nameHits += Hit(fullPath, displayName, snippetForNameMatch(text))
            } else {
                contentHits += Hit(fullPath, displayName, snippetAround(text, contentIdx, q.length))
            }
        }
        // Filename matches first, then content matches — same overall cap.
        val ordered = (nameHits + contentHits).take(MAX_SEARCH_HITS)
        Timber.i("TOOL searchObsidianNotes: scanned=$scanned hits=${ordered.size} (name=${nameHits.size} content=${contentHits.size})")
        recordResults(ordered.map { it.path })

        if (ordered.isEmpty()) {
            val tail = if (skippedTooLarge > 0) " (skipped $skippedTooLarge files > ${MAX_FILE_BYTES / 1_000_000} MB)" else ""
            return@safe "matches: 0 (scanned $scanned$tail)"
        }
        val body = buildString {
            append("matches: ${ordered.size}\n")
            ordered.forEachIndexed { i, h ->
                append("[${i + 1}] ").append(h.displayName).append('\n')
                if (h.snippet.isNotBlank()) append("    ").append(h.snippet).append('\n')
            }
        }
        sanitizeForModel(body).take(MAX_RESPONSE_CHARS)
    }

    private data class Hit(val path: String, val displayName: String, val snippet: String)

    private fun snippetForNameMatch(text: String): String {
        // Filename matched — show the note's opening so the model has *some* body context.
        if (text.isBlank()) return ""
        return text.take(SNIPPET_LEN).replace('\n', ' ').trim()
    }

    private fun snippetAround(text: String, idx: Int, qLen: Int): String {
        val padBefore = SNIPPET_LEN / 4
        val padAfter = SNIPPET_LEN - padBefore - qLen
        val from = (idx - padBefore).coerceAtLeast(0)
        val to = (idx + qLen + padAfter).coerceAtMost(text.length)
        val raw = text.substring(from, to).replace('\n', ' ').trim()
        return if (from > 0) "…$raw" else raw + if (to < text.length) "…" else ""
    }

    @Tool(description = "Writes (overwrites) an Obsidian note. Path is a literal relative path; .md is added if missing. Use appendObsidianNote to add to existing content instead.")
    fun writeObsidianNote(
        @ToolParam(description = "Relative path; .md added if missing. (No '[N]' indices for write — invent a new path.)") path: String,
        @ToolParam(description = "Markdown body.") content: String,
    ): String = safe("writeObsidianNote") {
        Timber.i("TOOL writeObsidianNote(path='$path', contentLen=${content.length})")
        val vault = ensureVaultDir() ?: return@safe "Could not create the Obsidian vault folder at /sdcard/Obsidian."
        if (!hasStorageAccess()) return@safe permissionMessage()
        if (containsParentTraversal(path)) return@safe "error: path uses '..' navigation."
        val withExt = if (path.endsWith(".md", ignoreCase = true)) path else "$path.md"
        val file = resolveInsideVault(vault, withExt)
            ?: return@safe "error: '$path' is outside the vault."

        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        val rel = relativeStringFromVault(vault, file)
        sanitizeForModel("ok: wrote $rel (${content.length} chars)")
    }

    @Tool(description = "Appends to an Obsidian note (creates if missing). Useful for journals.")
    fun appendObsidianNote(
        @ToolParam(description = "Either '[N]' (from prior list/search) or a literal relative path; .md added if missing.") path: String,
        @ToolParam(description = "Markdown to append.") content: String,
    ): String = safe("appendObsidianNote") {
        Timber.i("TOOL appendObsidianNote(path='$path', contentLen=${content.length})")
        val vault = ensureVaultDir() ?: return@safe "Could not create the Obsidian vault folder at /sdcard/Obsidian."
        if (!hasStorageAccess()) return@safe permissionMessage()
        val resolvedPath = resolveIndexOrPath(path)
            ?: return@safe "error: '$path' is not a known [N] index."
        if (containsParentTraversal(resolvedPath)) return@safe "error: path uses '..' navigation."
        val withExt = if (resolvedPath.endsWith(".md", ignoreCase = true)) resolvedPath else "$resolvedPath.md"
        val file = resolveInsideVault(vault, withExt)
            ?: return@safe "error: '$resolvedPath' is outside the vault."
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            return@safe "error: existing note is ${file.length() / 1_000_000} MB; refusing to append to files larger than ${MAX_FILE_BYTES / 1_000_000} MB."
        }

        file.parentFile?.mkdirs()
        val needsSeparator = file.exists() && file.length() > 0 &&
            !readBoundedUtf8FromTail(file, peekBytes = 8_192).endsWith("\n\n")
        val prefix = if (needsSeparator) "\n\n" else ""
        file.appendText(prefix + content, Charsets.UTF_8)
        val rel = relativeStringFromVault(vault, file)
        sanitizeForModel("ok: appended ${content.length} chars to $rel")
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

    // Walk the vault in a way that excludes Obsidian/Syncthing/git internals — those hold
    // thousands of duplicate notes that drown the actual results otherwise. onEnter blocks
    // descent into any directory whose name starts with '.' (e.g. .stversions, .trash, .git,
    // .obsidian); the .md extension filter eliminates dot-prefixed root files like .DS_Store.
    @Suppress("UNUSED_PARAMETER")
    private fun walkVaultMarkdown(start: File, vault: File): Sequence<File> {
        return start.walkTopDown()
            .onEnter { dir -> dir == start || !dir.name.startsWith(".") }
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
    }

    // Normalize "[N]" or "N" forms to a stored path; otherwise treat the input as a literal
    // relative path. Returns null only if the model passed an [N] form that doesn't match.
    private fun resolveIndexOrPath(arg: String): String? {
        val trimmed = arg.trim()
        // Match "[N]", "N.", "(N)" — be generous; the model generates these inconsistently.
        val match = Regex("""^\[?\(?(\d{1,3})[\])\.\s]*$""").matchEntire(trimmed)
        if (match != null) {
            val idx = match.groupValues[1].toInt() - 1
            return lastResults.getOrNull(idx)
        }
        return trimmed.removePrefix("[").removeSuffix("]")
    }

    private fun recordResults(paths: List<String>) {
        lastResults = paths
    }

    private fun numberedLines(items: List<String>): String =
        items.withIndex().joinToString("\n") { (i, s) -> "[${i + 1}] $s" }

    // True only when '..' appears as a whole path component — i.e. real parent-directory
    // navigation. Filenames that happen to contain two dots elsewhere (e.g. 'ignacio.tech,
    // Portfolio.md', 'IGNACIO.TECH.md') are NOT traversal and should pass.
    private fun containsParentTraversal(path: String): Boolean =
        path.split('/', '\\').any { it == ".." }

    // Simple string-based relative-path computation. We deliberately avoid Kotlin's
    // File.toRelativeString because its implementation routes through canonicalFile, which
    // on Android collapses /storage/emulated/0 (a bind mount) and can produce all-'..'
    // garbage when bases and targets canonicalize differently. Walk products are always
    // built by appending children to vault.path, so prefix-match on absolutePath always
    // works for our case.
    private fun relativeStringFromVault(vault: File, file: File): String {
        val vaultPath = vault.absolutePath
        val filePath = file.absolutePath
        val rel = when {
            filePath == vaultPath -> "."
            filePath.startsWith(vaultPath + File.separator) -> filePath.substring(vaultPath.length + 1)
            else -> filePath  // shouldn't happen given walkTopDown's contract
        }
        return rel.replace(File.separatorChar, '/')
    }

    // Path-traversal guard. The model can hand us any string; canonicalize and verify the
    // resolved file still lives under the vault root.
    //
    // Also does fuzzy matching for sanitized paths: search results have non-BMP chars
    // replaced with '_', so when the model later asks to read that path the exact path
    // won't match on disk. We walk component-by-component and look for siblings whose
    // sanitized name equals the requested sanitized name.
    private fun resolveInsideVault(vault: File, relative: String): File? {
        val cleaned = relative.trimStart('/', '\\').ifEmpty { "." }
        val candidate = File(vault, cleaned)
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val canonicalVault = runCatching { vault.canonicalFile }.getOrNull() ?: return null
        if (canonicalCandidate != canonicalVault &&
            !canonicalCandidate.path.startsWith(canonicalVault.path + File.separator)
        ) return null

        if (canonicalCandidate.exists()) return canonicalCandidate

        var cursor: File = canonicalVault
        for (part in canonicalCandidate.toRelativeString(canonicalVault).split(File.separatorChar)) {
            if (part.isEmpty()) continue
            val direct = File(cursor, part)
            if (direct.exists()) {
                cursor = direct
                continue
            }
            val targetSanitized = sanitizeForModel(part)
            val sibling = cursor.listFiles()?.firstOrNull { sanitizeForModel(it.name) == targetSanitized }
                ?: return canonicalCandidate
            cursor = sibling
        }
        return cursor
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

    private fun readBoundedUtf8(file: File, maxBytes: Int): String {
        val cap = maxBytes.toLong().coerceAtMost(file.length()).toInt()
        if (cap <= 0) return ""
        val buf = ByteArray(cap)
        file.inputStream().use { input ->
            var off = 0
            while (off < cap) {
                val n = input.read(buf, off, cap - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }
    }

    private fun readBoundedUtf8FromTail(file: File, peekBytes: Int): String {
        val len = file.length()
        if (len == 0L) return ""
        val want = peekBytes.toLong().coerceAtMost(len).toInt()
        val buf = ByteArray(want)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - want)
            var off = 0
            while (off < want) {
                val n = raf.read(buf, off, want - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }
    }

    private inline fun safe(name: String, block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        Timber.e(t, "Tool $name threw; returning structured error")
        "Tool $name failed: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
    }

    // Removes characters that crash litertlm's automatic tool-result encoder: non-BMP code
    // points (emoji surrogate pairs) and lone surrogates. Surrogate pairs are replaced with
    // '_' to keep paths as stable strings (emoji-stripped paths used to start with whitespace,
    // which the model couldn't echo back reliably).
    private fun sanitizeForModel(s: String): String {
        if (s.isEmpty()) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val code = c.code
            when {
                Character.isHighSurrogate(c) -> {
                    out.append('_')
                    i += if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) 2 else 1
                    continue
                }
                Character.isLowSurrogate(c) -> {
                    out.append('_')
                    i++
                    continue
                }
                code == 0x09 || code == 0x0A -> out.append(c)
                code < 0x20 || code == 0x7F -> { /* drop control char */ }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    companion object {
        private const val MAX_LIST_ENTRIES = 40
        private const val MAX_READ_BYTES = 64_000
        // Bounded per-file read during search. 16 KB head is enough to capture matches on any
        // typical note while keeping total search time under a couple of seconds.
        private const val MAX_SEARCH_READ_BYTES = 16_000
        private const val MAX_SEARCH_FILES = 500
        private const val MAX_SEARCH_HITS = 5
        private const val SNIPPET_LEN = 220
        // Bumped to fit MAX_SEARCH_HITS × (path + ~220 chars snippet) plus header.
        private const val MAX_RESPONSE_CHARS = 1500
        private const val MAX_FILE_BYTES = 2_000_000L
    }
}
