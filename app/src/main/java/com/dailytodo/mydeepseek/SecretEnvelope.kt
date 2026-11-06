package com.dailytodo.mydeepseek

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption shared by the key and local chat files. */
object SecretEnvelope {
    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
            .put(1).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
    }
    fun decrypt(key: SecretKey, encoded: ByteArray): ByteArray {
        require(encoded.size >= 30 && encoded[0].toInt() == 1)
        val ivLength = encoded[1].toInt()
        require(ivLength == 12 && encoded.size > 2 + ivLength)
        val iv = encoded.copyOfRange(2, 2 + ivLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encoded.copyOfRange(2 + ivLength, encoded.size))
    }
}
