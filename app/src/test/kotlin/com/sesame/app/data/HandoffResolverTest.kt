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
 * The rule set: KSU feasibility is KSU's own static check — the device KMI
 * (KSU's parser) is one of the manager's `<kmi>_kernelsu.ko` assets — with the
 * release mirror only when the payload carries no asset names; Magisk is
 * feasible when its libs are present. The stored choice moves its row to the
 * head of the attempt order.
 */
class HandoffResolverTest {
    private val diting = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"
    private val star = "5.4.233-qgki-gbb70cde46897"

    private fun candidate(
        pkg: String,
        suitable: Boolean = true,
        kmis: List<String> = listOf("android12-5.10"),
        path: String = "/x/$pkg/libksud.so",
    ) = ManagerScanner.Candidate(
        packageName = pkg,
        label = pkg,
        path = path,
        builtin = false,
        lateLoadToken = suitable,
        allowShellToken = suitable,
        supportedKmisToken = true,
        kmis = kmis,
    )

    private fun current(value: String?, source: String = "uname", failure: String? = null) =
        ManagerScanner.CurrentKmi(value, source, failure)

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
        kmi: ManagerScanner.CurrentKmi? = current("android12-5.10"),
        magisk: List<RootManager.Magisk> = emptyList(),
        stored: String? = null,
    ) = HandoffResolver.resolve(release, ksu, kmi, magisk, stored)

    @Test
    fun `the kernel kmi inside the asset list is feasible`() {
        val plan = resolve(ksu = listOf(candidate("a", kmis = listOf("android12-5.10", "android13-5.15"))))
        val row = plan.feasible.single()
        assertEquals(HandoffState.FEASIBLE, row.state)
        assertEquals("android12-5.10", row.kmi)
        assertEquals("provides android12-5.10", row.reason)
    }

    @Test
    fun `a kernel kmi outside the asset list is refused with the evidence`() {
        val plan = resolve(ksu = listOf(candidate("a")), kmi = current("android11-5.10"))
        assertTrue(plan.isEmpty)
        assertEquals(
            "provides android12-5.10; kernel is android11-5.10",
            plan.verdicts.single().reason,
        )
        assertNull(plan.verdicts.single().kmi)
    }

    @Test
    fun `a failing kmi detection keeps KSU's own message and no fallback`() {
        val plan = resolve(
            ksu = listOf(candidate("a")),
            kmi = current(null, "none", "Failed to get KMI from boot/modules"),
        )
        assertTrue(plan.isEmpty)
        assertEquals(
            "Failed to get KMI from boot/modules",
            plan.verdicts.single().reason,
        )
    }

    @Test
    fun `a payload without asset names falls back to the mirror with the KSU-derived kmi`() {
        val plan = resolve(ksu = listOf(candidate("a", kmis = emptyList())))
        val row = plan.feasible.single()
        assertEquals("release mirror (no KMI assets): android12-5.10", row.reason)
        assertEquals("android12-5.10", row.kmi)
    }

    @Test
    fun `the mirror derives the kmi itself when detection failed`() {
        val plan = resolve(ksu = listOf(candidate("a", kmis = emptyList())), kmi = null)
        val row = plan.feasible.single()
        assertEquals("release fallback: android12-5.10", row.reason)
        assertEquals("android12-5.10", row.kmi)
    }

    @Test
    fun `the mirror refuses a kernel line below 5_10`() {
        val plan = resolve(
            release = star,
            ksu = listOf(candidate("a", kmis = emptyList())),
            kmi = null,
        )
        assertTrue(plan.isEmpty)
        assertEquals(
            "kernel line <5.10 has no late-load module",
            plan.verdicts.single().reason,
        )
    }

    @Test
    fun `the mirror refuses an unparseable release and a missing android token`() {
        val noVersion = resolve(
            release = "custom",
            ksu = listOf(candidate("a", kmis = emptyList())),
            kmi = null,
        )
        assertEquals(
            "uname -r has no numeric <major>.<minor>",
            noVersion.verdicts.single().reason,
        )
        val noToken = resolve(
            release = "5.10.246-gki-g3e27bf130358",
            ksu = listOf(candidate("a", kmis = emptyList())),
            kmi = null,
        )
        assertEquals(
            "uname -r carries no android<NN> token",
            noToken.verdicts.single().reason,
        )
    }

    @Test
    fun `an unsuitable candidate keeps a row but is never feasible`() {
        val plan = resolve(ksu = listOf(candidate("a", suitable = false), candidate("b")))
        assertEquals(
            listOf("b"),
            plan.verdicts.filter { it.state == HandoffState.FEASIBLE }.map { it.manager.packageName },
        )
        val rejected = plan.verdicts.first { it.manager.packageName == "a" }
        assertEquals(HandoffState.INFEASIBLE, rejected.state)
        assertEquals("not suitable", rejected.reason)
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
        val plan = resolve(
            ksu = listOf(candidate("a"), candidate("b")),
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
        val plan = resolve(
            ksu = listOf(candidate("a"), candidate("b", suitable = false)),
            stored = "b",
        )
        assertEquals(listOf("a"), plan.feasible.map { it.manager.packageName })
    }

    @Test
    fun `family lines name the stored choice and the absent families`() {
        val plan = resolve(ksu = listOf(candidate("a")), stored = "a")
        assertEquals(
            listOf(
                "handoff=ksu state=feasible reason=selected:a",
                "handoff=magisk state=infeasible reason=not installed",
            ),
            HandoffResolver.familyLines(plan, "a"),
        )
    }

    @Test
    fun `picker rows carry feasibility and evidence across both families`() {
        val plan = resolve(
            ksu = listOf(candidate("a"), candidate("gone", suitable = false)),
            magisk = listOf(magisk("com.topjohnwu.magisk", setOf("magiskpolicy"))),
        )
        val rows = HandoffResolver.choices(plan)
        assertEquals(
            listOf("a", "com.topjohnwu.magisk"),
            rows.map { it.packageName },
        )
        assertEquals(HandoffKind.KSU, rows[0].kind)
        assertTrue(rows[0].feasible)
        assertEquals("provides android12-5.10", rows[0].verdict)
        assertEquals(HandoffKind.MAGISK, rows[1].kind)
        assertFalse(rows[1].feasible)
        assertEquals("native libs incomplete (missing magiskpolicy)", rows[1].verdict)
    }

    @Test
    fun `a stored but infeasible choice is named in the family line`() {
        val plan = resolve(
            ksu = listOf(candidate("a")),
            magisk = listOf(magisk("com.topjohnwu.magisk", setOf("magiskpolicy"))),
            stored = "com.topjohnwu.magisk",
        )
        assertEquals(
            "handoff=magisk state=infeasible reason=selected:com.topjohnwu.magisk " +
                "infeasible — using a; native libs incomplete (missing magiskpolicy)",
            HandoffResolver.familyLines(plan, "com.topjohnwu.magisk")
                .first { it.startsWith("handoff=magisk") },
        )
        // The KSU line names the fallback that the run will use.
        assertEquals(
            "handoff=ksu state=feasible reason=available (1 candidate)",
            HandoffResolver.familyLines(plan, "com.topjohnwu.magisk")
                .first { it.startsWith("handoff=ksu") },
        )
    }

    @Test
    fun `an empty plan reports both families`() {
        val plan = resolve()
        assertTrue(plan.isEmpty)
        assertFalse(plan.feasible.any())
        assertEquals(
            listOf(
                "handoff=ksu state=infeasible reason=no ksud with late-load support",
                "handoff=magisk state=infeasible reason=not installed",
            ),
            HandoffResolver.familyLines(plan, null),
        )
    }
}
