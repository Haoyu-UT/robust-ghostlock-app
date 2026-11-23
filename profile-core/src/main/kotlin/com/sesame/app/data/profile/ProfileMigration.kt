package com.sesame.app.data.profile

import com.sesame.app.data.HoconSupport
import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import java.io.File

/**
 * One-shot conversion of the bundled authored documents to the cleaned v3
 * shape (decision 19's authored half). It is a verification aid, not the
 * shipping path: the store regeneration and the extractor's `--format conf`
 * remain the producers of new assets.
 *
 * Each converted document is rendered back through the HOCON parser and
 * compared value for value with the tree it was written from, so a lost key,
 * a re-typed number or a dropped null fails here rather than at load time.
 */
object ProfileMigration {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: ProfileMigration <profilesDir> <outDir>" }
        val src = File(args[0]).canonicalFile
        val out = File(args[1]).canonicalFile
        require(src.isDirectory) { "profiles dir does not exist: $src" }
        out.mkdirs()

        var converted = 0
        var skipped = 0
        val files = src.listFiles { file -> file.isFile && file.name.endsWith(".conf") }
            ?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            if (file.name == "index.conf" || file.name == "device_defaults.conf" ||
                file.name.startsWith("execution-")
            ) {
                skipped++
                continue
            }
            val text = expand(file, src, linkedSetOf()) ?: continue
            val parsed = runCatching { HoconSupport.parseValue(text).asValueMap() }.getOrNull()
            /* Include fragments (the credential and kernelsnitch includes) are not documents. */
            if (parsed == null || parsed["release"] !is String) {
                skipped++
                continue
            }
            val document = ProfileTransport.authoredDocument(parsed)
            val rendered = HoconSupport.render(document)
            val back = runCatching { HoconSupport.parseValue(rendered).asValueMap() }.getOrNull()
            require(back != null) { "${file.name}: the rendered document does not parse" }
            require(equivalent(back, document)) {
                "${file.name}: render/parse round trip differs"
            }
            File(out, file.name).writeText(rendered.trimEnd() + "\n")
            converted++
        }
        println("migrated $converted document(s), skipped $skipped -> ${out.absolutePath}")
    }

    /** Value equality across the parser's number types. */
    private fun equivalent(a: Any?, b: Any?): Boolean = when {
        a is Map<*, *> && b is Map<*, *> ->
            a.keys.map { it.toString() }.toSet() == b.keys.map { it.toString() }.toSet() &&
                a.keys.all { key ->
                    val other = b.entries.firstOrNull { it.key.toString() == key.toString() }?.value
                    equivalent(a[key], other)
                }

        a is List<*> && b is List<*> ->
            a.size == b.size && a.indices.all { equivalent(a[it], b[it]) }

        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }

    /** Same include expansion as the exporter: comments drop, includes inline. */
    private fun expand(file: File, baseDir: File, visiting: MutableSet<String>): String? {
        val name = file.absolutePath
        if (!visiting.add(name)) return ""
        val text = runCatching { file.readText() }.getOrElse {
            visiting.remove(name)
            return null
        }
        val builder = StringBuilder()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            val target = when {
                trimmed.startsWith("#") || trimmed.startsWith("//") -> null
                trimmed.startsWith("include ") ->
                    trimmed.removePrefix("include ").trim().trim('"').takeIf { it.isNotEmpty() }

                else -> null
            }
            if (target == null) {
                builder.append(line).append('\n')
            } else {
                builder.append(expand(File(baseDir, target), baseDir, visiting)).append('\n')
            }
        }
        visiting.remove(name)
        return builder.toString()
    }
}
