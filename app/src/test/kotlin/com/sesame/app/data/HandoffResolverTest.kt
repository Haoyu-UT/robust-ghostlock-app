package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffState
import com.sesame.app.domain.model.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule set: KSU feasibility is the manager's own `boot-info` interface
 * (membership of `current-kmi` in `supported-kmis`), with the release mirror
 * only when the manager cannot answer; Magisk is feasible when its libs are
 * present. The stored choice moves its row to the head of the attempt order.
 */
class HandoffResolverTest {
    private val diting = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"
    private val star = "5.4.233-qgki-gbb70cde46897"

    private fun candidate(
        pkg: String,
        suitable: Boolean = true,
        iface: Boolean? = true,
        path: String = "/x/$pkg/libksud.so",
    ) = ManagerScanner.Candidate(
        packageName = pkg,
        label = pkg,
        path = path,
        builtin = false,
        lateLoadToken = true,
        allowShellToken = true,
        interfaceOk = if (suitable) iface else false,
    )

    private fun answer(supported: List<String>?, current: String?, failure: String? = null) =
        ManagerScanner.KmiAnswer(supported, current, failure)

    private fun magisk(pkg: String, missing: Set<String> = emptySet()) = RootManager.Magisk(
        packageName = pkg,
        label = pkg,
        nativeLibDir = "/x/$pkg/lib",
        apkPath = "/x/$pkg/base.apk",
        missing = missing,
    )

    private fun resolve(
        release: String = diting,
        ksu: List<ManagerScanner.Candidate> = emptyList(),
        kmi: Map<String, ManagerScanner.KmiAnswer> = emptyMap(),
        magisk: List<RootManager.Magisk> = emptyList(),
        stored: String? = null,
    ) = HandoffResolver.resolve(release, ksu, kmi, magisk, stored)

    @Test
    fun `a current kmi inside the supported list is feasible`() {
        val c = candidate("a")
        val plan = resolve(
            ksu = listOf(c),
            kmi = mapOf(c.path to answer(listOf("android12-5.10", "android13-5.15"), "android12-5.10")),
        )
        val row = plan.feasible.single()
        assertEquals(HandoffState.FEASIBLE, row.state)
        assertEquals("android12-5.10", row.kmi)
        assertEquals("supported-kmis lists android12-5.10", row.reason)
    }

    @Test
    fun `a current kmi outside the list is refused with the named reason`() {
        val c = candidate("a")
        val plan = resolve(
            ksu = listOf(c),
            kmi = mapOf(c.path to answer(listOf("android12-5.10"), "android11-5.10")),
        )
        assertTrue(plan.isEmpty)
        assertEquals("supported KMI list does not contain android11-5.10", plan.verdicts.single().reason)
        assertNull(plan.verdicts.single().kmi)
    }

    @Test
    fun `a failing current-kmi keeps the manager's own message and no fallback`() {
        val c = candidate("a")
        val plan = resolve(
            ksu = listOf(c),
            kmi = mapOf(c.path to answer(listOf("android12-5.10"), null, "Failed to get KMI from boot/modules")),
        )
        assertTrue(plan.isEmpty)
        assertEquals(
            "Failed to get KMI from boot/modules",
            plan.verdicts.single().reason,
        )
    }

    @Test
    fun `a manager that cannot answer falls back to the release mirror`() {
        val c = candidate("a")
        val plan = resolve(ksu = listOf(c), kmi = mapOf(c.path to answer(null, null)))
        val row = plan.feasible.single()
        assertEquals("release fallback: android12-5.10", row.reason)
        assertEquals("android12-5.10", row.kmi)
    }

    @Test
    fun `the fallback refuses a kernel line below 5_10`() {
        val c = candidate("a")
        val plan = resolve(release = star, ksu = listOf(c), kmi = mapOf(c.path to answer(null, null)))
        assertTrue(plan.isEmpty)
        assertEquals(
            "kernel line <5.10 has no late-load module",
            plan.verdicts.single().reason,
        )
    }

    @Test
    fun `the fallback refuses an unparseable release and a missing android token`() {
        val c = candidate("a")
        val noVersion = resolve(release = "custom", ksu = listOf(c), kmi = mapOf(c.path to answer(null, null)))
        assertEquals(
            "uname -r has no numeric <major>.<minor>",
            noVersion.verdicts.single().reason,
        )
        val noToken = resolve(release = "5.10.246-gki-g3e27bf130358", ksu = listOf(c), kmi = mapOf(c.path to answer(null, null)))
        assertEquals(
            "uname -r carries no android<NN> token",
            noToken.verdicts.single().reason,
        )
    }

    @Test
    fun `an unsuitable candidate keeps a row but is never probed or feasible`() {
        val plan = resolve(ksu = listOf(candidate("a", suitable = false), candidate("b")), kmi = emptyMap())
        assertEquals(listOf("b"), plan.verdicts.filter { it.state == HandoffState.FEASIBLE }
            .map { it.manager.packageName })
        val rejected = plan.verdicts.first { it.manager.packageName == "a" }
        assertEquals(HandoffState.INFEASIBLE, rejected.state)
        assertEquals("late-load interface missing", rejected.reason)
    }

    @Test
    fun `magisk is feasible when its required libs are present`() {
        val plan = resolve(magisk = listOf(magisk("com.topjohnwu.magisk")))
        val row = plan.feasible.single()
        assertEquals(HandoffKind.MAGISK, row.manager.kind)
        assertEquals("binaries present (magisk, magiskinit, magiskpolicy)", row.reason)
    }

    @Test
    fun `an incomplete magisk install names the missing lib`() {
        val plan = resolve(magisk = listOf(magisk("com.topjohnwu.magisk", setOf("magiskpolicy"))))
        assertTrue(plan.isEmpty)
        assertEquals(
            "native libs incomplete (missing magiskpolicy)",
            plan.verdicts.single().reason,
        )
    }

    @Test
    fun `the stored choice moves to the head and the rest stay fallbacks`() {
        val a = candidate("a")
        val b = candidate("b")
        val plan = resolve(
            ksu = listOf(a, b),
            kmi = mapOf(
                a.path to answer(listOf("android12-5.10"), "android12-5.10"),
                b.path to answer(listOf("android12-5.10"), "android12-5.10"),
            ),
            magisk = listOf(magisk("com.topjohnwu.magisk")),
            stored = "b",
        )
        assertEquals(
            listOf("b", "a", "com.topjohnwu.magisk"),
            plan.feasible.map { it.manager.packageName },
        )
    }

    @Test
    fun `a stored choice that is not feasible does not reorder anything`() {
        val a = candidate("a")
        val b = candidate("b", suitable = false)
        val plan = resolve(
            ksu = listOf(a, b),
            kmi = mapOf(a.path to answer(listOf("android12-5.10"), "android12-5.10")),
            stored = "b",
        )
        assertEquals(listOf("a"), plan.feasible.map { it.manager.packageName })
    }

    @Test
    fun `family lines name the stored choice and the absent families`() {
        val a = candidate("a")
        val plan = resolve(
            ksu = listOf(a),
            kmi = mapOf(a.path to answer(listOf("android12-5.10"), "android12-5.10")),
            stored = "a",
        )
        assertEquals(
            listOf(
                "handoff=ksu state=feasible reason=selected:a",
                "handoff=magisk state=infeasible reason=not installed",
            ),
            HandoffResolver.familyLines(plan, "a"),
        )
    }

    @Test
    fun `an empty plan reports both families`() {
        val plan = resolve()
        assertTrue(plan.isEmpty)
        assertFalse(plan.feasible.any())
        assertEquals(
            listOf(
                "handoff=ksu state=infeasible reason=no ksud with late-load --help",
                "handoff=magisk state=infeasible reason=not installed",
            ),
            HandoffResolver.familyLines(plan, null),
        )
    }
}
