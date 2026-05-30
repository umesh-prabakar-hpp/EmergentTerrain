//! Timestamped stdout/stderr logging.
//!
//! The engine logs via raw `println!`, which emits no wall-clock time, so
//! gpu-engine.log lines carried only a request_id + durations — the file
//! mtime (and the epoch embedded in `prof-gpu-<ms>` request ids) was the
//! only time anchor. paper-review's operational-log discovery had to fall
//! back to mtime + `ps` for exactly this reason.
//!
//! `logln!` / `elogln!` are drop-in replacements for `println!` /
//! `eprintln!` that prefix each line with a local-time RFC3339 stamp, e.g.
//!     2026-05-30T12:50:22.461-0400 startup.init pid=326393 bind=127.0.0.1:50051
//! Local time (not UTC) matches EmergentTerrain's logs/et-app.log so the two
//! correlate line-for-line.

/// Local-time stamp, e.g. `2026-05-30T12:50:22.461-0400`.
pub fn ts() -> String {
    chrono::Local::now().format("%Y-%m-%dT%H:%M:%S%.3f%z").to_string()
}

/// `println!` with a leading local-time timestamp. Same format args.
#[macro_export]
macro_rules! logln {
    ($($arg:tt)*) => {
        println!("{} {}", $crate::logging::ts(), format_args!($($arg)*))
    };
}

/// `eprintln!` with a leading local-time timestamp. Same format args.
#[macro_export]
macro_rules! elogln {
    ($($arg:tt)*) => {
        eprintln!("{} {}", $crate::logging::ts(), format_args!($($arg)*))
    };
}
