package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sesame.app.data.profile.LockSources
import com.sesame.app.domain.model.ProfileImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The run-time lock source: the facts (anchor, scratch) are parsed from the
 * current document on demand — the picker parses them when it opens and the
 * render parses them when it writes — and the only state is the per-release
 * user choice. A selection change is visible on the next parse without a
 * reload (the stale-snapshot regression).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockSourceControllerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    private lateinit var preferences: SharedPreferences
    private lateinit var external: FakeExternalProfileStore
    private lateinit var controller: AndroidProfileConfigController

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("lock-source", 0)
            .also { it.edit().clear().commit() }
        external = FakeExternalProfileStore()
        controller = AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            deviceCodename = "diting",
            forcedSelection = ProfileSelection.Bundled(release),
        )
    }

    private fun renderedGhostlock(config: com.sesame.app.domain.model.ProfileConfig): ValueMap {
        val rendered = requireNotNull(
            controller.nativeDocumentWithOverlay(config, "ghostlock", false, emptyList()),
        )
        val document = requireNotNull(
            HoconSupport.parseValue(String(rendered.bytes, Charsets.UTF_8)).asValueMap(),
        )
        return requireNotNull(document["ghostlock"].asValueMap())
    }

    @Test
    fun `the bundled document offers all three placements, page-free by default`() = runBlocking {
        val lock = requireNotNull(controller.lockState(release))
        assertTrue(lock.anchorPresent)
        assertTrue(lock.scratchPresent)
        assertEquals(
            listOf(LockSources.PageFree, LockSources.AnchorPage, LockSources.Page),
            LockSources.options(lock.anchorPresent, lock.scratchPresent),
        )
        assertEquals(LockSources.PageFree, lock.choice)
        val anchor = renderedGhostlock(controller.load(release))["anchor"].asValueMap()
        assertEquals("0x2a3a600", anchor?.get("scratch"))
    }

    @Test
    fun `the choice reaches the document, anchor plus page then page`() = runBlocking {
        assertTrue(controller.selectLockSource(release, LockSources.AnchorPage))
        assertEquals(LockSources.AnchorPage, controller.lockState(release)?.choice)
        val anchor = renderedGhostlock(controller.load(release))["anchor"].asValueMap()
        assertEquals("0x2a3a590", anchor?.get("image"))
        assertNull(anchor?.get("scratch"))

        assertTrue(controller.selectLockSource(release, LockSources.Page))
        assertEquals(LockSources.Page, controller.lockState(release)?.choice)
        assertNull(renderedGhostlock(controller.load(release))["anchor"])
    }

    @Test
    fun `a stored value the document does not support is cleared`() = runBlocking {
        preferences.edit { putString("lock_source_$release", "bogus") }
        assertEquals(LockSources.PageFree, controller.lockState(release)?.choice)
        assertNull(preferences.getString("lock_source_$release", null))
    }

    @Test
    fun `the facts follow a new selection on the next parse`() = runBlocking {
        val bundled = requireNotNull(controller.renderResolved(release))
        /* An unpinned controller resolves the stored selection, so an import
         * changes what the next parse sees — the regression this pins. */
        val live = AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            deviceCodename = "diting",
        )
        /* Anchor only: the scratch is gone, so page-free is not offered and the
         * default moves to anchor + page — without a reload. */
        val anchorOnly = bundled.replace("scratch = \"0x2a3a600\"", "scratch = null")
        val uri = external.add("content://anchor-only.conf", anchorOnly)
        val accepted = live.selectExternalProfile(uri, release)
        assertTrue("import refused: $accepted", accepted is ProfileImportResult.Accepted)

        val anchorOnlyLock = requireNotNull(live.lockState(release))
        assertTrue(anchorOnlyLock.anchorPresent)
        assertFalse(anchorOnlyLock.scratchPresent)
        assertEquals(LockSources.AnchorPage, anchorOnlyLock.choice)

        /* No anchor: only the page placement is expressible, and the render
         * clears nothing it was not asked to. */
        val page = bundled.replace(
            Regex("""anchor \{\n(?:.*\n)*? {2}\}"""),
            "anchor = null",
        )
        assertTrue(page.contains("anchor = null"))
        val pageUri = external.add("content://page.conf", page)
        val acceptedPage = live.selectExternalProfile(pageUri, release)
        assertTrue("import refused: $acceptedPage", acceptedPage is ProfileImportResult.Accepted)
        val pageLock = requireNotNull(live.lockState(release))
        assertFalse(pageLock.anchorPresent)
        assertEquals(LockSources.Page, pageLock.choice)
    }
}
