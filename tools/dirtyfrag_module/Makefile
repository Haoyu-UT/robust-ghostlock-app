obj-m := dfswitch.o

# The kernel line, supplied by build.sh: 54 (the 5.4 family) or 510 (5.10+).
# It fixes the `enforcing` byte: +1 on 5.4 (unconditional `disabled`), and on
# 5.10+ +0 unless CONFIG_SECURITY_SELINUX_DISABLE is set (then +1).
ifeq ($(DFSWITCH_LINE),)
$(error set DFSWITCH_LINE (54 or 510); use build.sh)
endif
ifneq ($(filter 54 510,$(DFSWITCH_LINE)),$(DFSWITCH_LINE))
$(error DFSWITCH_LINE must be 54 (the 5.4 family) or 510 (5.10+))
endif
ccflags-y += -DDFSWITCH_LINE=$(DFSWITCH_LINE)
