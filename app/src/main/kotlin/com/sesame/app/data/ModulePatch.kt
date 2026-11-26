package com.sesame.app.data

/**
 * In-place `__versions` patch of the bundled switch module.
 *
 * The profile carries the target Image's per-symbol CRCs (`module.crcs`). The
 * kernel compares those at load (`check_version` for each import, and
 * `check_modstruct_version` for `module_layout`), so this writes them into the
 * canonical asset after its sha256 has been verified.
 *
 * The rewrite touches only the eight bytes of each `struct modversion_info`
 * entry that carry the CRC (the low four are the value; the high four are
 * zeroed because the kernel compares as `u32`). No section moves, no size
 * changes: `.modinfo` keeps the module's name (the end-of-run unload reads it
 * from there) and the delivery plan's block arithmetic is unaffected.
 *
 * Fail-closed rules: the asset must parse as a 64-bit little-endian ELF with a
 * non-empty `__versions`; the profile's name set must equal the asset's
 * entries exactly; `module_layout` must be present on both sides (it is the
 * one layout proof); every value is a non-zero 32-bit CRC.
 */
object ModulePatch {
    /** `struct modversion_info` on arm64: 8-byte `crc` + `char name[56]`. */
    internal const val ENTRY_SIZE = 64
    private const val NAME_MAX = 56
    private const val CRC_MAX = 0xFFFF_FFFFL
    private const val MODULE_LAYOUT = "module_layout"

    internal data class Entry(val name: String, val crc: Long, val offset: Int)

    sealed interface Result {
        data class Ok(
            val bytes: ByteArray,
            /** True when at least one byte differs from the canonical asset. */
            val patched: Boolean,
            /** The asset's build-time `module_layout` CRC, for the log. */
            val assetLayout: Long,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    /** Apply the profile's CRCs; returns new bytes, never mutating the input. */
    fun apply(bytes: ByteArray, crcs: Map<String, Long>): Result {
        val entries = try {
            parse(bytes)
        } catch (e: IllegalArgumentException) {
            return Result.Failed("not a patchable module: ${e.message}")
        }
        val assetNames = entries.mapTo(HashSet()) { it.name }
        if (assetNames.size != entries.size) {
            return Result.Failed("duplicate __versions entry")
        }
        val missing = assetNames - crcs.keys
        val extra = crcs.keys - assetNames
        if (missing.isNotEmpty()) {
            return Result.Failed(
                "profile.crcs lacks ${missing.sorted().joinToString(", ")}",
            )
        }
        if (extra.isNotEmpty()) {
            return Result.Failed(
                "profile.crcs names entries the asset does not import: " +
                    extra.sorted().joinToString(", "),
            )
        }
        if (MODULE_LAYOUT !in crcs || MODULE_LAYOUT !in assetNames) {
            return Result.Failed("the $MODULE_LAYOUT entry is missing")
        }
        for ((name, value) in crcs) {
            if (value <= 0L || value > CRC_MAX) {
                return Result.Failed("$name is not a non-zero 32-bit CRC")
            }
        }

        val out = bytes.copyOf()
        var patched = false
        for (entry in entries) {
            val value = crcs.getValue(entry.name)
            for (i in 0 until 8) {
                val next = when (i) {
                    0 -> (value and 0xff).toByte()
                    1 -> ((value ushr 8) and 0xff).toByte()
                    2 -> ((value ushr 16) and 0xff).toByte()
                    3 -> ((value ushr 24) and 0xff).toByte()
                    else -> 0
                }
                if (out[entry.offset + i] != next) patched = true
                out[entry.offset + i] = next
            }
        }
        val assetLayout = entries.first { it.name == MODULE_LAYOUT }.crc
        return Result.Ok(out, patched, assetLayout)
    }

    /**
     * Parse the `__versions` entries. Internal for tests; the delivery path
     * only needs [apply].
     */
    internal fun parse(bytes: ByteArray): List<Entry> {
        require(bytes.size >= 64) { "shorter than an ELF64 header" }
        require(
            bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte(),
        ) { "not an ELF" }
        require(bytes[4].toInt() == 2) { "not a 64-bit ELF" }
        require(bytes[5].toInt() == 1) { "not little-endian" }

        val shoff = u64(bytes, 0x28)
        val shentsize = u16(bytes, 0x3a)
        val shnum = u16(bytes, 0x3c)
        val shstrndx = u16(bytes, 0x3e)
        require(shentsize == 64) { "section header size $shentsize" }
        require(shnum > 0 && shoff > 0) { "no section table" }
        require(shoff + shnum.toLong() * shentsize <= bytes.size) { "section table out of bounds" }
        require(shstrndx < shnum) { "no section-name table" }

        fun nameOffset(index: Int): Int =
            u32(bytes, (shoff + index.toLong() * shentsize).toInt()).toInt()

        val strtab = (shoff + shstrndx.toLong() * shentsize).toInt()
        val strOff = u64(bytes, strtab + 24)
        val strSize = u64(bytes, strtab + 32)
        require(strOff >= 0 && strOff + strSize <= bytes.size) { "section names out of bounds" }

        fun sectionName(index: Int): String {
            var p = (strOff + nameOffset(index)).toInt()
            val end = (strOff + strSize).toInt()
            require(p in 0 until end) { "section name out of bounds" }
            while (p < end && bytes[p] != 0.toByte()) p++
            return String(bytes, (strOff + nameOffset(index)).toInt(), p - (strOff + nameOffset(index)).toInt())
        }

        var versions: Int? = null
        for (i in 0 until shnum) {
            if (sectionName(i) == "__versions") {
                versions = i
                break
            }
        }
        val index = requireNotNull(versions) { "no __versions section" }
        val header = (shoff + index.toLong() * shentsize).toInt()
        val offset = u64(bytes, header + 24)
        val size = u64(bytes, header + 32)
        require(offset >= 0 && size > 0 && offset + size <= bytes.size) {
            "__versions is empty or out of bounds"
        }
        require(size % ENTRY_SIZE == 0L) { "__versions size $size is not a multiple of $ENTRY_SIZE" }

        val entries = ArrayList<Entry>()
        var p = offset.toInt()
        while (p < offset + size) {
            val crc = u64(bytes, p)
            val name = String(bytes, p + 8, NAME_MAX)
                .substringBefore('\u0000')
            require(name.isNotEmpty()) { "an entry has an empty name" }
            require(name.length < NAME_MAX) { "an entry name is not NUL-terminated" }
            entries += Entry(name, crc, p)
            p += ENTRY_SIZE
        }
        return entries
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
        return v
    }
}
