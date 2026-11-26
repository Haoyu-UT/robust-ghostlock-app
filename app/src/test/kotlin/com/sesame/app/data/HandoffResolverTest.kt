package com.sesame.app.data

import com.sesame.app.domain.model.HandoffKind
import com.sesame.app.domain.model.HandoffState
import com.sesame.app.domain.model.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S1 rule set: KSU candidacy is the feasibility gate (today's rule),
 * Magisk is detected and reported but not selectable, and the stored choice
 * moves its row to the head of the attempt order.
 */
class HandoffResolverTest {
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

    private fun magisk(pkg: String, missing: Set<String> = emptySet()) = RootManager.Magisk(
        packageName = pkg,
        label = pkg,
        nativeLibDir = "/x/$pkg/lib",
        apkPath = "/x/$pkg/base.apk",
        missing = missing,
    )

    @Test
    fun `detector order is the default and every feasible row is kept`() {
        val plan = HandoffResolver.resolve(
            ksu = listOf(candidate("a"), candidate("b")),
            magisk = emptyList(),
            storedChoice = null,
        )
        assertEquals(listOf("a", "b"), plan.feasible.map { it.manager.packageName })
        assertEquals(2, plan.verdicts.size)
        assertTrue(plan.verdicts.all { it.state == HandoffState.FEASIBLE })
    }

    @Test
    fun `an unsuitable candidate keeps a row but is not feasible`() {
        val plan = HandoffResolver.resolve(
            ksu = listOf(candidate("a", suitable = false), candidate("b")),
            magisk = emptyList(),
            storedChoice = null,
        )
        assertEquals(listOf("b"), plan.feasible.map { it.manager.packageName })
        val rejected = plan.verdicts.first { it.manager.packageName == "a" }
        assertEquals(HandoffState.INFEASIBLE, rejected.state)
        assertEquals("late-load interface missing", rejected.reason)
    }

    @Test
    fun `the stored choice moves to the head and the rest stay fallbacks`() {
        val plan = HandoffResolver.resolve(
            ksu = listOf(candidate("a"), candidate("b"), candidate("c")),
            magisk = emptyList(),
            storedChoice = "b",
        )
        assertEquals(listOf("b", "a", "c"), plan.feasible.map { it.manager.packageName })
    }

    @Test
    fun `a stored choice that is not feasible does not reorder anything`() {
        val plan = HandoffResolver.resolve(
            ksu = listOf(candidate("a"), candidate("b", suitable = false)),
            magisk = emptyList(),
            storedChoice = "b",
        )
        assertEquals(listOf("a"), plan.feasible.map { it.manager.packageName })
    }

    @Test
    fun `magisk is detected but not selectable in this slice`() {
        val plan = HandoffResolver.resolve(
            ksu = emptyList(),
            magisk = listOf(magisk("com.topjohnwu.magisk")),
            storedChoice = null,
        )
        assertTrue(plan.isEmpty)
        val row = plan.verdicts.single()
        assertEquals(HandoffKind.MAGISK, row.manager.kind)
        assertEquals(HandoffState.INFEASIBLE, row.state)
        assertEquals("Magisk handoff is not enabled in this build", row.reason)
        assertFalse(row.manager.kind == HandoffKind.KSU)
    }

    @Test
    fun `an incomplete magisk install names the missing lib`() {
        val plan = HandoffResolver.resolve(
            ksu = emptyList(),
            magisk = listOf(magisk("com.topjohnwu.magisk", missing = setOf("magiskpolicy"))),
            storedChoice = null,
        )
        assertEquals(
            "native libs incomplete (missing magiskpolicy)",
            plan.verdicts.single().reason,
        )
    }

    @Test
    fun `a ksu-only device still reports magisk as absent`() {
        val plan = HandoffResolver.resolve(
            ksu = listOf(candidate("a")),
            magisk = emptyList(),
            storedChoice = null,
        )
        assertEquals(
            listOf(
                "handoff=ksu state=feasible reason=available (1 candidate)",
                "handoff=magisk state=infeasible reason=not installed",
            ),
            HandoffResolver.familyLines(plan, null),
        )
    }

    @Test
    fun `family lines name the stored choice and the absent ksu line`() {
        val plan = HandoffResolver.resolve(
            ksu = listOf(candidate("a"), candidate("b")),
            magisk = listOf(magisk("com.topjohnwu.magisk")),
            storedChoice = "b",
        )
        assertEquals(
            listOf(
                "handoff=ksu state=feasible reason=selected:b",
                "handoff=magisk state=infeasible reason=Magisk handoff is not enabled in this build",
            ),
            HandoffResolver.familyLines(plan, "b"),
        )
    }

    @Test
    fun `an empty plan reports both families`() {
        val plan = HandoffResolver.resolve(emptyList(), emptyList(), null)
        assertTrue(plan.isEmpty)
        assertEquals(
            listOf(
                "handoff=ksu state=infeasible reason=no ksud with late-load --help",
                "handoff=magisk state=infeasible reason=not installed",
            ),
            HandoffResolver.familyLines(plan, null),
        )
    }
}
