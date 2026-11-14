package com.nujan.helper.core.phone

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why pairing did not finish, in words for the person doing it. */
class AdbPairingException(message: String) : IOException(message)

/**
 * ADB's pairing exchange, run inside a TLS connection to the port Android's "Pair device with pairing code" dialog opens.
 * The two sides run [Spake2] over the code and the TLS channel's exported key, so the code is bound to this connection,
 * then swap their public keys encrypted under the result. The daemon trusts [publicKey] from then on.
 */
object AdbPairing {
    /** What the TLS connection exports for the exchange: this label, with its final NUL, and this many bytes. */
    const val EXPORT_LABEL = "adb-label\u0000"
    const val EXPORT_BYTES = 64

    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()
    private val KEY_INFO = "adb pairing_auth aes-128-gcm key".toByteArray()
    private const val HEADER_VERSION = 1
    private const val TYPE_SPAKE2 = 0
    private const val TYPE_PEER_INFO = 1
    private const val PEER_INFO_BYTES = 8192
    private const val GCM_TAG_BYTES = 16
    private const val MAX_PAYLOAD = PEER_INFO_BYTES * 2
    private const val KEY_RSA_PUBLIC = 0

    /**
     * Pairs over the TLS connection's [input] and [output]. [publicKey] is the ADB form of the key ([AdbKeyFormat.encode])
     * with its name. Throws [AdbPairingException] saying why: a wrong code shows up as the daemon closing the connection.
     */
    fun pair(input: InputStream, output: OutputStream, code: String, exported: ByteArray, publicKey: String, random: SecureRandom = SecureRandom()) {
        val data = DataInputStream(input)
        val spake = Spake2(Spake2.Role.ALICE, CLIENT_NAME, SERVER_NAME, code.toByteArray() + exported, random)
        write(output, TYPE_SPAKE2, spake.message)
        val theirs = refusedOnClose { read(data, TYPE_SPAKE2) }
        val shared = spake.finish(theirs) ?: throw AdbPairingException("The phone answered with something that is not a pairing message.")
        val key = SecretKeySpec(hkdfSha256(shared, KEY_INFO, 16), "AES")

        val info = ByteArray(PEER_INFO_BYTES)
        info[0] = KEY_RSA_PUBLIC.toByte()
        val keyBytes = publicKey.toByteArray()
        require(keyBytes.size < PEER_INFO_BYTES - 1) { "the key is longer than a pairing message holds" }
        keyBytes.copyInto(info, 1)
        write(output, TYPE_PEER_INFO, seal(key, Cipher.ENCRYPT_MODE, info))
        val answer = refusedOnClose { read(data, TYPE_PEER_INFO) }
        try {
            seal(key, Cipher.DECRYPT_MODE, answer)
        } catch (wrongKey: java.security.GeneralSecurityException) {
            throw AdbPairingException(WRONG_CODE)
        }
    }

    private const val WRONG_CODE = "The phone did not accept that code. Codes change each time the dialog opens: check the one on screen and try again."

    private inline fun <T> refusedOnClose(block: () -> T): T = try {
        block()
    } catch (closed: EOFException) {
        throw AdbPairingException(WRONG_CODE)
    } catch (closed: javax.net.ssl.SSLException) {
        throw AdbPairingException(WRONG_CODE)
    }

    private fun write(output: OutputStream, type: Int, payload: ByteArray) {
        val head = ByteBuffer.allocate(6).put(HEADER_VERSION.toByte()).put(type.toByte()).putInt(payload.size).array()
        output.write(head)
        output.write(payload)
        output.flush()
    }

    private fun read(input: DataInputStream, type: Int): ByteArray {
        val version = input.readUnsignedByte()
        val got = input.readUnsignedByte()
        val size = input.readInt()
        if (version != HEADER_VERSION || got != type || size !in 1..MAX_PAYLOAD) {
            throw AdbPairingException("The phone sent a pairing message this Helper does not understand.")
        }
        return ByteArray(size).also(input::readFully)
    }

    /** One message each way, so each direction's first and only nonce is zero. */
    private fun seal(key: SecretKeySpec, mode: Int, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BYTES * 8, ByteArray(12)))
        return cipher.doFinal(bytes)
    }

    internal fun hkdfSha256(secret: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val pseudoRandom = mac.doFinal(secret)
        mac.init(SecretKeySpec(pseudoRandom, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal().copyOf(length)
    }
}

/** A public key in the layout an ADB daemon stores. */
object AdbKeyFormat {
    /**
     * [key] base64-encoded as ADB lays it out: the modulus length in 32-bit words, -1/n mod 2^32, the modulus and
     * R^2 mod n (R = 2^bits) as little-endian words, and the public exponent.
     */
    fun encode(key: java.security.interfaces.RSAPublicKey): String {
        val n = key.modulus
        val words = (n.bitLength() + 31) / 32
        val r32 = java.math.BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = java.math.BigInteger.ONE.shiftLeft(words * 32).modPow(java.math.BigInteger.TWO, n)
        val out = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(words)
        out.putInt(n0inv.toInt())
        fun putWords(value: java.math.BigInteger) {
            val mask = java.math.BigInteger.valueOf(0xffffffffL)
            repeat(words) { out.putInt(value.shiftRight(32 * it).and(mask).toLong().toInt()) }
        }
        putWords(n)
        putWords(rr)
        out.putInt(key.publicExponent.toInt())
        return java.util.Base64.getEncoder().encodeToString(out.array())
    }
}
