// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

/**
 * The consent nonces a provider issues from `hello` and consumes when its consent screen opens (SPEC.md 6.3):
 * 128 bits, bound to who asked and for which groups, valid for [Atp.NONCE_TTL_MS], usable once. At most
 * [capacity] are outstanding; issuing past that drops the oldest.
 *
 * Logic only, over an injected clock and randomness. Not thread-safe; a provider keeps one behind one lock.
 */
class NonceBook(
    private val now: () -> Long,
    private val randomBytes: (Int) -> ByteArray,
    private val capacity: Int = 16,
) {
    /** Who a nonce was issued to and what for. */
    data class Binding(val uid: Int, val packageName: String, val signer: String, val groups: Set<String>)

    private class Issued(val binding: Binding, val expiresAt: Long)

    private val issued = LinkedHashMap<String, Issued>()

    /** A new nonce for [binding]: 32 lowercase hex digits. */
    fun issue(binding: Binding): String {
        val bytes = randomBytes(Atp.NONCE_HEX_CHARS / 2)
        require(bytes.size == Atp.NONCE_HEX_CHARS / 2) { "a nonce needs ${Atp.NONCE_HEX_CHARS / 2} random bytes" }
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        while (issued.size >= capacity) issued.remove(issued.keys.first())
        issued[nonce] = Issued(binding, now() + Atp.NONCE_TTL_MS)
        return nonce
    }

    /**
     * The binding [nonce] was issued for, if it is live and was issued to [uid]; null otherwise. The nonce is used
     * up whenever it is found, so a replay, or a try from another app, cannot use it again.
     */
    fun consume(nonce: String, uid: Int): Binding? {
        val found = issued.remove(nonce) ?: return null
        if (now() > found.expiresAt) return null
        return found.binding.takeIf { it.uid == uid }
    }
}
