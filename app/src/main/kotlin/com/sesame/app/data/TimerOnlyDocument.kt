package com.sesame.app.data

import com.sesame.app.data.profile.ProfileResolver
import com.sesame.app.data.profile.Timer98260

/**
 * The timer-only projection of a generated document.
 *
 * On a platform where the physical pair is mandatory (MediaTek) and the images
 * did not supply it, GhostLock cannot be derived — but the 98260 timer route
 * can, because it never reads the pair. This turns the generated document into
 * a profile that claims only what it can run: the release, the schema, and the
 * timer block, with `pipeline = ["timer"]` and none of the native route/W1
 * sections. Import validation then treats it as a non-W1 document and does not
 * ask for the pair.
 */
object TimerOnlyDocument {
    fun from(documentText: String?): String? {
        val map = documentText
            ?.let { runCatching { HoconSupport.parseValue(it).asValueMap() }.getOrNull() }
            ?: return null
        val release = map["release"] as? String ?: return null
        val major = (map["kernel_major"] as? Number)?.toLong() ?: return null
        val block = map[Timer98260.BLOCK_KEY] ?: return null
        val (config, errors) = Timer98260.parse(block)
        if (config == null || errors.isNotEmpty() || !config.fakeSupported) return null
        return HoconSupport.render(
            linkedMapOf(
                "release" to release,
                "schema_version" to ProfileResolver.ProfileSchemaVersion,
                "kernel_major" to major,
                "pipeline" to listOf("timer"),
                Timer98260.BLOCK_KEY to block,
            ),
        )
    }
}
