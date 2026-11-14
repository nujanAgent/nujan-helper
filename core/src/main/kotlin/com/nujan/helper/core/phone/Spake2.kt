package com.nujan.helper.core.phone

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, byte for byte as BoringSSL's `SPAKE2_*` does it, because that is what Android's ADB daemon runs
 * when a device pairs with a code. Each side blinds a Diffie-Hellman share with the code, so both end with the same key
 * only if they hold the same code, and a listener learns nothing it could test a guess against.
 */
class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    password: ByteArray,
    random: SecureRandom = SecureRandom(),
) {
    enum class Role { ALICE, BOB }

    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val passwordScalar: BigInteger = blinding(passwordHash)
    private val privateKey: BigInteger = ByteArray(64).also(random::nextBytes).let { littleEndian(it).mod(Edwards25519.ORDER).shiftLeft(3) }

    /** The message for the other side: this side's share, blinded with the code. */
    val message: ByteArray = Edwards25519.encode(
        Edwards25519.add(
            Edwards25519.multiply(privateKey, Edwards25519.BASE),
            Edwards25519.multiply(passwordScalar, if (role == Role.ALICE) Edwards25519.M else Edwards25519.N),
        ),
    )

    /** The 64-byte key from [theirMessage], which matches the other side's only if the codes were the same; null for a message that is no point. */
    fun finish(theirMessage: ByteArray): ByteArray? {
        val blinded = Edwards25519.decode(theirMessage) ?: return null
        val mask = Edwards25519.multiply(passwordScalar, if (role == Role.ALICE) Edwards25519.N else Edwards25519.M)
        val shared = Edwards25519.encode(Edwards25519.multiply(privateKey, Edwards25519.add(blinded, Edwards25519.negate(mask))))
        val digest = MessageDigest.getInstance("SHA-512")
        fun add(bytes: ByteArray) {
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        if (role == Role.ALICE) {
            add(myName); add(theirName); add(message); add(theirMessage)
        } else {
            add(theirName); add(myName); add(theirMessage); add(message)
        }
        add(shared)
        add(passwordHash)
        return digest.digest()
    }

    private companion object {
        /**
         * The hash of the code as a scalar, raised by multiples of the group's order until it is a multiple of eight. Its
         * value in the prime-order subgroup is unchanged, and the small-order part M and N may carry is cleared.
         */
        fun blinding(hash: ByteArray): BigInteger {
            var scalar = littleEndian(hash).mod(Edwards25519.ORDER)
            for (bit in 0..2) {
                if (scalar.testBit(bit)) scalar = scalar.add(Edwards25519.ORDER.shiftLeft(bit))
            }
            return scalar
        }

        fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
    }
}

/** The curve's arithmetic in extended coordinates, with BigInteger: a pairing does this a few times, and never on a secret that outlives it. */
internal object Edwards25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val ORDER: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inverse(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_MINUS_ONE: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    val BASE: Point = BigInteger.valueOf(4).multiply(inverse(BigInteger.valueOf(5))).mod(P).let { y ->
        point(requireNotNull(recoverX(y, odd = false)), y)
    }

    /** SPAKE2's fixed points, each hashed from its seed until the hash decodes to a point, so no one chose them. */
    val M: Point by lazy { fromSeed("edwards25519 point generation seed (M)") }
    val N: Point by lazy { fromSeed("edwards25519 point generation seed (N)") }

    private fun fromSeed(seed: String): Point {
        val sha256 = MessageDigest.getInstance("SHA-256")
        var candidate = sha256.digest(seed.toByteArray())
        while (true) {
            decode(candidate)?.let { return it }
            candidate = sha256.digest(candidate)
        }
    }

    private fun inverse(value: BigInteger): BigInteger = value.modPow(P.subtract(BigInteger.TWO), P)

    private fun point(x: BigInteger, y: BigInteger) = Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))

    private fun recoverX(y: BigInteger, odd: Boolean): BigInteger? {
        val square = y.multiply(y).subtract(BigInteger.ONE).multiply(inverse(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P)
        var x = square.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(square).mod(P).signum() != 0) x = x.multiply(SQRT_MINUS_ONE).mod(P)
        if (x.multiply(x).subtract(square).mod(P).signum() != 0) return null
        return if (x.testBit(0) != odd) P.subtract(x).mod(P) else x
    }

    fun add(a: Point, b: Point): Point {
        val e = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val f = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val g = a.t.multiply(BigInteger.TWO).multiply(D).multiply(b.t).mod(P)
        val h = a.z.multiply(BigInteger.TWO).multiply(b.z).mod(P)
        val i = f.subtract(e)
        val j = h.subtract(g)
        val k = h.add(g)
        val l = f.add(e)
        return Point(i.multiply(j).mod(P), k.multiply(l).mod(P), j.multiply(k).mod(P), i.multiply(l).mod(P))
    }

    fun negate(a: Point) = Point(P.subtract(a.x).mod(P), a.y, a.z, P.subtract(a.t).mod(P))

    fun multiply(scalar: BigInteger, point: Point): Point {
        var result = IDENTITY
        for (bit in scalar.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (scalar.testBit(bit)) result = add(result, point)
        }
        return result
    }

    fun encode(point: Point): ByteArray {
        val zInverse = inverse(point.z)
        val x = point.x.multiply(zInverse).mod(P)
        val y = point.y.multiply(zInverse).mod(P)
        val out = ByteArray(32)
        val big = y.toByteArray().reversedArray()
        big.copyInto(out, 0, 0, minOf(big.size, 32))
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** The point [bytes] encode, or null when they encode none. */
    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val odd = bytes[31].toInt() and 0x80 != 0
        val little = bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = BigInteger(1, little.reversedArray())
        if (y >= P) return null
        return recoverX(y, odd)?.let { point(it, y) }
    }
}
