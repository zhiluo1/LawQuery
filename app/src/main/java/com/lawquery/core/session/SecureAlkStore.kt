package com.lawquery.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 人民法院案例库会话凭据的本地加密存储(需求 2 登录态存储策略)。
 *
 * - 密钥由 **Android Keystore** 生成并保管(AES/GCM,不可导出),应用只持有句柄;
 * - 凭据(Cookie + userToken)经该密钥加密后写入私有 SharedPreferences,
 *   即便设备被 root 或备份文件外泄,脱离本机 Keystore 也无法解开;
 * - 这样用户只需登录一次,重启应用后仍可继续检索;官方会话真正失效时
 *   接口会返回 401,届时再引导重新登录。
 */
class SecureAlkStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 读取已保存凭据;返回 (cookie, userToken, token 取得时刻)。
     *
     * ⚠️ **向后兼容**:旧版本落盘的是「cookie␀token」两段,这里解析不到第三段时
     * `tokenAt` 返回 0 —— 上层据此判定「token 年龄未知」并**主动续期一次**,
     * 而不是把这份还能救的凭据当成无效丢弃(否则升级 APP 就等于强制重新登录)。
     */
    fun load(): Triple<String?, String?, Long> {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return Triple(null, null, 0L)
        val plain = runCatching { decrypt(payload) }.getOrNull() ?: return Triple(null, null, 0L)
        val parts = plain.split(SEPARATOR)
        val cookie = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val token = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val at = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
        return Triple(cookie, token, at)
    }

    /** 加密保存凭据;cookie 与 token 皆空时视为清除 */
    fun save(cookie: String?, token: String?, tokenAt: Long) {
        if (cookie.isNullOrBlank() && token.isNullOrBlank()) {
            clear()
            return
        }
        val plain = listOf(cookie.orEmpty(), token.orEmpty(), tokenAt.toString())
            .joinToString(SEPARATOR)
        runCatching { prefs.edit().putString(KEY_PAYLOAD, encrypt(plain)).apply() }
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    // ---- 加解密 ----

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // 结构:[1 字节 IV 长度][IV][密文],整体 Base64
        val out = ByteArray(1 + iv.size + body.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(body, 0, out, 1 + iv.size, body.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        val ivSize = raw[0].toInt()
        val iv = raw.copyOfRange(1, 1 + ivSize)
        val body = raw.copyOfRange(1 + ivSize, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** 取(或首次生成)Keystore 中的 AES 密钥 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREF_NAME = "alk_session"
        const val KEY_PAYLOAD = "payload"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lawquery_alk_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        /** 用控制字符分隔两个字段,避免与 Cookie 内容冲突 */
        const val SEPARATOR = "\u0000"
    }
}
