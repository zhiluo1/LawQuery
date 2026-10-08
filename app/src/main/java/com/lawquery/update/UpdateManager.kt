package com.lawquery.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 更新机制(客户端):
 * - 更新源 = 开发者发布端工具(发布端 EXE 内置 HTTP 服务);
 * - GET {BASE_URL}/update.json → 版本清单;GET {BASE_URL}/app.apk → 安装包;
 * - 校验 SHA256 后拉起系统安装器完成覆盖安装;不静默安装,始终经用户确认。
 */

/**
 * 更新服务器地址。
 *
 * ⚠️ **开源版本这里是占位地址**:克隆后请改成你自己的服务地址 ——
 * 正式渠道为 CloudBase 静态托管(HTTPS,发布端 EXE「发送更新」时自动推送到公网);
 * 模拟器/局域网联调时可临时改回发布端本地服务 `http://10.0.2.2:8527`。
 *
 * 不改的话只有「检查更新」会失败,检索、浏览、收藏、阅读等**其余功能不受影响**。
 * 配置步骤见 README《接入自己的更新服务》。
 */
object UpdateConfig {
    const val BASE_URL = "https://your-update-host.example.com"
}

@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
)

/** 更新流程状态机:检查 → 可用/最新 → 下载 → 待安装 / 失败 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersionName: String) : UpdateState
    data class Available(val manifest: UpdateManifest, val currentVersionName: String) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val updateDir: File
        get() = File(context.filesDir, "updates").apply { mkdirs() }

    /** 当前已安装版本号(versionCode)与版本名 */
    fun installedVersion(): Pair<Long, String> = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.longVersionCode to (pi.versionName ?: "?")
    }.getOrDefault(1L to "?")

    /** 检查更新:查询清单并与本地版本比较 */
    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        val (currentCode, currentName) = installedVersion()
        try {
            val request = Request.Builder()
                // 时间戳参数防止静态托管的 CDN 缓存旧清单,确保新版本能被及时检查到
                .url(UpdateConfig.BASE_URL.trimEnd('/') + "/update.json?t=" + System.currentTimeMillis())
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateState.Failed("更新服务器响应异常(HTTP ${resp.code})")
                }
                val body = resp.body?.string() ?: return@withContext UpdateState.Failed("更新服务器返回为空")
                val manifest = json.decodeFromString(UpdateManifest.serializer(), body)
                if (manifest.versionCode > currentCode) {
                    UpdateState.Available(manifest, currentName)
                } else {
                    UpdateState.UpToDate(currentName)
                }
            }
        } catch (e: IOException) {
            UpdateState.Failed("无法连接更新服务器,请检查网络")
        } catch (e: Exception) {
            UpdateState.Failed("更新信息解析失败:${e.message ?: "未知错误"}")
        }
    }

    /** 下载新版本安装包(带进度回调,完成后做 SHA256 校验) */
    suspend fun download(manifest: UpdateManifest, onProgress: (Int) -> Unit): UpdateState =
        withContext(Dispatchers.IO) {
            try {
                val url = if (manifest.apkUrl.startsWith("http")) {
                    manifest.apkUrl
                } else {
                    UpdateConfig.BASE_URL.trimEnd('/') + manifest.apkUrl
                }
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext UpdateState.Failed("下载失败(HTTP ${resp.code})")
                    val body = resp.body ?: return@withContext UpdateState.Failed("下载失败:空响应")
                    val total = if (manifest.sizeBytes > 0) manifest.sizeBytes else body.contentLength()
                    val target = File(updateDir, "app_${manifest.versionCode}.apk")
                    target.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(8 * 1024)
                            var read: Int
                            var written = 0L
                            var lastPct = -1
                            while (input.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                written += read
                                if (total > 0) {
                                    val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                    // 完整性校验:发布端提供了 SHA256 时必须匹配
                    manifest.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                        val actual = sha256Of(target)
                        if (!actual.equals(expected, ignoreCase = true)) {
                            target.delete()
                            return@withContext UpdateState.Failed("安装包校验失败,请重试")
                        }
                    }
                    UpdateState.ReadyToInstall(manifest, target)
                }
            } catch (e: IOException) {
                UpdateState.Failed("下载失败:${e.message ?: "网络异常"}")
            } catch (e: Exception) {
                UpdateState.Failed("下载失败:${e.message ?: "未知错误"}")
            }
        }

    /** 拉起系统安装器(用户确认后完成覆盖安装;需已授予"安装未知应用"权限) */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
