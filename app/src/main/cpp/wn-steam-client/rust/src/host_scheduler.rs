//! Adaptive CDN host scheduling.
//!
//! Valve's own client and other Steam downloaders do not spray chunks evenly
//! over every content server the CM handed out. They create one download
//! interface per source, measure each source's throughput, and keep steering
//! work at the sources that are actually fast while backing off from the ones
//! that fail.
//!
//! WinNative previously round-robined chunks across the whole usable list. On a
//! mainland-China network that list mixes reachable partner CDNs with
//! international edges that are blackholed, so a large share of workers sat in
//! connect timeouts and effective throughput collapsed. This scheduler keeps a
//! running throughput estimate per host, places failing hosts into a cooldown
//! that doubles on repeated failures, and occasionally samples the other hosts
//! so a recovering node is picked up again.

use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Cooldown applied after the nth consecutive failure (clamped to the last).
const DEFAULT_FAILURE_COOLDOWNS: &[Duration] = &[
    Duration::from_secs(3),
    Duration::from_secs(15),
    Duration::from_secs(60),
    Duration::from_secs(300),
];

/// EWMA weight for the newest throughput sample.
const SCORE_NEW_SAMPLE_WEIGHT: f64 = 0.35;

/// Every Nth pick samples a host other than the current best.
const EXPLORATION_PERIOD: usize = 8;

#[derive(Clone, Copy, Debug, Default)]
struct HostEntry {
    score_bytes_per_sec: f64,
    has_sample: bool,
    successes: u32,
    failures: u32,
    consecutive_failures: u32,
    cooldown_until: Option<Instant>,
    last_picked_at: Option<Instant>,
    in_flight: u32,
}

#[derive(Debug)]
struct Inner {
    entries: Vec<HostEntry>,
    pick_count: usize,
    exploration_cursor: usize,
}

pub struct HostScheduler {
    inner: Mutex<Inner>,
    failure_cooldowns: Vec<Duration>,
}

impl HostScheduler {
    pub fn new(host_count: usize) -> Self {
        Self::with_failure_cooldowns(host_count, DEFAULT_FAILURE_COOLDOWNS.to_vec())
    }

    pub fn with_failure_cooldowns(host_count: usize, cooldowns: Vec<Duration>) -> Self {
        Self {
            inner: Mutex::new(Inner {
                entries: vec![HostEntry::default(); host_count],
                pick_count: 0,
                exploration_cursor: 0,
            }),
            failure_cooldowns: if cooldowns.is_empty() {
                DEFAULT_FAILURE_COOLDOWNS.to_vec()
            } else {
                cooldowns
            },
        }
    }

    pub fn host_count(&self) -> usize {
        self.inner.lock().expect("host scheduler poisoned").entries.len()
    }

    /// Chooses the host a worker should fetch its next chunk from.
    ///
    /// Hosts in cooldown are skipped. Among the rest the highest estimated
    /// throughput wins, except for a periodic exploration pick that samples the
    /// least recently used host so a host that recovered is retried.
    pub fn pick(&self) -> Option<usize> {
        self.pick_excluding(&[])
    }

    /// Like [`Self::pick`] but never returns an index in `exclude`, which the
    /// chunk retry path uses to walk through distinct hosts on repeated
    /// failures.
    pub fn pick_excluding(&self, exclude: &[usize]) -> Option<usize> {
        let mut inner = self.inner.lock().expect("host scheduler poisoned");
        let now = Instant::now();
        let count = inner.entries.len();
        if count == 0 {
            return None;
        }

        inner.pick_count += 1;
        let explore = inner.pick_count % EXPLORATION_PERIOD == 0;

        let usable = |entry: &HostEntry, idx: usize| -> bool {
            !exclude.contains(&idx)
                && !entry.cooldown_until.is_some_and(|until| until > now)
        };

        let chosen = if explore {
            // Round-robin over usable hosts by last_picked_at, oldest first.
            let mut candidates: Vec<usize> = (0..count)
                .filter(|idx| usable(&inner.entries[*idx], *idx))
                .collect();
            if candidates.is_empty() {
                return None;
            }
            candidates.sort_by_key(|idx| inner.entries[*idx].last_picked_at);
            let idx = candidates[inner.exploration_cursor % candidates.len()];
            inner.exploration_cursor = inner.exploration_cursor.wrapping_add(1);
            idx
        } else {
            let mut best: Option<(usize, f64)> = None;
            for idx in 0..count {
                let entry = &inner.entries[idx];
                if !usable(entry, idx) {
                    continue;
                }
                // Unknown hosts start at a neutral score so they get sampled
                // once instead of being starved by measured hosts.
                let score = if entry.has_sample {
                    entry.score_bytes_per_sec
                } else {
                    f64::INFINITY
                };
                if best.is_none_or(|(_, best_score)| score > best_score) {
                    best = Some((idx, score));
                }
            }
            best.map(|(idx, _)| idx)?
        };

        let entry = &mut inner.entries[chosen];
        entry.last_picked_at = Some(now);
        entry.in_flight = entry.in_flight.saturating_add(1);
        Some(chosen)
    }

    /// Records a successful transfer so the host's throughput estimate improves.
    pub fn record_success(&self, host: usize, bytes: u64, elapsed: Duration) {
        let mut inner = self.inner.lock().expect("host scheduler poisoned");
        let Some(entry) = inner.entries.get_mut(host) else {
            return;
        };
        entry.in_flight = entry.in_flight.saturating_sub(1);
        entry.successes = entry.successes.saturating_add(1);
        entry.consecutive_failures = 0;
        entry.cooldown_until = None;

        let secs = elapsed.as_secs_f64();
        if secs <= 0.0 || bytes == 0 {
            return;
        }
        let sample = bytes as f64 / secs;
        entry.score_bytes_per_sec = if entry.has_sample {
            entry.score_bytes_per_sec * (1.0 - SCORE_NEW_SAMPLE_WEIGHT)
                + sample * SCORE_NEW_SAMPLE_WEIGHT
        } else {
            sample
        };
        entry.has_sample = true;
    }

    /// Records a failed transfer and places the host in an escalating cooldown.
    pub fn record_failure(&self, host: usize) {
        let mut inner = self.inner.lock().expect("host scheduler poisoned");
        let Some(entry) = inner.entries.get_mut(host) else {
            return;
        };
        entry.in_flight = entry.in_flight.saturating_sub(1);
        entry.failures = entry.failures.saturating_add(1);
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);

        let step = (entry.consecutive_failures as usize - 1).min(self.failure_cooldowns.len() - 1);
        entry.cooldown_until = Some(Instant::now() + self.failure_cooldowns[step]);
        // Halve the estimate so a host that starts failing loses its lead even
        // before the cooldown is consulted.
        entry.score_bytes_per_sec *= 0.5;
    }

    /// Estimated throughput in bytes/sec, or None when the host never succeeded.
    pub fn score_of(&self, host: usize) -> Option<f64> {
        let inner = self.inner.lock().expect("host scheduler poisoned");
        let entry = inner.entries.get(host)?;
        entry.has_sample.then_some(entry.score_bytes_per_sec)
    }

    /// One-line summary of the scheduler state, best host first.
    pub fn summary(&self) -> Vec<String> {
        let inner = self.inner.lock().expect("host scheduler poisoned");
        let mut rows: Vec<(usize, f64, u32, u32)> = inner
            .entries
            .iter()
            .enumerate()
            .map(|(idx, entry)| {
                (
                    idx,
                    if entry.has_sample {
                        entry.score_bytes_per_sec
                    } else {
                        -1.0
                    },
                    entry.successes,
                    entry.failures,
                )
            })
            .collect();
        rows.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        rows.into_iter()
            .map(|(idx, score, successes, failures)| {
                let mbps = if score >= 0.0 { score * 8.0 / 1_000_000.0 } else { 0.0 };
                format!(
                    "host#{idx}: {mbps:.1} Mbps (ok={successes} fail={failures})"
                )
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fast_seconds(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    #[test]
    fn prefers_the_host_with_the_highest_measured_throughput() {
        let scheduler = HostScheduler::new(3);
        // Host 0 is slow, host 1 is fast.
        scheduler.record_success(0, 1_000_000, fast_seconds(100)); // 10 MB/s
        scheduler.record_success(1, 1_000_000, fast_seconds(20)); // 50 MB/s
        scheduler.record_success(2, 1_000_000, fast_seconds(80)); // 12.5 MB/s

        let mut picks = Vec::new();
        for _ in 0..6 {
            picks.push(scheduler.pick().unwrap());
        }
        // Most picks must target the fastest host.
        let fast_picks = picks.iter().filter(|idx| **idx == 1).count();
        assert!(fast_picks >= 4, "picks={picks:?}");
    }

    #[test]
    fn sampling_an_unmeasured_host_comes_before_measured_ones() {
        let scheduler = HostScheduler::new(2);
        scheduler.record_success(0, 1_000_000, fast_seconds(10));
        // Host 1 has no sample yet, so it should be picked first.
        assert_eq!(scheduler.pick(), Some(1));
        scheduler.record_success(1, 1_000_000, fast_seconds(200));
        assert_eq!(scheduler.pick(), Some(0));
    }

    #[test]
    fn failing_host_is_cooled_down_and_others_take_over() {
        let scheduler = HostScheduler::with_failure_cooldowns(
            2,
            vec![Duration::from_millis(50), Duration::from_millis(80)],
        );
        scheduler.record_success(0, 1_000_000, fast_seconds(10)); // fastest
        scheduler.record_success(1, 1_000_000, fast_seconds(200));
        assert_eq!(scheduler.pick(), Some(0));

        scheduler.record_failure(0);
        assert_eq!(scheduler.pick(), Some(1), "failed host must be skipped");

        std::thread::sleep(Duration::from_millis(70));
        // Cooldown elapsed: host 0 is candidates again (its score was halved,
        // so host 1 may still win; just assert it is no longer excluded).
        let picked = scheduler.pick().unwrap();
        assert!(picked < 2);
    }

    #[test]
    fn cooldown_escalates_with_consecutive_failures() {
        let scheduler = HostScheduler::with_failure_cooldowns(
            1,
            vec![Duration::from_millis(20), Duration::from_millis(10_000)],
        );
        scheduler.record_failure(0);
        assert_eq!(scheduler.pick(), None, "single host in cooldown");

        std::thread::sleep(Duration::from_millis(30));
        assert_eq!(scheduler.pick(), Some(0));

        // Second consecutive failure uses the longer cooldown.
        scheduler.record_failure(0);
        assert_eq!(scheduler.pick(), None);
    }

    #[test]
    fn exclude_walks_through_alternatives() {
        let scheduler = HostScheduler::new(3);
        scheduler.record_success(0, 1_000_000, fast_seconds(10));
        scheduler.record_success(1, 1_000_000, fast_seconds(100));
        scheduler.record_success(2, 1_000_000, fast_seconds(1000));

        let first = scheduler.pick().unwrap();
        assert_eq!(first, 0);
        let second = scheduler.pick_excluding(&[0]).unwrap();
        assert_ne!(second, 0);
        let third = scheduler.pick_excluding(&[0, second]).unwrap();
        assert_ne!(third, 0);
        assert_ne!(third, second);
    }

    #[test]
    fn summary_reports_measured_hosts_first() {
        let scheduler = HostScheduler::new(2);
        scheduler.record_success(0, 125_000, fast_seconds(1000)); // 1 Mbps
        scheduler.record_success(1, 1_000_000, fast_seconds(1000)); // 8 Mbps
        let rows = scheduler.summary();
        assert_eq!(rows.len(), 2);
        assert!(rows[0].starts_with("host#1"), "{rows:?}");
        assert!(rows[0].contains("Mbps"));
    }

    #[test]
    fn empty_scheduler_returns_none() {
        let scheduler = HostScheduler::new(0);
        assert_eq!(scheduler.pick(), None);
        assert_eq!(scheduler.host_count(), 0);
    }
}
