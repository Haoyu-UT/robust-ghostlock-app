// SPDX-License-Identifier: GPL-2.0
/*
 * sgpatch — neutralise an OPPO/OnePlus secureguard exec handler in place.
 *
 * The guard registers a kretprobe on do_execveat_common; its return handler
 * (oplus_exe_block_ret_handler on the 6.6 `_new` generation) applies the
 * exec policy. The handler is resolved by kprobe symbol name — module symbols
 * are in kallsyms with CONFIG_KALLSYMS_ALL — and its first two instructions
 * are replaced with `mov x0, #0; ret` through a writable vmap alias. The
 * original words are restored on unload.
 *
 * The entry must be `paciasp` unless force=1 is passed; the KCFI type hash at
 * entry-4 is not touched.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/vmalloc.h>
#include <linux/mm.h>
#include <linux/string.h>
#include <asm/cacheflush.h>

static char *symbol = "oplus_exe_block_ret_handler";
module_param(symbol, charp, 0444);
MODULE_PARM_DESC(symbol, "Guard handler to neutralise");

static bool force;
module_param(force, bool, 0444);
MODULE_PARM_DESC(force, "Patch even when the entry is not paciasp");

#define PACIASP   0xd503233fu
#define MOV_X0_0  0xd2800000u
#define RET       0xd65f03c0u

/*
 * A function compiled with -fsanitize=shadow-call-stack (CONFIG_SHADOW_CALL_STACK)
 * opens with `str x30, [x18, #imm]`. The kernel's module alternatives apply it
 * over the paciasp the file carries, so the runtime entry is this store. It is
 * safe to replace: the shadow slot is per-function and only read by the
 * function's own epilogue, which our early return skips.
 */
static bool is_scs_store(u32 insn)
{
	return (insn >> 22) == 0x3e0 &&		/* str x?, [x?, #imm] 64-bit */
	       ((insn >> 5) & 0x1f) == 18 &&	/* Rn = x18 (the SCS base) */
	       (insn & 0x1f) == 30;		/* Rt = x30 (the return address) */
}

static struct kprobe kp;
static unsigned long patched_addr;
static u32 saved_insn[2];
static bool patched;

static int resolve_target(unsigned long *addr)
{
	int ret;

	memset(&kp, 0, sizeof(kp));
	kp.symbol_name = symbol;
	ret = register_kprobe(&kp);
	if (ret)
		return ret;
	*addr = (unsigned long)kp.addr;
	unregister_kprobe(&kp);
	return 0;
}

static int patch_words(unsigned long addr, const u32 *words, size_t count)
{
	struct page *page;
	void *alias;
	size_t bytes = count * sizeof(u32);

	page = vmalloc_to_page((void *)addr);
	if (!page)
		return -EINVAL;
	alias = vmap(&page, 1, VM_MAP, PAGE_KERNEL);
	if (!alias)
		return -ENOMEM;
	memcpy((u8 *)alias + offset_in_page(addr), words, bytes);
	vunmap(alias);
	flush_icache_range(addr, addr + bytes);
	return 0;
}

static int __init sgpatch_init(void)
{
	const u32 patch[2] = { MOV_X0_0, RET };
	unsigned long addr;
	u32 entry, next;
	int ret;

	ret = resolve_target(&addr);
	if (ret) {
		pr_err("sgpatch: cannot resolve %s: %d\n", symbol, ret);
		return ret;
	}
	entry = READ_ONCE(*(u32 *)addr);
	next = READ_ONCE(*((u32 *)addr + 1));
	pr_info("sgpatch: %s at %px entry=%08x next=%08x\n", symbol,
		(void *)addr, entry, next);
	if (entry != PACIASP && !is_scs_store(entry) && !force) {
		pr_err("sgpatch: unexpected entry (want paciasp or the SCS store); refusing\n");
		return -EINVAL;
	}
	saved_insn[0] = entry;
	saved_insn[1] = next;
	ret = patch_words(addr, patch, ARRAY_SIZE(patch));
	if (ret) {
		pr_err("sgpatch: write failed: %d\n", ret);
		return ret;
	}
	patched_addr = addr;
	patched = true;
	pr_info("sgpatch: patched (readback=%08x %08x)\n",
		READ_ONCE(*(u32 *)addr), READ_ONCE(*((u32 *)addr + 1)));
	return 0;
}

static void __exit sgpatch_exit(void)
{
	if (patched)
		patch_words(patched_addr, saved_insn, ARRAY_SIZE(saved_insn));
	pr_info("sgpatch: unloaded\n");
}

module_init(sgpatch_init);
module_exit(sgpatch_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Neutralise a secureguard exec handler in place");
