package com.sesame.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSourceEditTest {
    private val document = """
        schema_version = 3
        release = "6.1.138-test"
        devices = null
        kernel {
          image_base = null
          symbols = null
          struct = null
          heap = null
          dirtyfrag = null
          kdp = false
        }
        ghostlock {
          syscall = "select_stack"
          carrier = null
          anchor {
            image = "0x2224cd0"
            bytes = 4096
            stride = 32
            scratch = "0x21d1000"
          }
          syscalls {
            select_stack {
              native { waiter_shift = -2 }
              compat = null
            }
            sigreturn = null
            multicast_waiter = null
            tcp_zerocopy = null
          }
          module = null
          execution = null
        }
        timer = null
    """.trimIndent()

    @Test
    fun `page mode removes the lock anchor`() {
        val out = stripLockAnchor(document)
        assertNotNull(out)
        assertFalse(out!!.contains("anchor {"))
        assertTrue(out.contains("anchor = null"))
        assertFalse(out.contains("scratch"))
        assertTrue(out.contains("waiter_shift = -2"))
        assertTrue(out.contains("syscall = \"select_stack\""))
    }

    @Test
    fun `a document without an anchor round-trips`() {
        val text = document.replace(
            Regex("(?ms)^  anchor \\{.*?^  }\\n"),
            "  anchor = null\n",
        )
        assertTrue(text.contains("anchor = null"))

        val out = stripLockAnchor(text)

        assertTrue(out!!.contains("anchor = null"))
        assertTrue(out.contains("waiter_shift = -2"))
        assertNull(stripLockAnchor("not hocon {{{"))
    }
}
