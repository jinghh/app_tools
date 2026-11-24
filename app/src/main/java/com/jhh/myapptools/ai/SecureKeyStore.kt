package com.jhh.myapptools.ai

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
 * 用 Android Keystore 的 AES-GCM 密钥加密用户填写的 API Key。
 *
 * 为什么不用明文 SharedPreferences：
 * - 本 App release 未开启混淆、CI 产物为 debug 包，明文 Key 可被轻易读出；
 * - Keystore 中的密钥材料不可导出（本需求不要求用户认证，仅要求不落明文）；
 * - `allowBackup=false` 已关闭云备份，这里再堵住本地 root/文件浏览的口子。
 *
 * 密文格式：`Base64(12 字节 IV + 密文)`，GCM tag 128 bit。
 * KeyStore 不可用时抛异常，由 UI 明确提示「本次不会保存 Key」，不静默降级为明文。
 */
internal class SecureKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }
                .generateKey()
        }
    }

    /** 无已保存 Key 时返回空串；解密失败抛异常，由调用方处理 */
    fun read(): String {
        val encoded = prefs.getString(KEY_CIPHER, null) ?: return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH) { "密文长度异常" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    fun write(plain: String) {
        if (plain.isEmpty()) {
            prefs.edit().remove(KEY_CIPHER).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHER, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun secretKey(): SecretKey = keyStore.getKey(ALIAS, null) as SecretKey

    private companion object {
        const val PREFS = "ai_secure"
        const val KEY_CIPHER = "provider_api_key_cipher"
        const val ALIAS = "ai_provider_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
