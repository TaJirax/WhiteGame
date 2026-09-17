package com.whitegame.app.connection

/**
 * Curve25519 scalar multiplication, used only to derive a WireGuard public key from a private key.
 *
 * Android exposes X25519 through JCA ("XDH") from API 33, but this app supports API 26, so the
 * curve is carried here. This is a direct Kotlin transcription of the TweetNaCl reference
 * implementation (public domain, Bernstein/van Gastel/Janssen/Lange/Schwabe/Smetsers), chosen
 * because it is short and widely cross-checked rather than hand-derived.
 *
 * Verified against the RFC 7748 6.1 vector in Curve25519Test.
 */
object Curve25519 {

    private const val GF_LEN = 16

    private val NINE = ByteArray(32).also { it[0] = 9 }

    private fun gf(vararg init: Long): LongArray {
        val r = LongArray(GF_LEN)
        init.forEachIndexed { i, v -> r[i] = v }
        return r
    }

    /** Public key for [privateKey] — i.e. scalar multiplication of the base point. */
    fun scalarBaseMult(privateKey: ByteArray): ByteArray = scalarMult(privateKey, NINE)

    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32) { "scalar must be 32 bytes" }
        require(point.size == 32) { "point must be 32 bytes" }

        val z = scalar.copyOf(32)
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()

        val x = unpack25519(point)
        val a = gf(1)
        val b = x.copyOf()
        val c = gf()
        val d = gf(1)
        val e = gf()
        val f = gf()

        for (i in 254 downTo 0) {
            val r = (((z[i ushr 3].toInt() and 0xff) ushr (i and 7)) and 1).toLong()
            sel25519(a, b, r)
            sel25519(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            square(d, e)
            square(f, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            square(b, a)
            sub(c, d, f)
            mul(a, c, C121665)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            square(b, e)
            sel25519(a, b, r)
            sel25519(c, d, r)
        }

        val cInv = gf()
        inv25519(cInv, c)
        val out = gf()
        mul(out, a, cInv)
        return pack25519(out)
    }

    private val C121665 = gf(0xDB41L, 1L)

    private fun car25519(o: LongArray) {
        for (i in 0 until GF_LEN) {
            o[i] += (1L shl 16)
            val carry = o[i] shr 16
            val target = if (i < 15) i + 1 else 0
            o[target] += carry - 1 + if (i == 15) 37 * (carry - 1) else 0
            o[i] -= carry shl 16
        }
    }

    /** Constant-time conditional swap of [p] and [q] when [b] is 1. */
    private fun sel25519(p: LongArray, q: LongArray, b: Long) {
        val mask = (b - 1L).inv()
        for (i in 0 until GF_LEN) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(n: LongArray): ByteArray {
        val t = n.copyOf()
        car25519(t); car25519(t); car25519(t)
        val m = LongArray(GF_LEN)
        repeat(2) {
            m[0] = t[0] - 0xffedL
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffffL - ((m[i - 1] shr 16) and 1L)
                m[i - 1] = m[i - 1] and 0xffffL
            }
            m[15] = t[15] - 0x7fffL - ((m[14] shr 16) and 1L)
            val b = (m[15] shr 16) and 1L
            m[14] = m[14] and 0xffffL
            sel25519(t, m, 1L - b)
        }
        val o = ByteArray(32)
        for (i in 0 until GF_LEN) {
            o[2 * i] = (t[i] and 0xffL).toByte()
            o[2 * i + 1] = ((t[i] shr 8) and 0xffL).toByte()
        }
        return o
    }

    private fun unpack25519(n: ByteArray): LongArray {
        val o = LongArray(GF_LEN)
        for (i in 0 until GF_LEN) {
            o[i] = (n[2 * i].toLong() and 0xffL) + ((n[2 * i + 1].toLong() and 0xffL) shl 8)
        }
        o[15] = o[15] and 0x7fffL
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until GF_LEN) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until GF_LEN) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until GF_LEN) for (j in 0 until GF_LEN) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38L * t[i + 16]
        for (i in 0 until GF_LEN) o[i] = t[i]
        car25519(o)
        car25519(o)
    }

    private fun square(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv25519(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            square(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        for (a in 0 until GF_LEN) o[a] = c[a]
    }
}
