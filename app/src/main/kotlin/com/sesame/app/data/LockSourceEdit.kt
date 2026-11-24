package com.sesame.app.data

/**
 * Removes the lock anchor from a generated profile so the route uses the
 * reclaimed payload page. This is the user's "page" choice at profile
 * generation; the rest of the document is unchanged.
 *
 * The document is the cleaned v3 shape: the anchor is `ghostlock.anchor`, so
 * the page choice sets it to null. (The pre-v3 `route`/`fallback` branch triples
 * no longer exist.)
 */
internal fun stripLockAnchor(documentText: String): String? {
    val entries = ProfileDocumentParser.entries(documentText) ?: return null
    for (entry in entries) {
        val ghostlock = entry["ghostlock"].asValueMap() ?: continue
        ghostlock["anchor"] = null
    }
    return HoconSupport.render(if (entries.size == 1) entries.first() else entries)
}
