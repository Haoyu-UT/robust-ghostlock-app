//! The store document must carry the W1 scratch its own page-free plans derive.
//!
//! The document's `ghostlock.anchor.scratch` is what makes a route page-free
//! (`page_free = policy ∧ scratch ∧ anchor`, `src/core/route/route_policy.hpp`).
//! The superset's `device_analysis.plans` state which routes have an
//! unconditional page-free placement for this very image, so a document that
//! carries no scratch while such a plan exists has dropped a capability it was
//! handed.
//!
//! The regression this pins (2026-11-23, `cb502571`): `shared_scratch` in
//! `options.rs` required *every* chosen route to agree, and `tcp_zerocopy` can
//! never be page-free, so its absent scratch vetoed the shared one on every
//! image with a feasible tcp route (every sampled 6.1 build).
//!
//! ```text
//! GHOSTLOCK_IMAGE_DIR=../../../../test-kernels \
//!     cargo test --test page_free_document -- --nocapture
//! ```

mod common;

use common::{collect_all, options_path};
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::Path;

/// A hex string (`"0x21f1000"`) or a plain JSON number.
fn number(value: &Value) -> Option<u64> {
    match value {
        Value::Number(n) => n.as_u64(),
        Value::String(s) => u64::from_str_radix(s.trim_start_matches("0x"), 16).ok(),
        _ => None,
    }
}

#[test]
fn documents_carry_the_scratch_their_plans_derive() {
    let Some(image_dir) = std::env::var_os("GHOSTLOCK_IMAGE_DIR") else {
        eprintln!("skipping: GHOSTLOCK_IMAGE_DIR is not set");
        return;
    };
    let corpus = collect_all(Path::new(&image_dir));
    assert!(
        !corpus.is_empty(),
        "no corpus entry under {}",
        Path::new(&image_dir).display()
    );

    let mut checked = 0usize;
    let mut violations: Vec<String> = Vec::new();

    for meta in &corpus {
        let release = &meta.release;
        let path = options_path(meta);
        let text = match std::fs::read_to_string(&path) {
            Ok(text) => text,
            Err(err) => {
                violations.push(format!(
                    "{release}: cannot read {}: {err}",
                    path.display()
                ));
                continue;
            }
        };
        let value: Value = match serde_json::from_str(&text) {
            Ok(value) => value,
            Err(err) => {
                violations.push(format!("{release}: invalid superset JSON: {err}"));
                continue;
            }
        };
        let Some(document) = value.get("document").filter(|doc| doc.is_object()) else {
            continue;
        };
        let Some(ghostlock) = document.get("ghostlock").filter(|g| g.is_object()) else {
            continue; // timer-only or module-only document: no native route
        };
        let Some(selected) = ghostlock.get("syscall").and_then(Value::as_str) else {
            continue;
        };
        let document_scratch = ghostlock
            .get("anchor")
            .and_then(|anchor| anchor.get("scratch"))
            .and_then(number);
        let document_anchor = ghostlock
            .get("anchor")
            .and_then(|anchor| anchor.get("image"))
            .and_then(number);
        /* The document's shared anchor comes from the first device's run: the
         * merge in `tests/common` keeps that device's shared sections and only
         * collects the other devices' physical pairs. */
        let Some(device) = value
            .get("device_analysis")
            .and_then(Value::as_array)
            .and_then(|devices| devices.first())
        else {
            continue;
        };
        let Some(plans) = device.get("plans").and_then(Value::as_array) else {
            continue;
        };
        /* An operator acceptance makes one conditioned pair selectable. The
         * document must carry that pair's derived anchor and scratch, and the
         * record must cover every device the document names (one shared
         * scratch). */
        if let Some(acceptance) =
            common::plan_acceptance(meta, meta.devices.first().map(String::as_str))
        {
            let mut derived: Vec<(u64, u64)> = Vec::new();
            for plan in plans {
                let Some(page_free) = plan.get("page_free").and_then(Value::as_array) else {
                    continue;
                };
                for candidate in page_free {
                    if candidate.get("anchor_name").and_then(Value::as_str)
                        != Some(acceptance.anchor.as_str())
                        || candidate.get("scratch_source").and_then(Value::as_str)
                            != Some(acceptance.scratch.as_str())
                    {
                        continue;
                    }
                    let Some(anchor) = candidate
                        .get("anchor")
                        .and_then(|anchor| anchor.get("image"))
                        .and_then(number)
                    else {
                        continue;
                    };
                    let Some(scratch) = candidate
                        .get("scratch")
                        .and_then(|scratch| scratch.get("image"))
                        .and_then(number)
                    else {
                        continue;
                    };
                    if !derived.contains(&(anchor, scratch)) {
                        derived.push((anchor, scratch));
                    }
                }
            }
            checked += 1;
            if derived.is_empty() {
                violations.push(format!(
                    "{release}: acceptance {}/{} names a pair the plans do not offer",
                    acceptance.anchor, acceptance.scratch
                ));
            } else if derived.len() > 1 {
                violations.push(format!(
                    "{release}: acceptance {}/{} derives different values across routes: {derived:?}",
                    acceptance.anchor, acceptance.scratch
                ));
            } else {
                let (anchor, scratch) = derived[0];
                if document_anchor != Some(anchor) || document_scratch != Some(scratch) {
                    violations.push(format!(
                        "{release}: acceptance {}/{} derives anchor 0x{anchor:x} / scratch 0x{scratch:x}, \
                         but the document carries anchor {} / scratch {}",
                        acceptance.anchor,
                        acceptance.scratch,
                        document_anchor
                            .map(|value| format!("0x{value:x}"))
                            .unwrap_or_else(|| "null".to_string()),
                        document_scratch
                            .map(|value| format!("0x{value:x}"))
                            .unwrap_or_else(|| "null".to_string()),
                    ));
                }
            }
            for device in meta.devices.iter().skip(1) {
                if common::plan_acceptance(meta, Some(device)).as_ref() != Some(&acceptance) {
                    violations.push(format!(
                        "{release}: device {device} does not carry the same acceptance as {}",
                        meta.devices[0]
                    ));
                }
            }
            continue;
        }
        let mut proposed: BTreeMap<String, (String, u64)> = BTreeMap::new();
        for plan in plans {
            let Some(route) = plan.get("route").and_then(Value::as_str) else {
                continue;
            };
            let Some(page_free) = plan.get("page_free").and_then(Value::as_array) else {
                continue;
            };
            /* `auto_plan` picks the first unconditioned page-free placement;
             * a conditioned one is not selectable without an operator call. */
            let Some(chosen) = page_free.iter().find(|candidate| {
                !candidate
                    .get("conditioned")
                    .and_then(Value::as_bool)
                    .unwrap_or(false)
            }) else {
                continue;
            };
            let Some(scratch) = chosen
                .get("scratch")
                .and_then(|scratch| scratch.get("image"))
                .and_then(number)
            else {
                continue;
            };
            let name = format!(
                "{} + {}",
                chosen
                    .get("anchor_name")
                    .and_then(Value::as_str)
                    .unwrap_or("?"),
                chosen
                    .get("scratch_source")
                    .and_then(Value::as_str)
                    .unwrap_or("?"),
            );
            proposed.insert(route.to_string(), (name, scratch));
        }
        if proposed.is_empty() {
            continue; // no route has an unconditional page-free plan
        }
        checked += 1;
        let mut values = proposed.values().map(|(_, scratch)| *scratch);
        let want = values.next().expect("proposed is not empty");
        if values.any(|scratch| scratch != want) {
            violations.push(format!(
                "{release}: page-free routes disagree on the scratch: {proposed:?} \
                 (the document carries one shared scratch; this needs a per-route home)"
            ));
            continue;
        }
        if document_scratch != Some(want) {
            let detail = proposed
                .iter()
                .map(|(route, (name, scratch))| format!("{route} <- {name} (0x{scratch:x})"))
                .collect::<Vec<_>>()
                .join(", ");
            violations.push(format!(
                "{release}: document scratch is {}, plans derive 0x{want:x} \
                 (selected {selected}): {detail}",
                document_scratch
                    .map(|scratch| format!("0x{scratch:x}"))
                    .unwrap_or_else(|| "null".to_string()),
            ));
        }
    }

    assert!(
        checked > 0,
        "no entry had an unconditional page-free plan; is GHOSTLOCK_IMAGE_DIR the store?"
    );
    assert!(
        violations.is_empty(),
        "{} of {checked} page-free entries carry no matching document scratch:\n  {}",
        violations.len(),
        violations.join("\n  ")
    );
    eprintln!("page-free documents: {checked} entries checked, all carry their scratch");
}

#[test]
fn plan_record_parses() {
    let text = "# comment\naccept = dump_skip.zeroes + s3c24xx_serial_ports\nnote = x\n";
    let acceptance = common::parse_acceptance(text).expect("accept line");
    assert_eq!(acceptance.anchor, "dump_skip.zeroes");
    assert_eq!(acceptance.scratch, "s3c24xx_serial_ports");
    assert!(common::parse_acceptance("accept = only-an-anchor\n").is_none());
    assert!(common::parse_acceptance("note = dump_skip.zeroes + bm_pmd\n").is_none());
}
