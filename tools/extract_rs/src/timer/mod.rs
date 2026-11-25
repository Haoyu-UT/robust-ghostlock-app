// SPDX-License-Identifier: Apache-2.0

//! CVE-2026-98260 (the `timer_98260` engine) derivation.
//!
//! Provenance: `derive`, `facts` and `report` are derived from
//! `YuKongA/CVE-2026-98260`, `tools/extract` (Apache-2.0; see `NOTICE` in this
//! directory). The kernel parsers they use are this crate's own modules, from
//! which the timer extractor's copies were originally vendored — the merged
//! extractor parses each image once and runs both derivations.
//!
//! Entry point: [`derive::analyze`] over the caller's already-parsed image.

pub mod derive;
pub mod facts;
pub mod report;

pub use derive::{Analysis, Options, Verdict};
