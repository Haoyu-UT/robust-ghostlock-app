# DirtyFrag on 5.x — host/QEMU plan

**Status 2026-11-21: research plan, branch-local.** This file lives only on `feat/dirtyfrag-5x`.
If the experiment is dropped, the branch and this plan go with it; it is not a product design and
changes nothing until a gate below says it should.

## The question

Can the DirtyFrag page-cache chain root a 5.4 Qualcomm GKI device without the GhostLock **W1**
step? Two concrete targets: a Mi 11 Pro on `5.4.233-qgki-gbb70cde46897` and a Mi 11 on
`5.4.274-qgki-g76ac0b265f44`. Decide it on host and QEMU; spend device time only at the last gate.

## Why this shape

The product's module-free DirtyFrag pipeline **shares W1 with GhostLock** — SELinux to permissive
written by the futex route — and then writes file page cache, hooks `libc++`'s sentry constructor
and lets init's own credential carry the root daemon. On the two 5.4 devices every page-free
attempt dies inside the first W1 call, so that pipeline never reaches its own stage. There is no
cheaper GhostLock to borrow: the only geometrically feasible route there is `sigreturn`, and it is
the one that resets the phone.

The public Android reference (`diabl0w/DFRoot`) takes the other branch of the same trade: it does
**not** do W1. The page-cache write patches `libc++.so`'s `std::ostream::sentry` ctor; init (uid 0,
tid 1) reaps an orphan and runs the stub; the stub's child writes `u:r:vendor_modprobe:s0` and
`exec`s `/vendor/bin/insmod <ko>`; the module itself sets `selinux_state = false`, bypasses DEFEX
where applicable, and `call_usermodehelper`s the bootstrap. No waiter stamp, no scheduler race, no
W1 — the module is the SELinux switch.

That trade costs a per-kernel module. On these two devices that is the whole risk, and it is a
build-and-ABI question, which is answerable without the phone.

The primitive itself is long-lived: `CVE-2026-43284` is in scope from 2017-01-17 to the 2026-05-05
fix (`f4c50a4034e6`), is a deterministic logic bug with no timing window, and does not panic on
failure. Reference implementations: `V4bel/dirtyfrag` (original), `diabl0w/DFRoot` (Android),
`linnemanlabs/dirtyfrag-arm64` (arm64 notes — the rxrpc leg oopses in `flush_dcache_page`; the ESP
leg is the arm64-safe one).

## What is already measured (host, 2026-11-21)

Both 5.4 images carry the pieces the ESP leg needs, built in:

```
INET_ESP=y INET6_ESP=y XFRM=y XFRM_USER=y XFRM_INTERFACE=y
CRYPTO_AES=y CRYPTO_CBC=y CRYPTO_HMAC=y CRYPTO_SHA256=y
KPROBES=y KALLSYMS_ALL=y SECURITY_SELINUX=y
AF_RXRPC absent
```

- The star Image exports `kallsyms_lookup_name` alongside `call_usermodehelper[_exec]`, so a
  module can resolve `selinux_state` with the 5.4 interface (5.7+ no longer exports it).
- Both builds are LTO and rename the ESP entry points: `esp_input$<hash>`, `esp6_input$<hash>`.
  On the star Image `esp_input$…` calls `skb_cow_data` and carries **no** shared-frag (bit 5) test
  in the window before it — the pre-fix shape. `udp_sendpage` calls `ip_append_page`, i.e. the
  5.10-style splice path, not the 6.5+ `skb_splice_from_iter`.
- The extractor's current `dirtyfrag infeasible: the ESP fix level could not be measured` on these
  images is an **implementation gap** (the symbol fallback understands `.llvm.*` clones but not
  `$<hash>` ones), not a kernel fact.

## Gates

Each gate has a pass criterion and a stop condition. The order is cheapest-first; a failed gate
ends the branch's question at that point.

### G1 — static verdict (host)

Add the `$<hash>` LTO-clone fallback to the extractor's symbol resolution (unique match only, fail
closed on two candidates), then run the pipeline derivation on both 5.4 Images.

- **Pass:** `dirtyfrag feasible` on both, with the ESP guard found in the renamed functions and
  the send path found through `udp_sendpage → ip_append_page`.
- **Guard:** the full existing store corpus must be unchanged (no verdict or pipeline order moves).
- **Cross-check:** the hand disassembly of `esp_input$…` above.

### G2 — primitive gate on the exact Images (QEMU)

Boot each Image with an initramfs running a static probe that performs the reference operation
verbatim:

1. program a transport-mode ESP SA (AES-CBC + HMAC-SHA256) over `NETLINK_XFRM`, plus a
   UDP-encapsulation socket;
2. `splice()` a page of a file we own in the initramfs into a UDP send;
3. send with `IV = AES_ECB_DEC(key, old_block) ⊕ new_block`;
4. read the file back.

Non-escalating, disposable guest; the host distinguishes "page cache changed" from "file copied".

- **Pass:** the file's content changes on both `5.4.233` and `5.4.274`.
- **Fail:** the write does not land — the ESP leg is out on these images and the branch ends.
- **Control:** the same run with the fix's shape forced (or a no-splice variant) must not write.

### G3 — ROM preflight (host)

The kernel is not the whole chain; the ROM must supply the hook. From the two ROM payloads
(already on hand as OTAs), check:

- `libc++.so` / `libbase.so` carry the sentry constructor and a usable code cave — feed the real
  files to the app's own preflight;
- `crash_dump64` exists and can open `vendor_file`-labelled files;
- `/vendor/bin/insmod` exists and `u:r:vendor_modprobe` is a domain in the platform policy;
- `IpSecManager`/`IpSecService` are present (the app-side delivery, which QEMU cannot exercise).

- **Pass:** every item present on both ROMs.
- **Fail:** the chain needs a different bridge/hook on that ROM; record which item is missing.

### G4 — module ABI gate (host + QEMU)

Build a minimal module (look up `selinux_state` through `kallsyms_lookup_name`; call
`call_usermodehelper`) against a 5.4 GKI tree at the matching sublevel, patch `vermagic` and the
`__versions` CRCs from the Image's own `__ksymtab` data, then `insmod` it as guest root in a QEMU
boot of the exact Image.

- **Pass:** the vendor kernel accepts the module and it can resolve the symbols it needs.
- **Fail:** the vendor kernel is not GKI-compatible at that layer — the module form is dead and
  the branch ends. This is the cheap kill for the whole reference-style chain.
- **Caveat:** QEMU root is not the delivery path; the phone-side delivery is init's `insmod` after
  the page-cache hook (G2 + G3).

### G5 — device (owner)

Only after G1–G4. The residual device-only facts are the ROM's live SELinux/init timing and the
app→init hook on real userspace. Everything about the kernel primitive, the module ABI and the ROM
payload is settled by then.

## What this deliberately avoids

W1, stack stamps, carrier geometry, KASLR/offset derivation and scheduler races — the whole class
of failure this fork has measured on these phones. If a gate above fails, the answer is "this
device needs a different chain", not "another series of device runs".

## Deliverables

- The `$<hash>` clone fallback and its regression evidence (G1).
- A reusable "page-cache primitive gate" harness next to the existing route gate (G2), applicable
  to any 5.x image.
- A ROM preflight table per device (G3) and an ABI verdict per kernel (G4).
- If G1–G4 pass, an L-level plan for a module-delivery DirtyFrag pipeline alongside the module-free
  one — that is a separate design, not this file.

## Risks

- The module ABI (G4) is the concentrated risk: the vendor trees for these kernel builds were
  never published, so the module must be built cross-version and CRC-patched.
- QEMU does not model the phone's SELinux or init; G3 is the static stand-in, G5 the only real
  test.
- A page-cache write contaminates the cache; a device run must drop caches or reboot afterwards
  (a QEMU guest simply exits).

No prebuilt exploit binaries are run at any gate: the reference sources are read, and the probe is
re-implemented in this repo.
