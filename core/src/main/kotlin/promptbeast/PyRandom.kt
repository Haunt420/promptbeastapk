package promptbeast

import java.math.BigInteger
import java.security.MessageDigest

/** MT19937, bit-exact with CPython's `_random` (init_genrand / init_by_array / genrand_uint32). */
class Mt19937 {
    private val mt = IntArray(N)
    private var mti = N + 1

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) {
            val p = mt[i - 1]
            mt[i] = 1812433253 * (p xor (p ushr 30)) + i
        }
        mti = N
    }

    fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = if (N > key.size) N else key.size
        while (k > 0) {
            val p = mt[i - 1]
            mt[i] = (mt[i] xor ((p xor (p ushr 30)) * 1664525)) + key[j] + j
            i++; j++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            val p = mt[i - 1]
            mt[i] = (mt[i] xor ((p xor (p ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            k--
        }
        mt[0] = 0x80000000.toInt()
    }

    /** Next 32 random bits as an Int (treat as unsigned). */
    fun nextInt32(): Int {
        if (mti >= N) {
            var kk = 0
            while (kk < N - M) {
                val y = (mt[kk] and UPPER) or (mt[kk + 1] and LOWER)
                mt[kk] = mt[kk + M] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX else 0)
                kk++
            }
            while (kk < N - 1) {
                val y = (mt[kk] and UPPER) or (mt[kk + 1] and LOWER)
                mt[kk] = mt[kk + (M - N)] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX else 0)
                kk++
            }
            val y = (mt[N - 1] and UPPER) or (mt[0] and LOWER)
            mt[N - 1] = mt[M - 1] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX else 0)
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX = 0x9908b0df.toInt()
        const val UPPER = 0x80000000.toInt()
        const val LOWER = 0x7fffffff
    }
}

/** Raised where CPython would raise ValueError. */
class PyValueError(message: String) : RuntimeException(message)

/**
 * The slice of Python's `random.Random` PromptBeast uses, bit-exact for the same seed:
 * int seeds (abs value, 32-bit LE key words), str seeds (utf-8 + sha512, version 2), random(), getrandbits(),
 * randint(), choices(weights=..., k=1).
 */
class PyRandom private constructor() {
    private val mt = Mt19937()

    private fun seedBig(n: BigInteger) {
        val a = n.abs()
        val bits = a.bitLength()
        val used = if (bits == 0) 1 else (bits - 1) / 32 + 1
        val key = IntArray(used) { a.shiftRight(32 * it).toInt() }
        mt.initByArray(key)
    }

    fun random(): Double {
        val a = (mt.nextInt32() ushr 5).toLong()
        val b = (mt.nextInt32() ushr 6).toLong()
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    /** `getrandbits(k)` for 0 <= k <= 63. */
    fun getrandbits(k: Int): Long {
        require(k in 0..63) { "k out of range" }
        if (k == 0) return 0L
        if (k <= 32) return (mt.nextInt32().toLong() and 0xFFFFFFFFL) ushr (32 - k)
        var remaining = k
        var out = 0L
        var shift = 0
        while (remaining > 0) {
            var r = mt.nextInt32().toLong() and 0xFFFFFFFFL
            if (remaining < 32) r = r ushr (32 - remaining)
            out = out or (r shl shift)
            shift += 32
            remaining -= 32
        }
        return out
    }

    /** `_randbelow_with_getrandbits(n)` for n >= 1. */
    fun randbelow(n: Long): Long {
        if (n <= 0L) return 0L
        val k = 64 - java.lang.Long.numberOfLeadingZeros(n)
        var r = getrandbits(k)
        while (r >= n) r = getrandbits(k)
        return r
    }

    /** `randint(a, b)`; raises like Python on an empty range. */
    fun randint(a: Long, b: Long): Long {
        val width = b - a + 1
        if (width <= 0) throw PyValueError("empty range in randrange($a, ${b + 1}) ($width)")
        return a + randbelow(width)
    }

    /** `choices(range(n), weights, k=1)[0]` with CPython's cumulative-weights + bisect_right. */
    fun chooseIndex(weights: DoubleArray): Int {
        val n = weights.size
        val cum = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) { acc += weights[i]; cum[i] = acc }
        val total = (if (n == 0) 0.0 else cum[n - 1]) + 0.0
        if (n == 0) throw PyValueError("The number of weights does not match the population")
        if (total <= 0.0) throw PyValueError("Total of weights must be greater than zero")
        if (total.isNaN() || total.isInfinite()) throw PyValueError("Total of weights must be finite")
        val x = random() * total
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (x < cum[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        fun ofInt(seed: Long): PyRandom = ofBig(BigInteger.valueOf(seed))

        fun ofBig(seed: BigInteger): PyRandom = PyRandom().also { it.seedBig(seed) }

        /** `random.Random("text")`: utf-8 bytes + their sha512 digest, read as a big-endian integer. */
        fun ofString(seed: String): PyRandom {
            val raw = seed.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-512").digest(raw)
            return ofBig(BigInteger(1, raw + digest))
        }
    }
}
