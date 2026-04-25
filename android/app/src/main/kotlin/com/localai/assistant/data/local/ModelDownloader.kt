package com.localai.assistant.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

// Streams the .litertlm into the destination file with HTTP Range resume.
// HTTP 416 (Range Not Satisfiable) is treated as Complete: it means the file on disk is already
// at or past the server's content length — i.e. the previous download finished but we never wrote
// the completion sentinel.
@Singleton
class ModelDownloader @Inject constructor() {

    sealed class DownloadProgress {
        data class Progress(val bytesDone: Long, val bytesTotal: Long) : DownloadProgress()
        data object Complete : DownloadProgress()
        data class Failed(val message: String) : DownloadProgress()
    }

    fun download(url: String, destination: File): Flow<DownloadProgress> = flow {
        destination.parentFile?.mkdirs()
        val resumeFrom = if (destination.exists()) destination.length() else 0L

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            val responseCode = connection.responseCode
            Timber.i("Model download responded $responseCode (resumeFrom=$resumeFrom)")

            when (responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    streamBody(connection, destination, resumeFrom = resumeFrom, isResume = true)
                }
                HttpURLConnection.HTTP_OK -> {
                    streamBody(connection, destination, resumeFrom = 0L, isResume = false)
                }
                416 /* HTTP_RANGE_NOT_SATISFIABLE */ -> {
                    // File on disk is already complete; the prior run just didn't get to write the
                    // sentinel before being killed. Accept and finalize.
                    Timber.i("Server returned 416 — treating as already-complete download")
                    emit(DownloadProgress.Complete)
                    return@flow
                }
                else -> {
                    emit(DownloadProgress.Failed("Server returned HTTP $responseCode"))
                    return@flow
                }
            }
            emit(DownloadProgress.Complete)
        } catch (e: Exception) {
            Timber.e(e, "Download failed")
            emit(DownloadProgress.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.streamBody(
        connection: HttpURLConnection,
        destination: File,
        resumeFrom: Long,
        isResume: Boolean,
    ) {
        val remainingBytes = connection.contentLengthLong
        val total = if (isResume) resumeFrom + remainingBytes else remainingBytes

        connection.inputStream.use { input ->
            RandomAccessFile(destination, "rw").use { raf ->
                if (isResume) raf.seek(resumeFrom) else raf.setLength(0)

                val buffer = ByteArray(BUFFER_SIZE)
                var bytesDone = if (isResume) resumeFrom else 0L
                var lastEmittedAt = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                    bytesDone += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmittedAt >= EMIT_INTERVAL_MS) {
                        emit(DownloadProgress.Progress(bytesDone, total))
                        lastEmittedAt = now
                    }
                }
                emit(DownloadProgress.Progress(bytesDone, total))
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val EMIT_INTERVAL_MS = 250L
    }
}
