package io.github.mchany.comciganneo.comcigan

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val SERVICE_RESPONSE_VERSION = "(?:enc)?[0-9]+"

/** Implements the public service page's authenticated response format without executing JavaScript. */
internal object ComciganResponseDecoder {
    private val version = Regex(SERVICE_RESPONSE_VERSION)

    fun decode(text: String, keyHex: String?, expectedFormat: String? = null): String {
        val clean = text.trim('\u0000', '\r', '\n', ' ', '\t', '\uFEFF')
        val format = clean.substringBefore(':')
        val encrypted = format.length < clean.length && version.matches(format)
        if (!encrypted) {
            if (keyHex != null) throw StructureChanged("암호화된 시간표 응답을 받지 못했어요.", source = StructureSource.PARSE)
            return clean
        }
        try {
            require(keyHex != null && keyHex.matches(Regex("[0-9a-fA-F]{64}")))
            val parts = clean.split(':')
            require(parts.size == 4)
            val key = hex(keyHex)
            val binary = parts.drop(1).map { value ->
                if (format != "enc1") {
                    require(value.isNotEmpty())
                    Base64.getDecoder().decode(value).also {
                        require(Base64.getEncoder().encodeToString(it) == value)
                    }
                } else {
                    require(value.matches(Regex("(?:[0-9a-f]{2})+")))
                    hex(value)
                }
            }
            val (iv, ciphertext, tag) = binary
            require(iv.size == 16 && ciphertext.isNotEmpty() && ciphertext.size % 16 == 0 && tag.size == 32)
            val authenticationKey = MessageDigest.getInstance("SHA-256").digest(key)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(authenticationKey, "HmacSHA256")) }
            val expected = mac.doFinal(parts.take(3).joinToString(":").toByteArray(Charsets.UTF_8))
            if (!MessageDigest.isEqual(expected, tag)) throw StructureChanged("시간표 응답의 무결성 검증에 실패했어요.")
            // Authenticated legacy hex envelopes are still accepted by the service page.
            if (format != "enc1" && format != expectedFormat) throw StructureChanged("시간표 응답 버전이 변경됐어요.")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(cipher.doFinal(ciphertext))).toString().trimEnd('\u0000')
        } catch (e: StructureChanged) { throw e.asParseFailure() }
          catch (e: Exception) { throw StructureChanged("암호화된 시간표 응답을 읽을 수 없어요.", e, StructureSource.PARSE) }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
