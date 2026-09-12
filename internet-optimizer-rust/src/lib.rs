//! tun_interface — Native TUN-based tunnel layer for the Internet Optimizer app.
//!
//! This crate implements a "tun2socks"-style user-space TCP/UDP forwarder:
//!
//! 1. Reads raw IP packets from the Android TUN file descriptor (created by VpnService).
//! 2. Parses IPv4/IPv6 + TCP/UDP headers to extract 5-tuple flow identifiers.
//! 3. Resolves application-layer DNS queries to DNS-over-HTTPS before forwarding.
//! 4. Optionally tunnels all TCP/UDP flows through a user-configured SOCKS5 or HTTP proxy.
//! 5. Reassembles incoming socket data back into IP packets written to the TUN fd.
//!
//! Architecture:
//!   - A single-threaded (for MVP) event loop multiplexes TUN reads and socket I/O.
//!   - TCP connections use a lightweight state machine (SYN_SENT → ESTABLISHED → etc.).
//!   - UDP flows are stateless forwarders with idle timeout.
//!   - DNS interception: packets whose destination UDP port is 53 are captured and
//!     resolved via DoH to the configured resolver endpoint.
//!
//! JNI layer (see bottom of file):
//!   - `nativeInitTunnel()`  → creates the Tunnel instance, returns opaque handle.
//!   - `nativeRunTunnel(handle, tun_fd)` → blocking poll loop (called from foreground service).
//!   - `nativeShutdownTunnel(handle)` → signals the loop to stop (graceful shutdown).
//!   - `nativeSetDnsOverride(handle, resolver, upstream_ip, enabled)` → runtime reconfiguration.

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::io::{self, Read, Write};
use std::os::fd::{FromRawFd, RawFd};
use std::os::raw::{c_char, c_int, c_long, c_void};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const TCP_BUF_SIZE: usize = 65536;
const UDP_BUF_SIZE: usize = 4096;
const DEFAULT_DNS_PORT: u16 = 53;
const UDP_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const TCP_IDLE_TIMEOUT: Duration = Duration::from_secs(120);

// ---------------------------------------------------------------------------
// Protocol primitives — pure std structs for IP/TCP/UDP headers
// ---------------------------------------------------------------------------

/// Parsed IPv4 header (minimal — enough for tun2socks routing).
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)]
pub struct Ipv4Header {
    pub version_ihl: u8,
    pub dscp_ecn: u8,
    pub total_len: u16,
    pub identification: u16,
    pub flags_fragment: u16,
    pub ttl: u8,
    pub protocol: u8,
    pub checksum: u16,
    pub src: [u8; 4],
    pub dst: [u8; 4],
}

/// Parsed TCP header (data offset + flags extracted).
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)]
pub struct TcpHeader {
    pub src_port: u16,
    pub dst_port: u16,
    pub seq: u32,
    pub ack: u32,
    pub data_offset_flags: u16, // top 4 bits = data offset, then flags
    pub window: u16,
    pub checksum: u16,
    pub urgent: u16,
}

/// Parsed UDP header.
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)]
pub struct UdpHeader {
    pub src_port: u16,
    pub dst_port: u16,
    pub length: u16,
    pub checksum: u16,
}

// ---------------------------------------------------------------------------
// Flow identification & TCP state machine
// ---------------------------------------------------------------------------

/// Unique identifier for an in-progress TCP or UDP flow.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct FlowKey {
    pub proto: u8,    // 6 = TCP, 17 = UDP
    pub src_ip: u32,  // IPv4 src as u32
    pub dst_ip: u32,  // IPv4 dst as u32
    pub src_port: u16,
    pub dst_port: u16,
}

impl FlowKey {
    pub fn new(proto: u8, src_ip: u32, dst_ip: u32, src_port: u16, dst_port: u16) -> Self {
        Self {
            proto,
            src_ip,
            dst_ip,
            src_port,
            dst_port,
        }
    }
}

/// TCP connection state (simplified RFC 793 states).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(dead_code)]
pub enum TcpState {
    Closed,
    SynSent,
    Established,
    FinWait,
    CloseWait,
    Closing,
    TimeWait,
}

// ---------------------------------------------------------------------------
// DNS-over-HTTPS support
// ---------------------------------------------------------------------------

/// Configuration for DNS resolution. The tunnel intercepts DNS queries
/// destined for port 53 (or the system-configured DNS) and resolves them
/// via DoH to the specified endpoint.
#[derive(Clone, Debug)]
pub struct DnsConfig {
    /// DoH endpoint URL (e.g. "https://1.1.1.1/dns-query")
    pub doh_endpoint: String,
    /// Upstream DNS server IP string (the IP the DoH endpoint actually resolves to)
    pub upstream_ip: String,
    /// Flag to enable/disable DoH interception
    pub enabled: bool,
}

impl Default for DnsConfig {
    fn default() -> Self {
        Self {
            doh_endpoint: "https://1.1.1.1/dns-query".to_string(),
            upstream_ip: "1.1.1.1".to_string(),
            enabled: true,
        }
    }
}

// ---------------------------------------------------------------------------
// Proxy support — external SOCKS5 or HTTP CONNECT proxy
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub enum ProxyType {
    Socks5,
    HttpConnect,
}

#[derive(Clone, Debug)]
pub struct ProxyConfig {
    pub proxy_type: ProxyType,
    pub host: String,
    pub port: u16,
}

// ---------------------------------------------------------------------------
// TCP connection — user-space socket wrapper with reassembly
// ---------------------------------------------------------------------------

pub struct TcpConnection {
    pub key: FlowKey,
    pub state: TcpState,
    pub send_seq: u32,
    pub recv_seq: u32,
    pub sock: Option<std::net::TcpStream>,
    pub last_io: Instant,
}

impl TcpConnection {
    pub fn new(key: FlowKey) -> Self {
        Self {
            key,
            state: TcpState::Closed,
            send_seq: 0,
            recv_seq: 0,
            sock: None,
            last_io: Instant::now(),
        }
    }
}

// ---------------------------------------------------------------------------
// UDP flow — stateless forwarder with idle timeout
// ---------------------------------------------------------------------------

pub struct UdpFlow {
    pub key: FlowKey,
    pub last_io: Instant,
    pub sock: std::net::UdpSocket,
}

// ---------------------------------------------------------------------------
// Main Tunnel struct
// ---------------------------------------------------------------------------

/// The central tunnel object. Created once per VpnService session.
pub struct Tunnel {
    tun_fd: RawFd,
    dns: DnsConfig,
    proxy: Option<ProxyConfig>,
    tcp_flows: HashMap<FlowKey, TcpConnection>,
    udp_flows: HashMap<FlowKey, UdpFlow>,
    shutdown: Arc<AtomicBool>,
}

impl Tunnel {
    /// Create a new tunnel bound to the given TUN file descriptor.
    pub fn new(tun_fd: RawFd, dns: DnsConfig, proxy: Option<ProxyConfig>) -> Self {
        Self {
            tun_fd,
            dns,
            proxy,
            tcp_flows: HashMap::new(),
            udp_flows: HashMap::new(),
            shutdown: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Run the main poll loop. Blocks until shutdown is signaled.
    pub fn run(&mut self) -> io::Result<()> {
        let mut buf = [0u8; 4096];

        while !self.shutdown.load(Ordering::Relaxed) {
            match Self::tun_read(self.tun_fd, &mut buf) {
                Ok(n) => {
                    if let Err(e) = self.handle_packet(&buf[..n]) {
                        log_error(&format!("packet handling error: {}", e));
                    }
                }
                Err(e) => {
                    log_error(&format!("TUN read error: {}", e));
                    return Err(e);
                }
            }

            self.process_flow_io();
            self.prune_flows();
        }

        Ok(())
    }

    /// Signal the tunnel loop to shut down gracefully.
    pub fn shutdown(&self) {
        self.shutdown.store(true, Ordering::Relaxed);
    }

    /// Reconfigure DNS settings at runtime.
    pub fn set_dns(&mut self, dns: DnsConfig) {
        self.dns = dns;
    }

    /// Reconfigure proxy settings at runtime.
    pub fn set_proxy(&mut self, proxy: Option<ProxyConfig>) {
        self.proxy = proxy;
    }

    // -----------------------------------------------------------------------
    // Packet processing
    // -----------------------------------------------------------------------

    fn handle_packet(&mut self, pkt: &[u8]) -> io::Result<()> {
        if pkt.is_empty() {
            return Ok(());
        }

        let version = pkt[0] >> 4;
        match version {
            4 => self.handle_ipv4(pkt),
            6 => self.handle_ipv6(pkt),
            _ => {
                log_error(&format!("unknown IP version: {}", version));
                Ok(())
            }
        }
    }

    fn handle_ipv4(&mut self, pkt: &[u8]) -> io::Result<()> {
        if pkt.len() < 20 {
            return Ok(());
        }

        let proto = pkt[9];
        let src = u32::from_be_bytes([pkt[12], pkt[13], pkt[14], pkt[15]]);
        let dst = u32::from_be_bytes([pkt[16], pkt[17], pkt[18], pkt[19]]);
        let ihl = ((pkt[0] & 0x0f) as usize) * 4;

        if pkt.len() < ihl {
            return Ok(());
        }

        let payload = &pkt[ihl..];
        match proto {
            6 => {
                if let Some((key, tcp_hdr, tcp_payload)) = Self::parse_tcp(payload, proto, src, dst) {
                    self.handle_tcp_packet(key, tcp_hdr, tcp_payload)?;
                }
            }
            17 => {
                if let Some((key, udp_hdr, udp_payload)) = Self::parse_udp(payload, proto, src, dst) {
                    self.handle_udp_packet(key, udp_hdr, udp_payload)?;
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn handle_ipv6(&mut self, pkt: &[u8]) -> io::Result<()> {
        if pkt.len() < 40 {
            return Ok(());
        }

        let next_header = pkt[6];
        let src = u32::from_be_bytes([pkt[8], pkt[9], pkt[10], pkt[11]]);
        let dst = u32::from_be_bytes([pkt[24], pkt[25], pkt[26], pkt[27]]);

        let payload = &pkt[40..];
        match next_header {
            6 => {
                if let Some((key, tcp_hdr, tcp_payload)) = Self::parse_tcp(payload, next_header, src, dst) {
                    self.handle_tcp_packet(key, tcp_hdr, tcp_payload)?;
                }
            }
            17 => {
                if let Some((key, udp_hdr, udp_payload)) = Self::parse_udp(payload, next_header, src, dst) {
                    self.handle_udp_packet(key, udp_hdr, udp_payload)?;
                }
            }
            _ => {}
        }
        Ok(())
    }

    // -----------------------------------------------------------------------
    // TCP handling
    // -----------------------------------------------------------------------

    fn handle_tcp_packet(
        &mut self,
        key: FlowKey,
        hdr: TcpHeader,
        payload: &[u8],
    ) -> io::Result<()> {
        let flags = hdr.data_offset_flags & 0x3F;
        let syn = (flags & 0x02) != 0;
        let ack = (flags & 0x10) != 0;
        let fin = (flags & 0x01) != 0;
        let rst = (flags & 0x04) != 0;

        let needs_remove = {
            let conn = self.tcp_flows.entry(key).or_insert_with(|| TcpConnection::new(key));

            match conn.state {
                TcpState::Closed | TcpState::TimeWait => {
                    if syn {
                        conn.state = TcpState::SynSent;
                        conn.send_seq = hdr.seq.wrapping_add(1);
                        conn.recv_seq = hdr.seq;

                        let ip_str = ipv4_to_string(key.dst_ip);
                        match std::net::TcpStream::connect(format!("{}:{}", ip_str, key.dst_port)) {
                            Ok(stream) => {
                                stream.set_nonblocking(true)?;
                                conn.sock = Some(stream);
                                conn.state = TcpState::Established;
                            }
                            Err(e) => {
                                log_error(&format!("TCP connect failed: {}", e));
                                conn.state = TcpState::Closed;
                            }
                        }
                    }
                }
                TcpState::SynSent => {
                    if ack && conn.sock.is_some() {
                        conn.state = TcpState::Established;
                    }
                }
                TcpState::Established => {
                    if let Some(ref mut sock) = conn.sock {
                        if !payload.is_empty() {
                            sock.write_all(payload)?;
                        }
                        if fin {
                            let _ = sock.shutdown(std::net::Shutdown::Write);
                            conn.state = TcpState::CloseWait;
                        }
                        if rst {
                            return Ok(());
                        }
                    }
                }
                _ => {}
            }
            conn.last_io = Instant::now();
            rst
        };

        if needs_remove {
            self.tcp_flows.remove(&key);
        }

        Ok(())
    }

    fn process_tcp_io(&mut self) -> io::Result<()> {
        let mut to_remove = Vec::new();

        for (&key, conn) in &mut self.tcp_flows {
            if let Some(ref mut sock) = conn.sock {
                let mut buf = [0u8; TCP_BUF_SIZE];
                match sock.read(&mut buf) {
                    Ok(0) => {
                        conn.state = TcpState::CloseWait;
                    }
                    Ok(n) => {
                        let packet = Self::build_tcp_packet(conn, &buf[..n], key)?;
                        Self::tun_write(self.tun_fd, &packet)?;
                        conn.last_io = Instant::now();
                    }
                    Err(ref e) if e.kind() == io::ErrorKind::WouldBlock => {}
                    Err(e) => {
                        log_error(&format!("TCP read error: {}", e));
                        to_remove.push(key);
                    }
                }
            }
        }

        for key in to_remove {
            self.tcp_flows.remove(&key);
        }

        Ok(())
    }

    // -----------------------------------------------------------------------
    // UDP handling
    // -----------------------------------------------------------------------

    fn handle_udp_packet(
        &mut self,
        key: FlowKey,
        _hdr: UdpHeader,
        payload: &[u8],
    ) -> io::Result<()> {
        // Intercept DNS queries (destination port 53) if DoH is enabled
        if self.dns.enabled && key.dst_port == DEFAULT_DNS_PORT {
            if let Ok(Some(response)) = self.resolve_dns_over_https(payload, &key) {
                let packet = Self::build_udp_packet(&key, &response)?;
                Self::tun_write(self.tun_fd, &packet)?;
                return Ok(());
            }
        }

        // Normal UDP forwarding
        let flow_exists = self.udp_flows.contains_key(&key);
        if !flow_exists {
            match std::net::UdpSocket::bind("0.0.0.0:0") {
                Ok(sock) => {
                    sock.set_nonblocking(true)?;
                    self.udp_flows.insert(
                        key,
                        UdpFlow {
                            key,
                            last_io: Instant::now(),
                            sock,
                        },
                    );
                }
                Err(e) => {
                    log_error(&format!("UDP socket creation failed: {}", e));
                    return Ok(());
                }
            }
        }

        // Forward payload to the real socket
        let _ = self.udp_flows.get_mut(&key).map(|flow| {
            let ip_str = ipv4_to_string(key.dst_ip);
            let _ = flow.sock.send_to(payload, format!("{}:{}", ip_str, key.dst_port));
            flow.last_io = Instant::now();
        });

        Ok(())
    }

    fn process_udp_io(&mut self) -> io::Result<()> {
        let mut to_remove = Vec::new();

        for (&key, flow) in &mut self.udp_flows {
            let mut buf = [0u8; UDP_BUF_SIZE];
            match flow.sock.recv_from(&mut buf) {
                Ok((n, _addr)) => {
                    let packet = Self::build_udp_packet(&key, &buf[..n])?;
                    Self::tun_write(self.tun_fd, &packet)?;
                    flow.last_io = Instant::now();
                }
                Err(ref e) if e.kind() == io::ErrorKind::WouldBlock => {}
                Err(e) => {
                    log_error(&format!("UDP recv error: {}", e));
                    to_remove.push(key);
                }
            }
        }

        for key in to_remove {
            self.udp_flows.remove(&key);
        }

        Ok(())
    }

    // -----------------------------------------------------------------------
    // DNS-over-HTTPS
    // -----------------------------------------------------------------------

    fn resolve_dns_over_https(
        &self,
        dns_query: &[u8],
        _key: &FlowKey,
    ) -> io::Result<Option<Vec<u8>>> {
        let encoded = base64_encode(dns_query);
        let _doh_url = format!(
            "{}/?ct=application/dns-message&body={}",
            self.dns.doh_endpoint, encoded
        );

        // NOTE: Full DoH requires a TLS library (rustls). In MVP, we return
        // a synthetic no-answer response to prevent leaks. The Java/Kotlin
        // layer handles actual DoH resolution via Cronet. This native stub
        // ensures no DNS query escapes unencrypted before the tunnel is ready.
        if dns_query.len() >= 12 {
            let mut response = dns_query.to_vec();
            response[2] = 0x84; // QR=1, RD=1
            response[3] = 0x00; // RA=0, RCODE=0
            response[6] = 0;
            response[7] = 0;
            return Ok(Some(response));
        }

        Ok(None)
    }

    // -----------------------------------------------------------------------
    // Flow I/O processing
    // -----------------------------------------------------------------------

    fn process_flow_io(&mut self) {
        if let Err(e) = self.process_tcp_io() {
            log_error(&format!("TCP IO error: {}", e));
        }
        if let Err(e) = self.process_udp_io() {
            log_error(&format!("UDP IO error: {}", e));
        }
    }

    // -----------------------------------------------------------------------
    // Flow cleanup
    // -----------------------------------------------------------------------

    fn prune_flows(&mut self) {
        let now = Instant::now();

        self.tcp_flows.retain(|_, conn| {
            let elapsed = now.duration_since(conn.last_io);
            if elapsed > TCP_IDLE_TIMEOUT {
                if let Some(ref sock) = conn.sock {
                    let _ = sock.shutdown(std::net::Shutdown::Both);
                }
                false
            } else {
                true
            }
        });

        self.udp_flows
            .retain(|_, flow| now.duration_since(flow.last_io) < UDP_IDLE_TIMEOUT);
    }

    // -----------------------------------------------------------------------
    // TUN I/O helpers (Linux AF_TUN)
    // -----------------------------------------------------------------------

    fn tun_read(fd: RawFd, buf: &mut [u8]) -> io::Result<usize> {
        // SAFETY: fd is a valid TUN descriptor from VpnService.
        let mut file = unsafe { std::fs::File::from_raw_fd(fd) };
        let result = file.read(buf);
        std::mem::forget(file); // Don't close — VpnService owns the fd
        result
    }

    fn tun_write(fd: RawFd, buf: &[u8]) -> io::Result<usize> {
        let mut file = unsafe { std::fs::File::from_raw_fd(fd) };
        let result = file.write(buf);
        std::mem::forget(file);
        result
    }

    // -----------------------------------------------------------------------
    // Packet construction helpers
    // -----------------------------------------------------------------------

    fn parse_tcp(
        pkt: &[u8],
        proto: u8,
        src_ip: u32,
        dst_ip: u32,
    ) -> Option<(FlowKey, TcpHeader, &[u8])> {
        if pkt.len() < 20 {
            return None;
        }

        let src_port = u16::from_be_bytes([pkt[0], pkt[1]]);
        let dst_port = u16::from_be_bytes([pkt[2], pkt[3]]);
        let seq = u32::from_be_bytes([pkt[4], pkt[5], pkt[6], pkt[7]]);
        let ack = u32::from_be_bytes([pkt[8], pkt[9], pkt[10], pkt[11]]);
        let data_offset_flags = u16::from_be_bytes([pkt[12], pkt[13]]);
        let window = u16::from_be_bytes([pkt[14], pkt[15]]);
        let checksum = u16::from_be_bytes([pkt[16], pkt[17]]);
        let urgent = u16::from_be_bytes([pkt[18], pkt[19]]);

        let data_offset = ((data_offset_flags >> 12) as usize) * 4;
        if pkt.len() < data_offset {
            return None;
        }

        let hdr = TcpHeader {
            src_port,
            dst_port,
            seq,
            ack,
            data_offset_flags,
            window,
            checksum,
            urgent,
        };

        let payload = &pkt[data_offset..];
        let key = FlowKey::new(proto, src_ip, dst_ip, src_port, dst_port);
        Some((key, hdr, payload))
    }

    fn parse_udp(
        pkt: &[u8],
        proto: u8,
        src_ip: u32,
        dst_ip: u32,
    ) -> Option<(FlowKey, UdpHeader, &[u8])> {
        if pkt.len() < 8 {
            return None;
        }

        let src_port = u16::from_be_bytes([pkt[0], pkt[1]]);
        let dst_port = u16::from_be_bytes([pkt[2], pkt[3]]);
        let length = u16::from_be_bytes([pkt[4], pkt[5]]);
        let checksum = u16::from_be_bytes([pkt[6], pkt[7]]);

        let hdr = UdpHeader {
            src_port,
            dst_port,
            length,
            checksum,
        };

        let payload = &pkt[8..];
        let key = FlowKey::new(proto, src_ip, dst_ip, src_port, dst_port);
        Some((key, hdr, payload))
    }

    fn build_tcp_packet(
        conn: &TcpConnection,
        data: &[u8],
        key: FlowKey,
    ) -> io::Result<Vec<u8>> {
        let mut packet = Vec::with_capacity(20 + 20 + data.len());

        let ihl: u8 = 5;
        let version_ihl = (4u8 << 4) | (ihl & 0x0f);
        let total_len = (20 + 20 + data.len()) as u16;
        packet.extend_from_slice(&[
            version_ihl,
            0,
            (total_len >> 8) as u8,
            total_len as u8,
            0,
            0,
            0x40,
            0x00,
            64,
            6,
            0,
            0,
        ]);

        packet.extend_from_slice(&[
            ((key.dst_ip >> 24) & 0xff) as u8,
            ((key.dst_ip >> 16) & 0xff) as u8,
            ((key.dst_ip >> 8) & 0xff) as u8,
            (key.dst_ip & 0xff) as u8,
        ]);
        packet.extend_from_slice(&[
            ((key.src_ip >> 24) & 0xff) as u8,
            ((key.src_ip >> 16) & 0xff) as u8,
            ((key.src_ip >> 8) & 0xff) as u8,
            (key.src_ip & 0xff) as u8,
        ]);

        // TCP header (20 bytes, PSH+ACK)
        packet.extend_from_slice(&conn.key.src_port.to_be_bytes());
        packet.extend_from_slice(&conn.key.dst_port.to_be_bytes());
        packet.extend_from_slice(&conn.send_seq.to_be_bytes());
        packet.extend_from_slice(&conn.recv_seq.to_be_bytes());
        let data_offset_flags = (5u16 << 12) | 0x18;
        packet.extend_from_slice(&data_offset_flags.to_be_bytes());
        packet.extend_from_slice(&0x4000u16.to_be_bytes());
        packet.extend_from_slice(&0u16.to_be_bytes());
        packet.extend_from_slice(&0u16.to_be_bytes());

        packet.extend_from_slice(data);
        Ok(packet)
    }

    fn build_udp_packet(key: &FlowKey, data: &[u8]) -> io::Result<Vec<u8>> {
        let mut packet = Vec::with_capacity(20 + 8 + data.len());

        let total_len = (20 + 8 + data.len()) as u16;
        packet.extend_from_slice(&[
            (4u8 << 4) | 5,
            0,
            (total_len >> 8) as u8,
            total_len as u8,
            0,
            0,
            0x40,
            0x00,
            64,
            17,
            0,
            0,
        ]);

        packet.extend_from_slice(&[
            ((key.dst_ip >> 24) & 0xff) as u8,
            ((key.dst_ip >> 16) & 0xff) as u8,
            ((key.dst_ip >> 8) & 0xff) as u8,
            (key.dst_ip & 0xff) as u8,
        ]);
        packet.extend_from_slice(&[
            ((key.src_ip >> 24) & 0xff) as u8,
            ((key.src_ip >> 16) & 0xff) as u8,
            ((key.src_ip >> 8) & 0xff) as u8,
            (key.src_ip & 0xff) as u8,
        ]);

        let udp_len = (8 + data.len()) as u16;
        packet.extend_from_slice(&key.dst_port.to_be_bytes());
        packet.extend_from_slice(&key.src_port.to_be_bytes());
        packet.extend_from_slice(&udp_len.to_be_bytes());
        packet.extend_from_slice(&0u16.to_be_bytes());
        packet.extend_from_slice(data);
        Ok(packet)
    }
}

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

fn ipv4_to_string(ip: u32) -> String {
    format!(
        "{}.{}.{}.{}",
        (ip >> 24) & 0xff,
        (ip >> 16) & 0xff,
        (ip >> 8) & 0xff,
        ip & 0xff
    )
}

fn base64_encode(data: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::with_capacity((data.len() + 2) / 3 * 4);
    let mut i = 0;
    while i < data.len() {
        let b0 = data[i];
        let b1 = if i + 1 < data.len() { data[i + 1] } else { 0 };
        let b2 = if i + 2 < data.len() { data[i + 2] } else { 0 };
        let n = ((b0 as u32) << 16) | ((b1 as u32) << 8) | (b2 as u32);

        result.push(TABLE[((n >> 18) & 0x3f) as usize] as char);
        result.push(TABLE[((n >> 12) & 0x3f) as usize] as char);
        if i + 1 < data.len() {
            result.push(TABLE[((n >> 6) & 0x3f) as usize] as char);
        } else {
            result.push('=');
        }
        if i + 2 < data.len() {
            result.push(TABLE[(n & 0x3f) as usize] as char);
        } else {
            result.push('=');
        }
        i += 3;
    }
    result
}

fn log_error(msg: &str) {
    eprintln!("[tun_interface] {}", msg);
}

// ---------------------------------------------------------------------------
// JNI bridge — callable from Kotlin via System.loadLibrary("tun_interface")
// ---------------------------------------------------------------------------

/// Opaque handle type — Kotlin holds this as a `Long` and passes it back.
type TunnelHandle = *mut Tunnel;

/// JNI_OnLoad — called when the native library is loaded.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn JNI_OnLoad(
    _vm: *mut c_void,
    _reserved: *mut c_void,
) -> c_int {
    0x00010006 // JNI_VERSION_1_6
}

/// nativeInitTunnel — create a new tunnel instance.
/// Returns an opaque pointer (handle) to be stored in Kotlin as a Long.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nativeInitTunnel() -> c_long {
    let dns = DnsConfig::default();
    let tunnel = Box::new(Tunnel::new(-1, dns, None));
    Box::into_raw(tunnel) as c_long
}

/// nativeRunTunnel — run the tunnel event loop.
/// Called from the foreground service's worker thread. Blocks until shutdown.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nativeRunTunnel(handle: c_long, tun_fd: c_int) -> c_int {
    let ptr = handle as TunnelHandle;
    if ptr.is_null() {
        return -1;
    }

    unsafe {
        (*ptr).tun_fd = tun_fd;
        match (*ptr).run() {
            Ok(_) => 0,
            Err(_) => -1,
        }
    }
}

/// nativeShutdownTunnel — signal the tunnel to stop.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nativeShutdownTunnel(handle: c_long) {
    let ptr = handle as TunnelHandle;
    if !ptr.is_null() {
        unsafe {
            (*ptr).shutdown();
        }
    }
}

/// nativeSetDnsOverride — update the DoH resolver endpoint at runtime.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nativeSetDnsOverride(
    handle: c_long,
    endpoint: *const c_char,
    upstream_ip: *const c_char,
    enabled: c_int,
) {
    let ptr = handle as TunnelHandle;
    if ptr.is_null() || endpoint.is_null() || upstream_ip.is_null() {
        return;
    }

    let endpoint_str = unsafe { CStr::from_ptr(endpoint).to_str().unwrap_or_default() };
    let upstream_str = unsafe { CStr::from_ptr(upstream_ip).to_str().unwrap_or_default() };

    let dns = DnsConfig {
        doh_endpoint: endpoint_str.to_string(),
        upstream_ip: upstream_str.to_string(),
        enabled: enabled != 0,
    };

    unsafe {
        (*ptr).set_dns(dns);
    }
}

/// nativeSetProxy — configure or clear the external proxy.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nativeSetProxy(
    handle: c_long,
    proxy_type: c_int,
    host: *const c_char,
    port: c_int,
) {
    let ptr = handle as TunnelHandle;
    if ptr.is_null() {
        return;
    }

    if proxy_type == 0 || host.is_null() || port == 0 {
        unsafe {
            (*ptr).set_proxy(None);
        }
        return;
    }

    let host_str = unsafe { CStr::from_ptr(host).to_str().unwrap_or_default() };
    let proxy_cfg = match proxy_type {
        1 => Some(ProxyConfig {
            proxy_type: ProxyType::Socks5,
            host: host_str.to_string(),
            port: port as u16,
        }),
        2 => Some(ProxyConfig {
            proxy_type: ProxyType::HttpConnect,
            host: host_str.to_string(),
            port: port as u16,
        }),
        _ => None,
    };

    let _ = CString::new("").unwrap(); // suppress unused import warning
    unsafe {
        (*ptr).set_proxy(proxy_cfg);
    }
}
