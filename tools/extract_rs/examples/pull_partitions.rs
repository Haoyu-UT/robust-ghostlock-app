//! Pull selected partitions from a `payload.bin`, an OTA ZIP, or an HTTP(S)
//! URL into a directory. A dev tool for sidecar recovery (e.g. an OTA's
//! `dtbo` when the vendor_boot carries no DTB); not part of the product.
//!
//! ```text
//! cargo run --example pull_partitions -- <source> <out-dir> <partition>...
//! ```
//!
//! Only the requested partitions' operation data is fetched.

use std::path::PathBuf;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let source = args
        .next()
        .expect("usage: pull_partitions <payload|ota-zip|url> <out-dir> <partition>...");
    let out = PathBuf::from(
        args.next()
            .expect("usage: pull_partitions <payload|ota-zip|url> <out-dir> <partition>..."),
    );
    let partitions: Vec<String> = args.collect();
    anyhow::ensure!(!partitions.is_empty(), "no partitions requested");

    std::fs::create_dir_all(&out)?;
    let view = payload_extract::input::open_for_extract_with(
        &source,
        &partitions,
        &payload_extract::input::OpenOptions {
            temp_dir: Some(out.clone()),
            ..Default::default()
        },
    )?;
    let config = payload_extract::extract::ExtractConfig {
        verify_ops: false,
        threads: 0,
        quiet: false,
        source_dir: None,
        out_config: None,
        progress: None,
    };
    payload_extract::extract::extract_partitions(&view, &out, &partitions, &config)?;
    println!("extracted {partitions:?} into {}", out.display());
    Ok(())
}
