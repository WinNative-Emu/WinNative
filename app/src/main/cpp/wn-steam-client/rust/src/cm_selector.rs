//! Picks the connection manager to log on to, the way the official client does.
//!
//! The Steam client does not take the first entry of the CM directory: it pings
//! every advertised WebSocket CM (port 443 and the 27018/27019 alternates) and
//! connects to the endpoint with the lowest measured round-trip time, using the
//! advertised load only to break ties. Picking by load alone - or rotating
//! blindly - sends clients to far-away CMs on networks where only some of the
//! advertised datacenters are reachable, which is exactly what a mainland-China
//! connection sees: `sha1`/Perfect World CMs are a few milliseconds away while
//! the `sea1`/`hkg1` entries time out.
//!
//! Two practical differences from a naive probe implementation:
//!
//! * Endpoints that recently failed are skipped instead of being probed again on
//!   every reconnect, and being asked to pick again within [`RECONNECT_WINDOW`]
//!   counts as a failure of the previous pick (the client only re-picks when the
//!   connection did not come up).
//! * If every probe fails we still return something - the least-loaded endpoint -
//!   because a wrong guess is better than not trying at all.

use crate::cm_server::CmServer;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// How long a single endpoint probe may take before it counts as unreachable.
pub const PROBE_TIMEOUT: Duration = Duration::from_millis(1500);

/// Endpoints that failed this recently are skipped while other options remain.
pub const FAILURE_MEMORY: Duration = Duration::from_secs(120);

/// Being asked to pick again this soon after the previous pick means the client
/// could not establish a session, so the previous endpoint is remembered as bad.
pub const RECONNECT_WINDOW: Duration = Duration::from_secs(45);

/// Ports the official client probes for a WebSocket CM, in addition to whatever
/// the directory advertises.
const ALTERNATE_PORTS: [u16; 3] = [443, 27018, 27019];

#[derive(Clone, Debug, PartialEq)]
pub struct CmCandidate {
    pub host: String,
    pub port: u16,
    pub weighted_load: f32,
}

impl CmCandidate {
    pub fn url(&self) -> String {
        format!("wss://{}:{}/cmsocket/", self.host, self.port)
    }

    fn key(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ProbeOutcome {
    pub index: usize,
    pub rtt: Duration,
}

/// Builds the candidate list: every advertised endpoint plus the alternate ports
/// the official client also probes, de-duplicated by `host:port`.
pub fn candidates_for(servers: &[CmServer]) -> Vec<CmCandidate> {
    let mut out: Vec<CmCandidate> = Vec::new();
    for server in servers {
        if server.host.is_empty() {
            continue;
        }
        let mut ports: Vec<u16> = Vec::new();
        if server.port != 0 {
            ports.push(server.port);
        }
        for port in ALTERNATE_PORTS {
            if !ports.contains(&port) {
                ports.push(port);
            }
        }
        for port in ports {
            let candidate = CmCandidate {
                host: server.host.clone(),
                port,
                weighted_load: server.weighted_load,
            };
            if !out.iter().any(|existing| existing.key() == candidate.key()) {
                out.push(candidate);
            }
        }
    }
    out
}

struct BadEndpoint {
    key: String,
    at: Instant,
}

#[derive(Default)]
struct Memory {
    bad: Vec<BadEndpoint>,
    last_pick: Option<(String, Instant)>,
}

static MEMORY: Mutex<Option<Memory>> = Mutex::new(None);

fn with_memory<T>(f: impl FnOnce(&mut Memory) -> T) -> T {
    let mut guard = MEMORY.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let memory = guard.get_or_insert_with(Memory::default);
    f(memory)
}

fn prune(memory: &mut Memory, now: Instant) {
    memory
        .bad
        .retain(|entry| now.duration_since(entry.at) < FAILURE_MEMORY);
}

fn is_bad(key: &str) -> bool {
    let now = Instant::now();
    with_memory(|memory| {
        prune(memory, now);
        memory.bad.iter().any(|entry| entry.key == key)
    })
}

/// Notes that we are about to connect to `key`. If this happens inside
/// [`RECONNECT_WINDOW`] the previous endpoint evidently did not work, so it is
/// remembered as bad and the next pick will avoid it.
fn note_pick(key: &str) {
    let now = Instant::now();
    with_memory(|memory| {
        if let Some((previous, at)) = memory.last_pick.take() {
            if previous != key && now.duration_since(at) < RECONNECT_WINDOW {
                prune(memory, now);
                memory.bad.push(BadEndpoint {
                    key: previous,
                    at: now,
                });
            }
        }
        memory.last_pick = Some((key.to_string(), now));
    });
}

fn resolve(host: &str, port: u16) -> Vec<SocketAddr> {
    let Ok(addrs) = (host, port).to_socket_addrs() else {
        return Vec::new();
    };
    addrs.collect()
}

/// Opens a TCP connection to measure reachability and round-trip time.
fn probe_one(host: &str, port: u16) -> Option<Duration> {
    let started = Instant::now();
    let addrs = resolve(host, port);
    if addrs.is_empty() {
        return None;
    }
    for addr in addrs {
        if TcpStream::connect_timeout(&addr, PROBE_TIMEOUT).is_ok() {
            return Some(started.elapsed());
        }
    }
    None
}

/// Probes `candidates` in parallel and returns the fastest reachable one.
///
/// Candidates that recently failed are only probed when nothing else works, and
/// the advertised load breaks ties between endpoints with similar latency.
pub fn pick(candidates: &[CmCandidate]) -> Option<(usize, Duration)> {
    if candidates.is_empty() {
        return None;
    }
    let preferred: Vec<usize> = (0..candidates.len())
        .filter(|index| !is_bad(&candidates[*index].key()))
        .collect();
    let indices = if preferred.is_empty() {
        (0..candidates.len()).collect::<Vec<_>>()
    } else {
        preferred
    };

    // (candidate index, measured rtt when the probe connected)
    let measurements: Vec<(usize, Option<Duration>)> = std::thread::scope(|scope| {
        let handles: Vec<_> = indices
            .iter()
            .map(|index| {
                let candidate = &candidates[*index];
                scope.spawn(move || (*index, probe_one(&candidate.host, candidate.port)))
            })
            .collect();
        handles
            .into_iter()
            .map(|handle| handle.join().unwrap_or((0, None)))
            .collect()
    });

    let now = Instant::now();
    with_memory(|memory| {
        prune(memory, now);
        for (index, rtt) in &measurements {
            if rtt.is_none() {
                let key = candidates[*index].key();
                if !memory.bad.iter().any(|entry| entry.key == key) {
                    memory.bad.push(BadEndpoint { key, at: now });
                }
            }
        }
    });

    let mut best: Option<(usize, Duration)> = None;
    for (index, rtt) in measurements {
        let Some(rtt) = rtt else { continue };
        match best {
            None => best = Some((index, rtt)),
            Some((best_index, best_rtt)) => {
                // Latency wins unless the two endpoints are within 25 ms, in which
                // case the less loaded datacenter is preferred.
                let close = rtt.abs_diff(best_rtt) < Duration::from_millis(25);
                let better = if close {
                    candidates[index].weighted_load < candidates[best_index].weighted_load
                } else {
                    rtt < best_rtt
                };
                if better {
                    best = Some((index, rtt));
                }
            }
        }
    }

    match best {
        Some((index, rtt)) => {
            note_pick(&candidates[index].key());
            Some((index, rtt))
        }
        None => {
            // Nothing answered. Still return an endpoint so the client attempts a
            // connection instead of giving up, but do not treat it as a success.
            let fallback = indices.iter().copied().min_by(|a, b| {
                candidates[*a]
                    .weighted_load
                    .partial_cmp(&candidates[*b].weighted_load)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })?;
            note_pick(&candidates[fallback].key());
            None
        }
    }
}

/// Human-readable candidate list, used only for logging.
pub fn describe(candidates: &[CmCandidate]) -> String {
    candidates
        .iter()
        .map(|candidate| format!("{}:{} load={:.1}", candidate.host, candidate.port, candidate.weighted_load))
        .collect::<Vec<_>>()
        .join(", ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cm_server::CmTransport;

    fn server(host: &str, port: u16, load: f32) -> CmServer {
        CmServer {
            endpoint: format!("{host}:{port}"),
            host: host.to_string(),
            port,
            transport: CmTransport::WebSocket,
            realm: "steamglobal".to_string(),
            datacenter: String::new(),
            load: 0,
            weighted_load: load,
        }
    }

    #[test]
    fn candidates_expand_alternate_ports_once() {
        let candidates = candidates_for(&[server("cmp2-sha1.steamchina.com", 443, 30.0)]);
        assert_eq!(candidates.len(), 3);
        assert_eq!(candidates[0].port, 443);
        assert!(candidates.iter().any(|candidate| candidate.port == 27018));
        assert!(candidates.iter().any(|candidate| candidate.port == 27019));
    }

    #[test]
    fn candidates_deduplicate_hosts() {
        let candidates = candidates_for(&[
            server("a.example.com", 443, 1.0),
            server("a.example.com", 443, 1.0),
            server("b.example.com", 27018, 2.0),
        ]);
        assert_eq!(
            candidates
                .iter()
                .filter(|candidate| candidate.host == "a.example.com")
                .count(),
            3
        );
        assert_eq!(
            candidates
                .iter()
                .filter(|candidate| candidate.host == "b.example.com")
                .count(),
            3
        );
    }

    #[test]
    fn url_is_the_websocket_endpoint() {
        let candidate = CmCandidate {
            host: "cmp2-sha1.steamchina.com".to_string(),
            port: 27018,
            weighted_load: 0.0,
        };
        assert_eq!(candidate.url(), "wss://cmp2-sha1.steamchina.com:27018/cmsocket/");
    }

    #[test]
    fn unreachable_candidates_are_reported_as_none() {
        // 192.0.2.0/24 is TEST-NET-1: guaranteed not to answer.
        let mut candidates = candidates_for(&[server("192.0.2.1", 443, 1.0)]);
        candidates.truncate(1);
        assert_eq!(pick(&candidates), None);
    }

    #[test]
    fn picking_again_quickly_marks_the_previous_endpoint_bad() {
        let first = CmCandidate {
            host: "192.0.2.10".to_string(),
            port: 443,
            weighted_load: 0.0,
        };
        let second = CmCandidate {
            host: "192.0.2.11".to_string(),
            port: 443,
            weighted_load: 0.0,
        };
        note_pick(&first.key());
        note_pick(&second.key());
        assert!(is_bad(&first.key()));
        assert!(!is_bad(&second.key()));
    }
}
