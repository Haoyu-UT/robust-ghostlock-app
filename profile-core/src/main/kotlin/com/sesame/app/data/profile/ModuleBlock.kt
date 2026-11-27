package com.sesame.app.data.profile

/**
 * The module chain's pair manifest (`module`).
 *
 * The chain is bundle-only: a bundled profile and the hand-built `.ko` it
 * names were validated together, so the profile is the only declaration of
 * that pair; there is no separate asset index. The document's ladder derives
 * `module` from this block (it and `syscalls` are mutually exclusive) —
 * [ProfileResolver.validateMerged] refuses a block without the matching
 * `ghostlock` shape.
 *
 * The block names the code and states the per-image numbers the loader will
 * compare. [ModuleConfig.asset] and [ModuleConfig.assetSha256] bind the
 * canonical artifact; [ModuleConfig.crcs] holds the target Image's values for
 * the module's imported symbols (`__versions` entries), keyed by symbol name.
 * The app cannot read the running kernel's ABI, so these values are
 * Image-derived at generation time or fixed by a gated, bundled pair — they are
 * numbers, not claims: the loader is the enforcer, and a wrong value is a clean
 * `ENOEXEC`, not a load. `module_layout` is the one entry that is a layout
 * proof, and it must always be the target's own value; a bundled pair whose
 * value differs from the asset's build-time entry is licensed only by a
 * recorded gate (see docs/analysis/module-single-asset-plan.md).
 */
data class ModuleConfig(
    val asset: String,
    val assetSha256: String,
    /** Symbol name -> CRC: the values the loader compares. */
    val crcs: Map<String, Long>,
    /** Empty = the app's built-in ordered probe list. */
    val vendorFiles: List<String>,
)

/**
 * Fail-closed parser and validator for the block. The block is present only
 * on a profile that selects the module chain; absence is not an error here
 * (the coupling rule lives in [ProfileResolver]).
 */
object ModuleBlock {
    const val BLOCK_KEY = "module"
    /** The block's path inside the document. */
    const val BLOCK_PATH = "ghostlock.module"

    /** The stub's `stub_ko_target` field: 64 bytes including the NUL. */
    private const val KO_TARGET_MAX = 64
    /* `struct modversion_info` carries `char name[56]`, so a symbol name is at
     * most 55 bytes including content and the NUL is implicit. */
    private const val SYMBOL_NAME_MAX = 55
    private const val CRC_MAX = 0xFFFF_FFFFL
    private val BlockKeys = setOf("asset", "sha256", "crcs", "vendor_files")
    private val Sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val SymbolName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** A numeric field: a JSON number or a `"0x..."` / decimal string. */
    private fun asLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toLong()
        is String -> {
            val text = value.trim()
            if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toLongOrNull(16)
            } else {
                text.toLongOrNull()
            }
        }
        else -> null
    }

    /** `assets/`-relative, under `modules/`, a `.ko`, and no traversal. */
    private fun validAsset(path: String): Boolean {
        if (!path.startsWith("modules/") || !path.endsWith(".ko")) return false
        if (path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** A `vendor_file` path the stub can carry and the bridge may read. */
    private fun validVendorFile(path: String): Boolean {
        if (!path.startsWith("/vendor/") || path.endsWith("/")) return false
        if (path.any { it.code < 0x20 || it.code > 0x7e }) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        return path.toByteArray(Charsets.US_ASCII).size < KO_TARGET_MAX
    }

    /** Validate the block; returns the parsed pair plus any errors. */
    fun parse(value: Any?): Pair<ModuleConfig?, List<ConfigError>> {
        val errors = mutableListOf<ConfigError>()
        if (value == null) return null to errors
        val block = value as? Map<*, *>
        if (block == null) {
            errors += ConfigError(BLOCK_PATH, "not an object")
            return null to errors
        }
        for (key in block.keys) {
            val name = key.toString()
            if (name !in BlockKeys) errors += ConfigError("$BLOCK_PATH.$name", "unknown key")
        }
        fun err(field: String, message: String) {
            errors += ConfigError("$BLOCK_PATH.$field", message)
        }

        val asset = (block["asset"] as? String)?.trim()
        when {
            block["asset"] == null -> err("asset", "missing")
            asset == null -> err("asset", "not a string")
            !validAsset(asset) -> err("asset", "must be a relative modules/<name>.ko path")
        }

        val sha = (block["sha256"] as? String)?.trim()
        when {
            block["sha256"] == null -> err("sha256", "missing")
            sha == null -> err("sha256", "not a string")
            !Sha256.matches(sha) -> err("sha256", "must be 64 hex characters")
        }

        val crcs = LinkedHashMap<String, Long>()
        when (val raw = block["crcs"]) {
            null -> err("crcs", "missing")
            !is Map<*, *> -> err("crcs", "not an object")
            else -> {
                if (raw.isEmpty()) err("crcs", "empty")
                for ((key, value) in raw) {
                    val name = key as? String
                    val crc = asLong(value)
                    when {
                        name == null -> err("crcs", "$key is not a symbol name")
                        !SymbolName.matches(name) -> err("crcs", "$name is not a symbol name")
                        name.length > SYMBOL_NAME_MAX ->
                            err("crcs", "$name is longer than $SYMBOL_NAME_MAX characters")
                        crc == null -> err("crcs", "$name is not a number")
                        crc == 0L -> err("crcs", "$name must be non-zero")
                        crc > CRC_MAX -> err("crcs", "$name does not fit in 32 bits")
                        else -> crcs[name] = crc
                    }
                }
            }
        }

        val files = mutableListOf<String>()
        when (val raw = block["vendor_files"]) {
            /* Absent: the app uses its built-in ordered probe list. */
            null -> Unit
            !is List<*> -> err("vendor_files", "not a list")
            else -> {
                if (raw.isEmpty()) {
                    err("vendor_files", "empty (omit the key to use the built-in list)")
                }
                val seen = mutableSetOf<String>()
                for (entry in raw) {
                    val path = (entry as? String)?.trim()
                    when {
                        path == null -> err("vendor_files", "${entry ?: "null"} is not a string")
                        !validVendorFile(path) -> err(
                            "vendor_files",
                            "$path is not a /vendor/ path shorter than $KO_TARGET_MAX bytes",
                        )
                        !seen.add(path) -> err("vendor_files", "$path is listed twice")
                        else -> files += path
                    }
                }
            }
        }

        if (errors.isNotEmpty()) return null to errors
        return ModuleConfig(
            asset = asset!!,
            assetSha256 = sha!!.lowercase(),
            crcs = crcs,
            vendorFiles = files,
        ) to errors
    }

    /** The block's validation errors (empty when absent or valid). */
    fun validate(value: Any?): List<ConfigError> = parse(value).second
}
