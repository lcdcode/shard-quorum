package com.lcdcode.shardquorum.sskr

/**
 * Arithmetic in the finite field GF(2^8).
 *
 * The field uses the Rijndael/AES reducing polynomial 0x11b and 3 (x + 1) as the
 * generator for the log/exp tables. These are the exact parameters used by
 * SLIP-39 and Blockchain Commons bc-shamir, so secret shares produced here
 * interoperate byte-for-byte with that ecosystem.
 *
 * Note: the choice of generator only affects how the internal tables are laid
 * out; multiply/divide/interpolate results depend solely on the field's
 * reducing polynomial, so byte-exact compatibility holds for any primitive
 * generator.
 */
internal object Gf256 {
    private const val REDUCING_POLY = 0x11b
    private const val GENERATOR = 3
    private const val FIELD_SIZE = 256
    private const val MULTIPLICATIVE_ORDER = FIELD_SIZE - 1 // 255 nonzero elements

    private val exp = IntArray(MULTIPLICATIVE_ORDER)
    private val log = IntArray(FIELD_SIZE)

    init {
        var poly = 1
        for (i in 0 until MULTIPLICATIVE_ORDER) {
            exp[i] = poly
            log[poly] = i
            // Multiply the running value by the generator 3 == (x + 1):
            // (poly << 1) xor poly, reduced modulo the field polynomial.
            poly = (poly shl 1) xor poly
            if (poly and FIELD_SIZE != 0) poly = poly xor REDUCING_POLY
        }
        // log[0] is undefined and never read: every caller guards the zero case.
    }

    /** Addition and subtraction in GF(2^n) are both bitwise xor. */
    fun add(a: Int, b: Int): Int = a xor b

    fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return exp[(log[a and 0xff] + log[b and 0xff]) % MULTIPLICATIVE_ORDER]
    }

    fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero in GF(256)" }
        if (a == 0) return 0
        val index = (log[a and 0xff] - log[b and 0xff] + MULTIPLICATIVE_ORDER) % MULTIPLICATIVE_ORDER
        return exp[index]
    }

    /**
     * Evaluates, at [x], the unique polynomial that passes through every
     * (x, value-vector) sample in [points], operating on each byte position
     * independently. This is standard Lagrange interpolation over GF(256).
     *
     * All x-coordinates and [x] are field elements (0..255). The y-values are
     * equal-length byte vectors. Subtraction is xor, so `(x - xj)` is `x xor xj`.
     */
    fun interpolate(points: List<Pair<Int, ByteArray>>, x: Int): ByteArray {
        require(points.isNotEmpty()) { "interpolation needs at least one point" }
        // Exact hit: the polynomial value at a sample point is that sample.
        points.firstOrNull { it.first == x }?.let { return it.second.copyOf() }

        val length = points.first().second.size
        require(points.all { it.second.size == length }) {
            "all sample vectors must have the same length"
        }
        // Distinct x-coordinates are required: a repeated x makes some basis
        // denominator zero (div-by-zero below). Fail with a clear precondition
        // rather than the opaque "division by zero" from the field arithmetic.
        require(points.mapTo(HashSet(points.size)) { it.first }.size == points.size) {
            "interpolation x-coordinates must be distinct"
        }

        val result = ByteArray(length)
        for (i in points.indices) {
            val (xi, yi) = points[i]
            // Lagrange basis coefficient: product over j != i of (x - xj)/(xi - xj).
            var numerator = 1
            var denominator = 1
            for (j in points.indices) {
                if (j == i) continue
                val xj = points[j].first
                numerator = mul(numerator, x xor xj)
                denominator = mul(denominator, xi xor xj)
            }
            val coefficient = div(numerator, denominator)
            for (k in 0 until length) {
                val term = mul(coefficient, yi[k].toInt() and 0xff)
                result[k] = (result[k].toInt() xor term).toByte()
            }
        }
        return result
    }
}
