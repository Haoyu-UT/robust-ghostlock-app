obj-m := dfswitch.o

# The byte of `enforcing` inside `struct selinux_state`, supplied by build.sh:
# 1 on the 5.4 family (unconditional `disabled` first), 0 on 5.10/6.1.
ifeq ($(DFSWITCH_ENFORCING_OFFSET),)
$(error set DFSWITCH_ENFORCING_OFFSET (0 for 5.10/6.1, 1 for 5.4); use build.sh)
endif
ccflags-y += -DDFSWITCH_ENFORCING_OFFSET=$(DFSWITCH_ENFORCING_OFFSET)
