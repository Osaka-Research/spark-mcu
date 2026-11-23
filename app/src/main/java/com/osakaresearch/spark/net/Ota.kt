package com.osakaresearch.spark.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

sealed class OtaResult {
    data class Ok(val bytes: Long, val response: String) : OtaResult()
    data class Fail(val reason: String) : OtaResult()
}

object Ota {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun upload(
        context: Context,
        host: String,
        port: Int,
        firmwareUri: Uri,
        onProgress: (Long, Long) -> Unit,
    ): OtaResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = resolver.openAssetFileDescriptor(firmwareUri, "r")?.use { it.length } ?: -1L

        val binBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(firmwareUri)
                    ?: throw IllegalStateException("cannot open firmware URI")
                input.use { stream ->
                    val src = stream.source()
                    val buf = okio.Buffer()
                    var written = 0L
                    while (true) {
                        val read = src.read(buf, 8192L)
                        if (read == -1L) break
                        sink.write(buf, read)
                        written += read
                        onProgress(written, size)
                    }
                }
            }
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("firmware", "firmware.bin", binBody)
            .build()

        val req = Request.Builder()
            .url("http://$host:$port/ota")
            .post(multipart)
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) OtaResult.Ok(size, body)
                else OtaResult.Fail("HTTP ${resp.code}: $body")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            OtaResult.Fail(t.message ?: "unknown")
        }
    }
}
