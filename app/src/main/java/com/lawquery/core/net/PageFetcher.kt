package com.lawquery.core.net

import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTML 页面拉取器:原生解析通道唯一取页入口。
 * 编码按项目文档 4.1:响应头 Content-Type → 页面 meta charset → 缺省 UTF-8。
 */
class PageFetcher(private val client: OkHttpClient) {

    class PageFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: throw PageFetchException("空响应体:$url")
                val bytes = body.bytes()
                val charset = response.header("Content-Type")?.let(::charsetFromHeader)
                    ?: detectCharsetFromHtml(bytes)
                    ?: Charsets.UTF_8
                String(bytes, charset)
            }
        } catch (e: RateLimitedException) {
            throw e
        } catch (e: PageFetchException) {
            throw e
        } catch (e: IOException) {
            throw e
        }
    }

    private fun charsetFromHeader(contentType: String): Charset? {
        val idx = contentType.lowercase(Locale.ROOT).lastIndexOf("charset=")
        if (idx < 0) return null
        return runCatching {
            Charset.forName(contentType.substring(idx + "charset=".length).trim(' ', '"', ';', '\''))
        }.getOrNull()
    }

    private fun detectCharsetFromHtml(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        val patterns = listOf("<meta charset=", "charset=")
        for (p in patterns) {
            val i = head.indexOf(p)
            if (i >= 0) {
                val rest = head.substring(i + p.length).trimStart(' ', '"', '\'')
                val name = rest.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
                if (name.isNotBlank()) {
                    return runCatching { Charset.forName(name) }.getOrNull()
                }
            }
        }
        return null
    }
}
