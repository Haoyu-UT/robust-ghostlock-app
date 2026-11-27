// SPDX-License-Identifier: GPL-2.0
/*
 * sgpatch — neutralise OPPO/OnePlus secureguard exec handlers in place.
 *
 * Patch every guard handler that resolves: the exec check (called from the
 * kretprobe/tracepoint path) and, for Scope B, the credential pair's post
 * handler (called from the sys_enter/sys_exit tracepoints):
 *
 *   do_execveat_common_entry_handler   6.6 `_new` — the check and its userspace report
 *   oplus_exe_block_ret_handler        5.10/6.1  — the block decision and the SIGKILL
 *   oplus_root_check_post_handler      all three — the credential pair's type-0 report and
 *                                      SIGKILL (Scope B: keeps an app-uid `su` from tripping
 *                                      the daemon's anti-root reboot on a locked unit)
 *
 * Symbols resolve through kprobes by name (module symbols are in kallsyms with
 * CONFIG_KALLSYMS_ALL), never through /proc/kallsyms or sysfs. Each entry must be
 * `paciasp` or the SCS prologue unless force=1; the first two instructions become
 * `mov x0, #0; ret` through a writable vmap alias, and the saved words are restored
 * on unload. The KCFI type hash at entry-4 is not touched.
 *
 * symbol=  comma-separated override list; entries that do not resolve or do not
 *          look like a function entry are skipped, and the load fails only when
 *          nothing at all was patched.
 * force=1  patch even when the entry is not a recognised prologue.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/vmalloc.h>
#include <linux/mm.h>
#include <linux/string.h>
#include <asm/cacheflush.h>

#define MAX_TARGETS 8

static char *symbol =
	"do_execveat_common_entry_handler,oplus_exe_block_ret_handler,"
	"oplus_root_check_post_handler";
module_param(symbol, charp, 0444);
MODULE_PARM_DESC(symbol, "Comma-separated guard handlers to neutralise");

static bool force;
module_param(force, bool, 0444);
MODULE_PARM_DESC(force, "Patch even when the entry is not paciasp");

#define PACIASP   0xd503233fu
#define MOV_X0_0  0xd2800000u
#define RET       0xd65f03c0u

struct target {
	unsigned long addr;
	u32 saved[2];
};

static struct target targets[MAX_TARGETS];
static int nr_targets;

/*
 * A function compiled with -fsanitize=shadow-call-stack (CONFIG_SHADOW_CALL_STACK)
 * opens with `str x30, [x18, #imm]` (pre/post-index forms). The kernel's module
 * alternatives apply it over the paciasp the file carries, so the runtime entry may
 * be this store. It is safe to replace: the shadow slot is per-function and only
 * read by the function's own epilogue, which our early return skips.
 */
static bool is_scs_store(u32 insn)
{
	return (insn >> 22) == 0x3e0 &&		/* str x?, [x?, #imm] 64-bit */
	       ((insn >> 5) & 0x1f) == 18 &&	/* Rn = x18 (the SCS base) */
	       (insn & 0x1f) == 30;		/* Rt = x30 (the return address) */
}

static int resolve_one(const char *name, unsigned long *addr)
{
	struct kprobe kp = {
		.symbol_name = name,
	};
	int ret = register_kprobe(&kp);

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

static int patch_one(const char *name)
{
	const u32 patch[2] = { MOV_X0_0, RET };
	unsigned long addr;
	u32 entry, next;
	int ret;

	ret = resolve_one(name, &addr);
	if (ret) {
		pr_info("sgpatch: %s: not present (%d), skipped\n", name, ret);
		return ret;
	}
	entry = READ_ONCE(*(u32 *)addr);
	next = READ_ONCE(*((u32 *)addr + 1));
	if (entry != PACIASP && !is_scs_store(entry) && !force) {
		pr_info("sgpatch: %s at %px: entry %08x is not a recognised prologue, skipped\n",
			name, (void *)addr, entry);
		return -EINVAL;
	}
	ret = patch_words(addr, patch, ARRAY_SIZE(patch));
	if (ret) {
		pr_err("sgpatch: %s: write failed: %d\n", name, ret);
		return ret;
	}
	targets[nr_targets].addr = addr;
	targets[nr_targets].saved[0] = entry;
	targets[nr_targets].saved[1] = next;
	nr_targets++;
	pr_info("sgpatch: %s at %px patched (readback=%08x %08x)\n", name,
		(void *)addr, READ_ONCE(*(u32 *)addr), READ_ONCE(*((u32 *)addr + 1)));
	return 0;
}

static int __init sgpatch_init(void)
{
	char list[256];
	char *p, *tok;

	strscpy(list, symbol, sizeof(list));
	p = list;
	while ((tok = strsep(&p, ",")) != NULL) {
		while (*tok == ' ')
			tok++;
		if (!*tok)
			continue;
		if (nr_targets >= MAX_TARGETS) {
			pr_warn("sgpatch: too many targets, stopping\n");
			break;
		}
		patch_one(tok);
	}
	if (!nr_targets) {
		pr_err("sgpatch: none of '%s' could be patched; refusing\n", symbol);
		return -ENOENT;
	}
	pr_info("sgpatch: %d handler(s) patched\n", nr_targets);
	return 0;
}

static void __exit sgpatch_exit(void)
{
	int i;

	for (i = nr_targets - 1; i >= 0; i--)
		patch_words(targets[i].addr, targets[i].saved,
			    ARRAY_SIZE(targets[i].saved));
	pr_info("sgpatch: unloaded (%d restored)\n", nr_targets);
}

module_init(sgpatch_init);
module_exit(sgpatch_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Neutralise secureguard exec handlers in place");
