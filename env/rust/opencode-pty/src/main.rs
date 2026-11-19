// OpenCode for Android — Native Pure Bionic ARM64 PTY & Pipe Daemon (Protocol v9)
// Copyright (c) 2026 Yhoanes (https://github.com/Yhoanes/opencode-android)
// Licensed under BY-NC + Apache 2.0 (see repository LICENSE).

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::{FromRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const PROTOCOL_VERSION: u32 = 9;
const MAX_FRAME_LEN: usize = 8 * 1024 * 1024;
const MAX_PTY_RING_BYTES: usize = 2 * 1024 * 1024;

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    if let Ok(mut f) = File::open("/dev/urandom") {
        let _ = f.read_exact(&mut buf);
    } else {
        let seed = now_ms();
        for (i, b) in buf.iter_mut().enumerate() {
            *b = ((seed >> ((i % 8) * 8)) as u8).wrapping_add((i as u8).wrapping_mul(37));
        }
    }
    hex::encode(buf)
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn signal_from_str_or_num(val: &Value) -> Option<libc::c_int> {
    if let Some(n) = val.as_i64() {
        return Some(n as libc::c_int);
    }
    let s = val.as_str()?.trim().to_uppercase();
    let name = s.strip_prefix("SIG").unwrap_or(&s);
    match name {
        "INT" => Some(libc::SIGINT),
        "TERM" => Some(libc::SIGTERM),
        "KILL" => Some(libc::SIGKILL),
        "HUP" => Some(libc::SIGHUP),
        "QUIT" => Some(libc::SIGQUIT),
        "USR1" => Some(libc::SIGUSR1),
        "USR2" => Some(libc::SIGUSR2),
        "CONT" => Some(libc::SIGCONT),
        "STOP" => Some(libc::SIGSTOP),
        "WINCH" => Some(libc::SIGWINCH),
        _ => name.parse::<libc::c_int>().ok(),
    }
}

fn signal_name(sig: libc::c_int) -> String {
    match sig {
        libc::SIGINT => "SIGINT".to_string(),
        libc::SIGTERM => "SIGTERM".to_string(),
        libc::SIGKILL => "SIGKILL".to_string(),
        libc::SIGHUP => "SIGHUP".to_string(),
        libc::SIGQUIT => "SIGQUIT".to_string(),
        libc::SIGABRT => "SIGABRT".to_string(),
        libc::SIGSEGV => "SIGSEGV".to_string(),
        libc::SIGPIPE => "SIGPIPE".to_string(),
        other => format!("SIG{}", other),
    }
}

#[derive(Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
enum Lifecycle {
    Running,
    Exited {
        exit_code: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        signal: Option<String>,
    },
    #[allow(dead_code)]
    Failed { message: String },
}

#[derive(Clone, Serialize)]
struct PtyMeta {
    title: String,
    user_title: Option<String>,
    foreground_process: Option<String>,
    cols: u16,
    rows: u16,
}

#[derive(Clone, Serialize)]
struct PipeMeta {
    output_path: String,
    output_lines: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    output_error: Option<String>,
    metadata: Map<String, Value>,
}

#[derive(Clone, Serialize)]
struct ProcessSnapshot {
    id: u64,
    mode: &'static str,
    pid: Option<u32>,
    command: Vec<String>,
    cwd: String,
    group_id: String,
    lifecycle: Lifecycle,
    output_head: u64,
    output_tail: u64,
    started_at: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    exited_at: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pty: Option<PtyMeta>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pipe: Option<PipeMeta>,
}

struct ScreenTracker {
    cols: usize,
    rows: usize,
    grid: Vec<Vec<char>>,
    cx: usize,
    cy: usize,
    esc_state: u8, // 0=normal, 1=ESC, 2=CSI, 3=OSC
    csi_buf: String,
    osc_buf: String,
}

impl ScreenTracker {
    fn new(cols: u16, rows: u16) -> Self {
        let c = (cols.max(2)) as usize;
        let r = (rows.max(2)) as usize;
        Self {
            cols: c,
            rows: r,
            grid: vec![vec![' '; c]; r],
            cx: 0,
            cy: 0,
            esc_state: 0,
            csi_buf: String::new(),
            osc_buf: String::new(),
        }
    }

    fn resize(&mut self, cols: u16, rows: u16) {
        let c = (cols.max(2)) as usize;
        let r = (rows.max(2)) as usize;
        let mut next = vec![vec![' '; c]; r];
        for y in 0..r.min(self.rows) {
            for x in 0..c.min(self.cols) {
                next[y][x] = self.grid[y][x];
            }
        }
        self.cols = c;
        self.rows = r;
        self.grid = next;
        self.cx = self.cx.min(c - 1);
        self.cy = self.cy.min(r - 1);
    }

    fn scroll_up(&mut self) {
        if self.rows > 1 {
            self.grid.remove(0);
            self.grid.push(vec![' '; self.cols]);
        } else if self.rows == 1 {
            self.grid[0].fill(' ');
        }
    }

    fn feed(&mut self, data: &[u8]) -> Option<String> {
        let mut new_title = None;
        let text = String::from_utf8_lossy(data);
        for ch in text.chars() {
            match self.esc_state {
                0 => match ch {
                    '\x1b' => {
                        self.esc_state = 1;
                    }
                    '\r' => {
                        self.cx = 0;
                    }
                    '\n' => {
                        if self.cy + 1 >= self.rows {
                            self.scroll_up();
                        } else {
                            self.cy += 1;
                        }
                    }
                    '\x08' => {
                        if self.cx > 0 {
                            self.cx -= 1;
                        }
                    }
                    '\t' => {
                        let next_tab = ((self.cx / 8) + 1) * 8;
                        self.cx = next_tab.min(self.cols - 1);
                    }
                    c if c >= ' ' => {
                        if self.cx >= self.cols {
                            self.cx = 0;
                            if self.cy + 1 >= self.rows {
                                self.scroll_up();
                            } else {
                                self.cy += 1;
                            }
                        }
                        self.grid[self.cy][self.cx] = c;
                        self.cx += 1;
                    }
                    _ => {}
                },
                1 => match ch {
                    '[' => {
                        self.esc_state = 2;
                        self.csi_buf.clear();
                    }
                    ']' => {
                        self.esc_state = 3;
                        self.osc_buf.clear();
                    }
                    'c' => {
                        for row in &mut self.grid {
                            row.fill(' ');
                        }
                        self.cx = 0;
                        self.cy = 0;
                        self.esc_state = 0;
                    }
                    _ => {
                        self.esc_state = 0;
                    }
                },
                2 => {
                    if ('@'..='~').contains(&ch) {
                        self.apply_csi(ch);
                        self.esc_state = 0;
                    } else if self.csi_buf.len() < 64 {
                        self.csi_buf.push(ch);
                    } else {
                        self.esc_state = 0;
                    }
                }
                3 => {
                    if ch == '\x07' {
                        if let Some(t) = Self::extract_osc_title(&self.osc_buf) {
                            new_title = Some(t);
                        }
                        self.esc_state = 0;
                    } else if ch == '\\' && self.osc_buf.ends_with('\x1b') {
                        self.osc_buf.pop();
                        if let Some(t) = Self::extract_osc_title(&self.osc_buf) {
                            new_title = Some(t);
                        }
                        self.esc_state = 0;
                    } else if self.osc_buf.len() < 256 {
                        self.osc_buf.push(ch);
                    } else {
                        self.esc_state = 0;
                    }
                }
                _ => self.esc_state = 0,
            }
        }
        new_title
    }

    fn extract_osc_title(osc: &str) -> Option<String> {
        let rest = osc
            .strip_prefix("0;")
            .or_else(|| osc.strip_prefix("2;"))?;
        let trimmed = rest.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_string())
        }
    }

    fn apply_csi(&mut self, final_byte: char) {
        let params: Vec<usize> = self
            .csi_buf
            .trim_start_matches('?')
            .split(';')
            .map(|s| s.parse::<usize>().unwrap_or(0))
            .collect();
        match final_byte {
            'H' | 'f' => {
                let row = params.first().copied().unwrap_or(1).max(1) - 1;
                let col = params.get(1).copied().unwrap_or(1).max(1) - 1;
                self.cy = row.min(self.rows - 1);
                self.cx = col.min(self.cols - 1);
            }
            'J' => {
                let mode = params.first().copied().unwrap_or(0);
                if mode == 2 || mode == 3 {
                    for row in &mut self.grid {
                        row.fill(' ');
                    }
                    self.cx = 0;
                    self.cy = 0;
                } else if mode == 0 {
                    for x in self.cx..self.cols {
                        self.grid[self.cy][x] = ' ';
                    }
                    for y in (self.cy + 1)..self.rows {
                        self.grid[y].fill(' ');
                    }
                }
            }
            'K' => {
                let mode = params.first().copied().unwrap_or(0);
                if mode == 0 {
                    for x in self.cx..self.cols {
                        self.grid[self.cy][x] = ' ';
                    }
                } else if mode == 2 {
                    self.grid[self.cy].fill(' ');
                }
            }
            'A' => {
                let n = params.first().copied().unwrap_or(1).max(1);
                self.cy = self.cy.saturating_sub(n);
            }
            'B' => {
                let n = params.first().copied().unwrap_or(1).max(1);
                self.cy = (self.cy + n).min(self.rows - 1);
            }
            'C' => {
                let n = params.first().copied().unwrap_or(1).max(1);
                self.cx = (self.cx + n).min(self.cols - 1);
            }
            'D' => {
                let n = params.first().copied().unwrap_or(1).max(1);
                self.cx = self.cx.saturating_sub(n);
            }
            _ => {}
        }
    }

    fn lines(&self, limit: Option<usize>) -> Vec<String> {
        let all: Vec<String> = self
            .grid
            .iter()
            .map(|r| r.iter().collect::<String>().trim_end().to_string())
            .collect();
        match limit {
            Some(n) if n < all.len() => all[all.len() - n..].to_vec(),
            _ => all,
        }
    }
}

enum SubFrame {
    Output {
        start: u64,
        end: u64,
        data: Vec<u8>,
    },
    Json(Value),
    Close,
}

struct Subscriber {
    #[allow(dead_code)]
    sub_id: u64,
    #[allow(dead_code)]
    attachment_id: Option<String>,
    tx: mpsc::Sender<SubFrame>,
}

struct PtyRuntime {
    master_fd: RawFd,
    ring: VecDeque<u8>,
    screen: ScreenTracker,
    generation: u64,
    controller_attachment: Option<String>,
    subscribers: Vec<Subscriber>,
}

struct ManagedEntry {
    snap: ProcessSnapshot,
    pty_rt: Option<PtyRuntime>,
    waiters: Vec<mpsc::Sender<Value>>,
}

struct ExitWait {
    done: Mutex<bool>,
    cv: Condvar,
}

impl ExitWait {
    fn new() -> Self {
        Self {
            done: Mutex::new(false),
            cv: Condvar::new(),
        }
    }
    fn notify(&self) {
        if let Ok(mut g) = self.done.lock() {
            *g = true;
            self.cv.notify_all();
        }
    }
}

struct DaemonState {
    instance_id: String,
    token: String,
    socket_path: PathBuf,
    service_json_path: PathBuf,
    runtime_subdir: PathBuf,
    next_id: AtomicU64,
    next_sub_id: AtomicU64,
    entries: Mutex<HashMap<u64, Arc<Mutex<ManagedEntry>>>>,
    handoff: Mutex<Option<(String, u64)>>,
    owner_connected: AtomicBool,
    shutting_down: AtomicBool,
    exit_wait: ExitWait,
}

#[derive(Deserialize)]
struct Envelope {
    token: String,
    request: Value,
}

fn read_frame(stream: &mut UnixStream) -> std::io::Result<Option<Vec<u8>>> {
    let mut len_buf = [0u8; 4];
    match stream.read_exact(&mut len_buf) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_be_bytes(len_buf) as usize;
    if len == 0 || len > MAX_FRAME_LEN {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "invalid frame length",
        ));
    }
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload)?;
    Ok(Some(payload))
}

fn write_raw_frame(stream: &mut UnixStream, payload: &[u8]) -> std::io::Result<()> {
    let len = payload.len() as u32;
    let mut frame = Vec::with_capacity(4 + payload.len());
    frame.extend_from_slice(&len.to_be_bytes());
    frame.extend_from_slice(payload);
    stream.write_all(&frame)?;
    stream.flush()
}

fn write_json_frame(stream: &mut UnixStream, val: &Value) -> std::io::Result<()> {
    let bytes = serde_json::to_vec(val).unwrap_or_else(|_| b"{\"type\":\"error\",\"message\":\"json error\"}".to_vec());
    write_raw_frame(stream, &bytes)
}

fn write_output_frame(stream: &mut UnixStream, start: u64, end: u64, data: &[u8]) -> std::io::Result<()> {
    let mut payload = Vec::with_capacity(17 + data.len());
    payload.push(0u8);
    payload.extend_from_slice(&start.to_be_bytes());
    payload.extend_from_slice(&end.to_be_bytes());
    payload.extend_from_slice(data);
    write_raw_frame(stream, &payload)
}

fn broadcast_pty(pty_rt: &mut PtyRuntime, frame: SubFrame) {
    pty_rt.subscribers.retain(|sub| match &frame {
        SubFrame::Output { start, end, data } => sub
            .tx
            .send(SubFrame::Output {
                start: *start,
                end: *end,
                data: data.clone(),
            })
            .is_ok(),
        SubFrame::Json(v) => sub.tx.send(SubFrame::Json(v.clone())).is_ok(),
        SubFrame::Close => {
            let _ = sub.tx.send(SubFrame::Close);
            false
        }
    });
}

fn build_env_cstrings(extra: Option<&BTreeMap<String, String>>) -> Vec<CString> {
    let mut merged: BTreeMap<String, String> = std::env::vars().collect();
    if let Some(map) = extra {
        for (k, v) in map {
            if !k.contains('=') && !k.contains('\0') && !v.contains('\0') {
                merged.insert(k.clone(), v.clone());
            }
        }
    }
    merged.entry("TERM".to_string()).or_insert_with(|| "xterm-256color".to_string());
    merged
        .into_iter()
        .filter_map(|(k, v)| CString::new(format!("{}={}", k, v)).ok())
        .collect()
}

fn resolve_executable(program: &str, env_map: Option<&BTreeMap<String, String>>) -> Option<CString> {
    if program.contains('/') {
        return CString::new(program).ok();
    }
    let path_var = env_map
        .and_then(|m| m.get("PATH").cloned())
        .or_else(|| std::env::var("PATH").ok())
        .unwrap_or_else(|| "/system/bin".to_string());
    for dir in path_var.split(':') {
        if dir.is_empty() {
            continue;
        }
        let candidate = Path::new(dir).join(program);
        if candidate.is_file() {
            if let Some(s) = candidate.to_str() {
                if let Ok(cs) = CString::new(s) {
                    return Some(cs);
                }
            }
        }
    }
    CString::new(program).ok()
}

fn build_exited_event(entry: &ManagedEntry) -> Value {
    let (exit_code, sig) = match &entry.snap.lifecycle {
        Lifecycle::Exited { exit_code, signal } => (*exit_code, signal.clone()),
        _ => (Some(0), None),
    };
    let mut obj = json!({
        "type": "exited",
        "exit_code": exit_code,
        "final_offset": entry.snap.output_tail,
        "exited_at": entry.snap.exited_at.unwrap_or_else(now_ms),
    });
    if let Some(s) = sig {
        obj["signal"] = Value::String(s);
    }
    if let Some(pipe) = &entry.snap.pipe {
        obj["output_lines"] = json!(pipe.output_lines);
        if let Some(err) = &pipe.output_error {
            obj["output_error"] = json!(err);
        }
    }
    obj
}

fn spawn_pty_process(
    state: &Arc<DaemonState>,
    req: &Value,
) -> Result<ProcessSnapshot, String> {
    let program = req
        .get("program")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .unwrap_or("/system/bin/sh")
        .to_string();
    let args: Vec<String> = req
        .get("args")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default();
    let cwd = req
        .get("cwd")
        .and_then(|v| v.as_str())
        .unwrap_or("/")
        .to_string();
    let group_id = req
        .get("group_id")
        .and_then(|v| v.as_str())
        .unwrap_or("default")
        .to_string();
    let cols = req.get("cols").and_then(|v| v.as_u64()).unwrap_or(80).clamp(2, 1000) as u16;
    let rows = req.get("rows").and_then(|v| v.as_u64()).unwrap_or(24).clamp(2, 1000) as u16;
    let default_title = Path::new(&program)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("bash")
        .to_string();
    let req_title = req
        .get("title")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string());
    let title = req_title.clone().unwrap_or(default_title);

    let env_override: Option<BTreeMap<String, String>> = req
        .get("env")
        .and_then(|v| serde_json::from_value(v.clone()).ok());

    let exe_c = resolve_executable(&program, env_override.as_ref())
        .ok_or_else(|| format!("invalid executable: {}", program))?;
    let cwd_c = CString::new(cwd.as_str()).map_err(|_| "invalid cwd".to_string())?;

    let mut argv_vec = Vec::with_capacity(1 + args.len());
    argv_vec.push(CString::new(program.as_str()).map_err(|_| "invalid arg0".to_string())?);
    for a in &args {
        argv_vec.push(CString::new(a.as_str()).map_err(|_| "invalid argument".to_string())?);
    }
    let mut argv_ptrs: Vec<*const libc::c_char> = argv_vec.iter().map(|s| s.as_ptr()).collect();
    argv_ptrs.push(std::ptr::null());

    let env_c = build_env_cstrings(env_override.as_ref());
    let mut envp_ptrs: Vec<*const libc::c_char> = env_c.iter().map(|s| s.as_ptr()).collect();
    envp_ptrs.push(std::ptr::null());

    let mut master_fd: libc::c_int = -1;
    let mut slave_fd: libc::c_int = -1;
    let ws = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };

    let rc = unsafe {
        libc::openpty(
            &mut master_fd,
            &mut slave_fd,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            &ws as *const libc::winsize as *mut libc::winsize,
        )
    };
    if rc != 0 || master_fd < 0 || slave_fd < 0 {
        return Err(format!("openpty failed: {}", std::io::Error::last_os_error()));
    }

    unsafe {
        libc::fcntl(master_fd, libc::F_SETFD, libc::FD_CLOEXEC);
    }

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        let err = std::io::Error::last_os_error();
        unsafe {
            libc::close(master_fd);
            libc::close(slave_fd);
        }
        return Err(format!("fork failed: {}", err));
    }

    if pid == 0 {
        unsafe {
            libc::close(master_fd);
            libc::setsid();
            let _ = libc::ioctl(slave_fd, libc::TIOCSCTTY as _, 0);
            libc::dup2(slave_fd, 0);
            libc::dup2(slave_fd, 1);
            libc::dup2(slave_fd, 2);
            if slave_fd > 2 {
                libc::close(slave_fd);
            }
            libc::signal(libc::SIGPIPE, libc::SIG_DFL);
            libc::signal(libc::SIGHUP, libc::SIG_DFL);
            let _ = libc::chdir(cwd_c.as_ptr());
            libc::execve(exe_c.as_ptr(), argv_ptrs.as_ptr(), envp_ptrs.as_ptr());
            libc::_exit(127);
        }
    }

    unsafe {
        libc::close(slave_fd);
    }

    let id = state.next_id.fetch_add(1, Ordering::SeqCst);
    let mut cmd_full = Vec::with_capacity(1 + args.len());
    cmd_full.push(program);
    cmd_full.extend(args);

    let snap = ProcessSnapshot {
        id,
        mode: "pty",
        pid: Some(pid as u32),
        command: cmd_full,
        cwd,
        group_id,
        lifecycle: Lifecycle::Running,
        output_head: 0,
        output_tail: 0,
        started_at: now_ms(),
        exited_at: None,
        pty: Some(PtyMeta {
            title,
            user_title: req_title,
            foreground_process: None,
            cols,
            rows,
        }),
        pipe: None,
    };

    let entry = Arc::new(Mutex::new(ManagedEntry {
        snap: snap.clone(),
        pty_rt: Some(PtyRuntime {
            master_fd,
            ring: VecDeque::with_capacity(65536),
            screen: ScreenTracker::new(cols, rows),
            generation: 1,
            controller_attachment: None,
            subscribers: Vec::new(),
        }),
        waiters: Vec::new(),
    }));

    if let Ok(mut map) = state.entries.lock() {
        map.insert(id, Arc::clone(&entry));
    }

    // Reader thread for PTY master_fd
    let entry_reader = Arc::clone(&entry);
    thread::spawn(move || {
        let mut buf = [0u8; 16384];
        loop {
            let n = unsafe { libc::read(master_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if n <= 0 {
                break;
            }
            let chunk = &buf[..n as usize];
            if let Ok(mut guard) = entry_reader.lock() {
                let start = guard.snap.output_tail;
                let end = start + chunk.len() as u64;
                guard.snap.output_tail = end;

                let mut osc_title = None;
                if let Some(rt) = guard.pty_rt.as_mut() {
                    rt.ring.extend(chunk.iter().copied());
                    if rt.ring.len() > MAX_PTY_RING_BYTES {
                        let excess = rt.ring.len() - MAX_PTY_RING_BYTES;
                        rt.ring.drain(..excess);
                    }
                    osc_title = rt.screen.feed(chunk);
                    broadcast_pty(
                        rt,
                        SubFrame::Output {
                            start,
                            end,
                            data: chunk.to_vec(),
                        },
                    );
                }
                if let Some(rt) = guard.pty_rt.as_ref() {
                    guard.snap.output_head = guard.snap.output_tail.saturating_sub(rt.ring.len() as u64);
                }
                if let Some(new_t) = osc_title {
                    let allow = guard
                        .snap
                        .pty
                        .as_ref()
                        .map(|p| p.user_title.is_none())
                        .unwrap_or(false);
                    if allow {
                        if let Some(p) = guard.snap.pty.as_mut() {
                            p.title = new_t.clone();
                        }
                        if let Some(rt) = guard.pty_rt.as_mut() {
                            broadcast_pty(
                                rt,
                                SubFrame::Json(json!({
                                    "type": "title_changed",
                                    "title": new_t,
                                })),
                            );
                        }
                    }
                }
            }
        }
    });

    // Reaper thread for child PID
    let entry_reaper = Arc::clone(&entry);
    thread::spawn(move || {
        let mut status: libc::c_int = 0;
        loop {
            let w = unsafe { libc::waitpid(pid, &mut status, 0) };
            if w == pid {
                break;
            }
            if w < 0 && std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
                break;
            }
        }
        // Small grace pause so reader thread drains trailing PTY output bytes
        thread::sleep(Duration::from_millis(20));

        if let Ok(mut guard) = entry_reaper.lock() {
            let (exit_code, sig) = if libc::WIFEXITED(status) {
                (Some(libc::WEXITSTATUS(status)), None)
            } else if libc::WIFSIGNALED(status) {
                (None, Some(signal_name(libc::WTERMSIG(status))))
            } else {
                (Some(0), None)
            };
            if exit_code != Some(0) {
                let kill_pgid = pid;
                unsafe {
                    let _ = libc::kill(-kill_pgid, libc::SIGTERM);
                }
                thread::spawn(move || {
                    thread::sleep(Duration::from_millis(200));
                    unsafe {
                        let _ = libc::kill(-kill_pgid, libc::SIGKILL);
                    }
                });
            }
            guard.snap.lifecycle = Lifecycle::Exited {
                exit_code,
                signal: sig,
            };
            guard.snap.exited_at = Some(now_ms());
            let exited_ev = build_exited_event(&guard);
            for wtx in guard.waiters.drain(..) {
                let _ = wtx.send(exited_ev.clone());
            }
            if let Some(rt) = guard.pty_rt.as_mut() {
                if rt.master_fd >= 0 {
                    unsafe {
                        libc::close(rt.master_fd);
                    }
                    rt.master_fd = -1;
                }
                broadcast_pty(rt, SubFrame::Json(exited_ev));
                broadcast_pty(rt, SubFrame::Close);
            }
        }
    });

    Ok(snap)
}

fn spawn_pipe_process(
    state: &Arc<DaemonState>,
    req: &Value,
) -> Result<ProcessSnapshot, String> {
    let program = req
        .get("program")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "missing program".to_string())?
        .to_string();
    let args: Vec<String> = req
        .get("args")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default();
    let cwd = req
        .get("cwd")
        .and_then(|v| v.as_str())
        .unwrap_or("/")
        .to_string();
    let group_id = req
        .get("group_id")
        .and_then(|v| v.as_str())
        .unwrap_or("default")
        .to_string();
    let output_path = req
        .get("output_path")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing output_path".to_string())?
        .to_string();
    let metadata: Map<String, Value> = req
        .get("metadata")
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();

    let env_override: Option<BTreeMap<String, String>> = req
        .get("env")
        .and_then(|v| serde_json::from_value(v.clone()).ok());

    if let Some(parent) = Path::new(&output_path).parent() {
        let _ = fs::create_dir_all(parent);
    }
    let out_file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&output_path)
        .map_err(|e| format!("cannot open output_path {}: {}", output_path, e))?;
    let _ = fs::set_permissions(&output_path, fs::Permissions::from_mode(0o600));

    let exe_c = resolve_executable(&program, env_override.as_ref())
        .ok_or_else(|| format!("invalid executable: {}", program))?;
    let cwd_c = CString::new(cwd.as_str()).map_err(|_| "invalid cwd".to_string())?;

    let mut argv_vec = Vec::with_capacity(1 + args.len());
    argv_vec.push(CString::new(program.as_str()).map_err(|_| "invalid arg0".to_string())?);
    for a in &args {
        argv_vec.push(CString::new(a.as_str()).map_err(|_| "invalid argument".to_string())?);
    }
    let mut argv_ptrs: Vec<*const libc::c_char> = argv_vec.iter().map(|s| s.as_ptr()).collect();
    argv_ptrs.push(std::ptr::null());

    let env_c = build_env_cstrings(env_override.as_ref());
    let mut envp_ptrs: Vec<*const libc::c_char> = env_c.iter().map(|s| s.as_ptr()).collect();
    envp_ptrs.push(std::ptr::null());

    let mut fds = [-1 as libc::c_int; 2];
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        return Err(format!("pipe failed: {}", std::io::Error::last_os_error()));
    }
    unsafe {
        libc::fcntl(fds[0], libc::F_SETFD, libc::FD_CLOEXEC);
        libc::fcntl(fds[1], libc::F_SETFD, libc::FD_CLOEXEC);
    }

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        let err = std::io::Error::last_os_error();
        unsafe {
            libc::close(fds[0]);
            libc::close(fds[1]);
        }
        return Err(format!("fork failed: {}", err));
    }

    if pid == 0 {
        unsafe {
            libc::close(fds[0]);
            let _ = libc::setpgid(0, 0);
            let devnull = libc::open(b"/dev/null\0".as_ptr() as *const libc::c_char, libc::O_RDONLY);
            if devnull >= 0 {
                libc::dup2(devnull, 0);
                if devnull > 2 {
                    libc::close(devnull);
                }
            }
            libc::dup2(fds[1], 1);
            libc::dup2(fds[1], 2);
            if fds[1] > 2 {
                libc::close(fds[1]);
            }
            libc::signal(libc::SIGPIPE, libc::SIG_DFL);
            libc::signal(libc::SIGHUP, libc::SIG_DFL);
            let _ = libc::chdir(cwd_c.as_ptr());
            libc::execve(exe_c.as_ptr(), argv_ptrs.as_ptr(), envp_ptrs.as_ptr());
            libc::_exit(127);
        }
    }

    unsafe {
        let _ = libc::setpgid(pid, pid);
        libc::close(fds[1]);
    }

    let id = state.next_id.fetch_add(1, Ordering::SeqCst);
    let mut cmd_full = Vec::with_capacity(1 + args.len());
    cmd_full.push(program);
    cmd_full.extend(args);

    let snap = ProcessSnapshot {
        id,
        mode: "pipe",
        pid: Some(pid as u32),
        command: cmd_full,
        cwd,
        group_id,
        lifecycle: Lifecycle::Running,
        output_head: 0,
        output_tail: 0,
        started_at: now_ms(),
        exited_at: None,
        pty: None,
        pipe: Some(PipeMeta {
            output_path,
            output_lines: 0,
            output_error: None,
            metadata,
        }),
    };

    let entry = Arc::new(Mutex::new(ManagedEntry {
        snap: snap.clone(),
        pty_rt: None,
        waiters: Vec::new(),
    }));

    if let Ok(mut map) = state.entries.lock() {
        map.insert(id, Arc::clone(&entry));
    }

    let entry_io = Arc::clone(&entry);
    let reader_handle = thread::spawn(move || {
        let mut reader = unsafe { File::from_raw_fd(fds[0]) };
        let mut writer = out_file;
        let mut buf = [0u8; 32768];
        let mut write_failed = false;
        loop {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    let chunk = &buf[..n];
                    let nl = chunk.iter().filter(|&&b| b == b'\n').count() as u64;
                    let mut io_err_msg = None;
                    if !write_failed {
                        if let Err(e) = writer.write_all(chunk) {
                            write_failed = true;
                            io_err_msg = Some(e.to_string());
                        }
                    }
                    if let Ok(mut guard) = entry_io.lock() {
                        guard.snap.output_tail += n as u64;
                        if let Some(pm) = guard.snap.pipe.as_mut() {
                            pm.output_lines += nl;
                            if let Some(em) = io_err_msg {
                                pm.output_error = Some(em);
                            }
                        }
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
        let _ = writer.flush();
    });

    let entry_reaper = Arc::clone(&entry);
    thread::spawn(move || {
        let mut status: libc::c_int = 0;
        loop {
            let w = unsafe { libc::waitpid(pid, &mut status, 0) };
            if w == pid {
                break;
            }
            if w < 0 && std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
                break;
            }
        }
        // Wait up to 150ms for pipe output drain; if a grandchild holds stdout open, proceed cleanly
        let drain_start = now_ms();
        while !reader_handle.is_finished() && now_ms().saturating_sub(drain_start) < 150 {
            thread::sleep(Duration::from_millis(10));
        }

        if let Ok(mut guard) = entry_reaper.lock() {
            let (exit_code, sig) = if libc::WIFEXITED(status) {
                (Some(libc::WEXITSTATUS(status)), None)
            } else if libc::WIFSIGNALED(status) {
                (None, Some(signal_name(libc::WTERMSIG(status))))
            } else {
                (Some(0), None)
            };
            if exit_code != Some(0) {
                let kill_pgid = pid;
                unsafe {
                    let _ = libc::kill(-kill_pgid, libc::SIGTERM);
                }
                thread::spawn(move || {
                    thread::sleep(Duration::from_millis(200));
                    unsafe {
                        let _ = libc::kill(-kill_pgid, libc::SIGKILL);
                    }
                });
            }
            guard.snap.lifecycle = Lifecycle::Exited {
                exit_code,
                signal: sig,
            };
            guard.snap.exited_at = Some(now_ms());
            let exited_ev = build_exited_event(&guard);
            for wtx in guard.waiters.drain(..) {
                let _ = wtx.send(exited_ev.clone());
            }
        }
    });

    Ok(snap)
}

fn find_entry(state: &DaemonState, id: u64) -> Option<Arc<Mutex<ManagedEntry>>> {
    state.entries.lock().ok()?.get(&id).cloned()
}

fn handle_single_request(state: &Arc<DaemonState>, req: &Value) -> Value {
    let op = match req.get("op").and_then(|v| v.as_str()) {
        Some(s) => s,
        None => return json!({"type": "error", "message": "missing op"}),
    };

    match op {
        "ping" => json!({
            "type": "pong",
            "instance_id": state.instance_id,
            "pid": std::process::id(),
            "protocol": PROTOCOL_VERSION,
            "features": ["pty", "pipe", "handoff"],
        }),

        "prepare_handoff" => {
            let ticket = random_hex(24);
            let expires = now_ms() + 30_000;
            if let Ok(mut h) = state.handoff.lock() {
                *h = Some((ticket.clone(), expires));
            }
            json!({
                "type": "handoff",
                "ticket": ticket,
                "expires_at": expires,
            })
        }

        "shutdown" => {
            state.shutting_down.store(true, Ordering::SeqCst);
            let all: Vec<Arc<Mutex<ManagedEntry>>> = state
                .entries
                .lock()
                .map(|m| m.values().cloned().collect())
                .unwrap_or_default();
            for e in all {
                if let Ok(g) = e.lock() {
                    if matches!(g.snap.lifecycle, Lifecycle::Running) {
                        if let Some(pid) = g.snap.pid {
                            unsafe {
                                let _ = libc::kill(-(pid as i32), libc::SIGTERM);
                                let _ = libc::kill(pid as i32, libc::SIGTERM);
                            }
                        }
                    }
                }
            }
            let _ = fs::remove_file(&state.service_json_path);
            let _ = fs::remove_file(&state.socket_path);
            json!({"type": "ok"})
        }

        "create_pty" => match spawn_pty_process(state, req) {
            Ok(snap) => json!({"type": "created", "process": snap}),
            Err(msg) => json!({"type": "error", "message": msg}),
        },

        "create_pipe" => match spawn_pipe_process(state, req) {
            Ok(snap) => json!({"type": "created", "process": snap}),
            Err(msg) => json!({"type": "error", "message": msg}),
        },

        "list" => {
            let mode_filter = req.get("mode").and_then(|v| v.as_str());
            let mut list = Vec::new();
            if let Ok(map) = state.entries.lock() {
                let mut ids: Vec<u64> = map.keys().copied().collect();
                ids.sort_unstable();
                for id in ids {
                    if let Some(e) = map.get(&id) {
                        if let Ok(g) = e.lock() {
                            if mode_filter.map(|m| m == g.snap.mode).unwrap_or(true) {
                                list.push(g.snap.clone());
                            }
                        }
                    }
                }
            }
            json!({"type": "processes", "processes": list})
        }

        "get" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            match find_entry(state, id) {
                Some(e) => match e.lock() {
                    Ok(g) => json!({"type": "process", "process": g.snap}),
                    Err(_) => json!({"type": "error", "message": "lock poisoned"}),
                },
                None => json!({"type": "error", "message": format!("process {} not found", id)}),
            }
        }

        "write" | "input" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let b64 = req.get("data_base64").and_then(|v| v.as_str()).unwrap_or("");
            let bytes = match B64.decode(b64) {
                Ok(b) => b,
                Err(_) => return json!({"type": "error", "message": "invalid base64"}),
            };
            let Some(entry) = find_entry(state, id) else {
                return json!({"type": "error", "message": "pty not found"});
            };
            if let Ok(mut g) = entry.lock() {
                let pid_opt = g.snap.pid;
                if op == "input" {
                    let att = req.get("attachment_id").and_then(|v| v.as_str()).map(|s| s.to_string());
                    let cols = req.get("cols").and_then(|v| v.as_u64()).map(|n| n.clamp(2, 1000) as u16);
                    let rows = req.get("rows").and_then(|v| v.as_u64()).map(|n| n.clamp(2, 1000) as u16);
                    apply_resize_and_control(&mut g, pid_opt, att, cols, rows, false);
                }
                if let Some(rt) = g.pty_rt.as_ref() {
                    if rt.master_fd >= 0 && !bytes.is_empty() {
                        let mut written = 0usize;
                        while written < bytes.len() {
                            let n = unsafe {
                                libc::write(
                                    rt.master_fd,
                                    bytes[written..].as_ptr() as *const libc::c_void,
                                    bytes.len() - written,
                                )
                            };
                            if n <= 0 {
                                break;
                            }
                            written += n as usize;
                        }
                    }
                }
            }
            json!({"type": "ok"})
        }

        "resize" | "control" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let Some(entry) = find_entry(state, id) else {
                return json!({"type": "error", "message": "pty not found"});
            };
            if let Ok(mut g) = entry.lock() {
                let pid_opt = g.snap.pid;
                let att = req.get("attachment_id").and_then(|v| v.as_str()).map(|s| s.to_string());
                let cols = req.get("cols").and_then(|v| v.as_u64()).map(|n| n.clamp(2, 1000) as u16);
                let rows = req.get("rows").and_then(|v| v.as_u64()).map(|n| n.clamp(2, 1000) as u16);
                apply_resize_and_control(&mut g, pid_opt, att, cols, rows, op == "control");
            }
            json!({"type": "ok"})
        }

        "rename" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let title_opt = req
                .get("title")
                .and_then(|v| v.as_str())
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty());
            let Some(entry) = find_entry(state, id) else {
                return json!({"type": "error", "message": "pty not found"});
            };
            if let Ok(mut g) = entry.lock() {
                let fallback = g
                    .snap
                    .command
                    .first()
                    .and_then(|c| Path::new(c).file_name().and_then(|n| n.to_str()))
                    .unwrap_or("bash")
                    .to_string();
                let effective = title_opt.clone().unwrap_or(fallback);
                if let Some(pm) = g.snap.pty.as_mut() {
                    pm.title = effective.clone();
                    pm.user_title = title_opt;
                }
                if let Some(rt) = g.pty_rt.as_mut() {
                    broadcast_pty(
                        rt,
                        SubFrame::Json(json!({
                            "type": "title_changed",
                            "title": effective,
                        })),
                    );
                }
            }
            json!({"type": "ok"})
        }

        "replay" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let req_offset = req.get("offset").and_then(|v| v.as_u64()).unwrap_or(0);
            let Some(entry) = find_entry(state, id) else {
                return json!({"type": "error", "message": "pty not found"});
            };
            let Ok(g) = entry.lock() else {
                return json!({"type": "error", "message": "lock poisoned"});
            };
            let Some(rt) = g.pty_rt.as_ref() else {
                return json!({"type": "error", "message": "not a pty"});
            };
            let head = g.snap.output_head;
            let tail = g.snap.output_tail;
            let available = req_offset.clamp(head, tail);
            let truncated = req_offset < head;
            let skip = (available - head) as usize;
            let replay_bytes: Vec<u8> = rt.ring.iter().skip(skip).copied().collect();
            json!({
                "type": "replay",
                "requested_offset": req_offset,
                "available_offset": available,
                "end_offset": tail,
                "truncated": truncated,
                "data_base64": B64.encode(&replay_bytes),
            })
        }

        "snapshot" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let Some(entry) = find_entry(state, id) else {
                return json!({"type": "error", "message": "pty not found"});
            };
            let Ok(g) = entry.lock() else {
                return json!({"type": "error", "message": "lock poisoned"});
            };
            let Some(rt) = g.pty_rt.as_ref() else {
                return json!({"type": "error", "message": "not a pty"});
            };
            let lines = rt.screen.lines(None);
            let raw: Vec<u8> = rt.ring.iter().copied().collect();
            json!({
                "type": "snapshot",
                "process": g.snap,
                "text": lines.join("\n"),
                "checkpoint_base64": B64.encode(&raw),
                "cursor_x": rt.screen.cx,
                "cursor_y": rt.screen.cy,
            })
        }

        "read_rows" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let rows_limit = req.get("rows").and_then(|v| v.as_u64()).map(|n| n as usize);
            let Some(entry) = find_entry(state, id) else {
                return json!({"type": "error", "message": "pty not found"});
            };
            let Ok(g) = entry.lock() else {
                return json!({"type": "error", "message": "lock poisoned"});
            };
            let Some(rt) = g.pty_rt.as_ref() else {
                return json!({"type": "error", "message": "not a pty"});
            };
            json!({
                "type": "rows",
                "process": g.snap,
                "lines": rt.screen.lines(rows_limit),
                "cursor_x": rt.screen.cx,
                "cursor_y": rt.screen.cy,
            })
        }

        "signal" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let sig = req
                .get("signal")
                .and_then(signal_from_str_or_num)
                .unwrap_or(libc::SIGTERM);
            if let Some(entry) = find_entry(state, id) {
                if let Ok(g) = entry.lock() {
                    if let Some(pid) = g.snap.pid {
                        unsafe {
                            let _ = libc::kill(-(pid as i32), sig);
                            let _ = libc::kill(pid as i32, sig);
                        }
                    }
                }
            }
            json!({"type": "ok"})
        }

        "set_metadata" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let meta = req
                .get("metadata")
                .and_then(|v| v.as_object().cloned())
                .unwrap_or_default();
            if let Some(entry) = find_entry(state, id) {
                if let Ok(mut g) = entry.lock() {
                    if let Some(pm) = g.snap.pipe.as_mut() {
                        pm.metadata = meta;
                    }
                }
            }
            json!({"type": "ok"})
        }

        "terminate" => {
            let id = match req.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => return json!({"type": "error", "message": "missing id"}),
            };
            let removed = state.entries.lock().ok().and_then(|mut m| m.remove(&id));
            if let Some(entry) = removed {
                if let Ok(mut g) = entry.lock() {
                    if matches!(g.snap.lifecycle, Lifecycle::Running) {
                        if let Some(pid) = g.snap.pid {
                            unsafe {
                                let _ = libc::kill(-(pid as i32), libc::SIGTERM);
                                let _ = libc::kill(pid as i32, libc::SIGTERM);
                            }
                            let kill_pid = pid as i32;
                            thread::spawn(move || {
                                thread::sleep(Duration::from_millis(500));
                                unsafe {
                                    let _ = libc::kill(-kill_pid, libc::SIGKILL);
                                    let _ = libc::kill(kill_pid, libc::SIGKILL);
                                }
                            });
                        }
                    }
                    if let Some(rt) = g.pty_rt.as_mut() {
                        if rt.master_fd >= 0 {
                            unsafe {
                                libc::close(rt.master_fd);
                            }
                            rt.master_fd = -1;
                        }
                    }
                }
            }
            json!({"type": "ok"})
        }

        other => json!({"type": "error", "message": format!("unsupported op: {}", other)}),
    }
}

fn apply_resize_and_control(
    g: &mut ManagedEntry,
    pid_opt: Option<u32>,
    att: Option<String>,
    cols: Option<u16>,
    rows: Option<u16>,
    force_control: bool,
) {
    let mut size_changed = false;
    if let (Some(c), Some(r)) = (cols, rows) {
        if let Some(pm) = g.snap.pty.as_mut() {
            if pm.cols != c || pm.rows != r {
                pm.cols = c;
                pm.rows = r;
                size_changed = true;
            }
        }
    }

    if let Some(rt) = g.pty_rt.as_mut() {
        if force_control || (att.is_some() && rt.controller_attachment != att) {
            rt.controller_attachment = att.clone();
            rt.generation += 1;
            let gen = rt.generation;
            broadcast_pty(
                rt,
                SubFrame::Json(json!({
                    "type": "controller_changed",
                    "attachment_id": att,
                    "generation": gen,
                })),
            );
        }
        if size_changed {
            if let (Some(c), Some(r)) = (cols, rows) {
                rt.screen.resize(c, r);
                if rt.master_fd >= 0 {
                    let ws = libc::winsize {
                        ws_row: r,
                        ws_col: c,
                        ws_xpixel: 0,
                        ws_ypixel: 0,
                    };
                    unsafe {
                        let _ = libc::ioctl(rt.master_fd, libc::TIOCSWINSZ as _, &ws);
                    }
                }
                if let Some(pid) = pid_opt {
                    unsafe {
                        let _ = libc::kill(-(pid as i32), libc::SIGWINCH);
                    }
                }
                rt.generation += 1;
                let gen = rt.generation;
                broadcast_pty(
                    rt,
                    SubFrame::Json(json!({
                        "type": "resized",
                        "cols": c,
                        "rows": r,
                        "generation": gen,
                        "checkpoint_base64": "",
                    })),
                );
            }
        }
    }
}

fn handle_client(state: Arc<DaemonState>, mut stream: UnixStream) {
    let Ok(Some(first_bytes)) = read_frame(&mut stream) else {
        return;
    };
    let env: Envelope = match serde_json::from_slice(&first_bytes) {
        Ok(v) => v,
        Err(e) => {
            let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": e.to_string()}));
            return;
        }
    };

    if !constant_time_eq(env.token.as_bytes(), state.token.as_bytes()) {
        let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": "authentication failed"}));
        return;
    }

    let op = env
        .request
        .get("op")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    match op.as_str() {
        "own" => {
            let req_inst = env
                .request
                .get("instance_id")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if req_inst != state.instance_id {
                let _ = write_json_frame(
                    &mut stream,
                    &json!({"type": "error", "message": "instance_id mismatch"}),
                );
                return;
            }
            if let Some(ticket) = env.request.get("ticket").and_then(|v| v.as_str()) {
                let mut valid = false;
                if let Ok(mut h) = state.handoff.lock() {
                    if let Some((saved_t, exp)) = h.as_ref() {
                        if saved_t == ticket && *exp > now_ms() {
                            valid = true;
                        }
                    }
                    if valid {
                        *h = None;
                    }
                }
                if !valid {
                    let _ = write_json_frame(
                        &mut stream,
                        &json!({"type": "error", "message": "authentication failed"}),
                    );
                    return;
                }
            }

            state.owner_connected.store(true, Ordering::SeqCst);
            if write_json_frame(&mut stream, &json!({"type": "owned"})).is_err() {
                state.owner_connected.store(false, Ordering::SeqCst);
                return;
            }

            // Serve subsequent exchange requests on the persistent ownership socket
            while let Ok(Some(next_bytes)) = read_frame(&mut stream) {
                let Ok(next_env) = serde_json::from_slice::<Envelope>(&next_bytes) else {
                    break;
                };
                if !constant_time_eq(next_env.token.as_bytes(), state.token.as_bytes()) {
                    let _ = write_json_frame(
                        &mut stream,
                        &json!({"type": "error", "message": "authentication failed"}),
                    );
                    break;
                }
                let resp = handle_single_request(&state, &next_env.request);
                let write_err = write_json_frame(&mut stream, &resp).is_err();
                if state.shutting_down.load(Ordering::SeqCst) {
                    let _ = stream.flush();
                    let _ = stream.shutdown(std::net::Shutdown::Write);
                    let st_exit = Arc::clone(&state);
                    thread::spawn(move || {
                        thread::sleep(Duration::from_millis(40));
                        st_exit.exit_wait.notify();
                    });
                    break;
                }
                if write_err {
                    break;
                }
            }
            state.owner_connected.store(false, Ordering::SeqCst);

            // If owner disconnected without an active handoff ticket, shut down after grace window
            let st_watch = Arc::clone(&state);
            thread::spawn(move || {
                thread::sleep(Duration::from_secs(5));
                if st_watch.owner_connected.load(Ordering::SeqCst) {
                    return;
                }
                let has_handoff = st_watch
                    .handoff
                    .lock()
                    .ok()
                    .and_then(|g| g.clone())
                    .map(|(_, exp)| exp > now_ms())
                    .unwrap_or(false);
                if !has_handoff {
                    let _ = handle_single_request(&st_watch, &json!({"op": "shutdown"}));
                    st_watch.exit_wait.notify();
                }
            });
        }

        "wait" => {
            let id = match env.request.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => {
                    let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": "missing id"}));
                    return;
                }
            };
            let Some(entry) = find_entry(&state, id) else {
                let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": "process not found"}));
                return;
            };
            let rx_opt = {
                let Ok(mut g) = entry.lock() else {
                    return;
                };
                if !matches!(g.snap.lifecycle, Lifecycle::Running) {
                    let ev = build_exited_event(&g);
                    let _ = write_json_frame(&mut stream, &ev);
                    return;
                }
                let (tx, rx) = mpsc::channel();
                g.waiters.push(tx);
                Some(rx)
            };
            if let Some(rx) = rx_opt {
                if let Ok(ev) = rx.recv() {
                    let _ = write_json_frame(&mut stream, &ev);
                }
            }
        }

        "subscribe" => {
            let id = match env.request.get("id").and_then(|v| v.as_u64()) {
                Some(i) => i,
                None => {
                    let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": "missing id"}));
                    return;
                }
            };
            let Some(entry) = find_entry(&state, id) else {
                let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": "pty not found"}));
                return;
            };

            let req_offset = env.request.get("offset").and_then(|v| v.as_u64());
            let att_id = env
                .request
                .get("attachment_id")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());
            let req_role = env
                .request
                .get("role")
                .and_then(|v| v.as_str())
                .unwrap_or("controller");
            let takeover = env
                .request
                .get("takeover")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            let (attached_json, initial_exited, rx) = {
                let Ok(mut g) = entry.lock() else {
                    return;
                };
                let head = g.snap.output_head;
                let tail = g.snap.output_tail;
                let snap_clone = g.snap.clone();
                let is_exited = if !matches!(g.snap.lifecycle, Lifecycle::Running) {
                    Some(build_exited_event(&g))
                } else {
                    None
                };
                let Some(rt) = g.pty_rt.as_mut() else {
                    let _ = write_json_frame(&mut stream, &json!({"type": "error", "message": "not a pty"}));
                    return;
                };

                let role = if req_role == "controller"
                    && (takeover || rt.controller_attachment.is_none() || rt.controller_attachment == att_id)
                {
                    if rt.controller_attachment != att_id {
                        rt.controller_attachment = att_id.clone();
                        rt.generation += 1;
                    }
                    "controller"
                } else {
                    "observer"
                };

                let requested = req_offset.unwrap_or(head);
                let available = requested.clamp(head, tail);
                let truncated = requested < head;
                let skip = (available - head) as usize;
                let replay_bytes: Vec<u8> = rt.ring.iter().skip(skip).copied().collect();

                let (tx, rx) = mpsc::channel();
                let sub_id = state.next_sub_id.fetch_add(1, Ordering::SeqCst);
                if is_exited.is_none() {
                    rt.subscribers.push(Subscriber {
                        sub_id,
                        attachment_id: att_id,
                        tx,
                    });
                }

                let attached = json!({
                    "type": "attached",
                    "process": snap_clone,
                    "role": role,
                    "generation": rt.generation,
                    "requested_offset": requested,
                    "available_offset": available,
                    "end_offset": tail,
                    "truncated": truncated,
                    "replay_base64": B64.encode(&replay_bytes),
                });
                (attached, is_exited, rx)
            };

            if write_json_frame(&mut stream, &attached_json).is_err() {
                return;
            }
            if let Some(ex) = initial_exited {
                let _ = write_json_frame(&mut stream, &ex);
                return;
            }

            while let Ok(frame) = rx.recv() {
                match frame {
                    SubFrame::Output { start, end, data } => {
                        if write_output_frame(&mut stream, start, end, &data).is_err() {
                            break;
                        }
                    }
                    SubFrame::Json(val) => {
                        let is_exit = val.get("type").and_then(|v| v.as_str()) == Some("exited");
                        if write_json_frame(&mut stream, &val).is_err() || is_exit {
                            break;
                        }
                    }
                    SubFrame::Close => break,
                }
            }
        }

        _ => {
            let resp = handle_single_request(&state, &env.request);
            let _ = write_json_frame(&mut stream, &resp);
            let _ = stream.flush();
            if state.shutting_down.load(Ordering::SeqCst) {
                let _ = stream.shutdown(std::net::Shutdown::Write);
                let st_exit = Arc::clone(&state);
                thread::spawn(move || {
                    thread::sleep(Duration::from_millis(40));
                    st_exit.exit_wait.notify();
                });
            }
        }
    }
}

fn run_daemon(runtime_dir: &str, name: &str) -> Result<(), String> {
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        libc::signal(libc::SIGHUP, libc::SIG_IGN);
    }

    let subdir = Path::new(runtime_dir).join(name);
    fs::create_dir_all(&subdir).map_err(|e| format!("mkdir {}: {}", subdir.display(), e))?;
    let _ = fs::set_permissions(&subdir, fs::Permissions::from_mode(0o700));

    let sock_path = subdir.join("s");
    if sock_path.exists() {
        let _ = fs::remove_file(&sock_path);
    }

    let listener = UnixListener::bind(&sock_path)
        .map_err(|e| format!("bind {}: {}", sock_path.display(), e))?;
    let _ = fs::set_permissions(&sock_path, fs::Permissions::from_mode(0o600));
    listener
        .set_nonblocking(false)
        .map_err(|e| format!("set_nonblocking: {}", e))?;

    let instance_id = random_hex(16);
    let token = random_hex(32);
    let service_json_path = subdir.join("service.json");
    let tmp_service = subdir.join("service.json.tmp");

    let service_payload = json!({
        "instance_id": instance_id,
        "pid": std::process::id(),
        "protocol": PROTOCOL_VERSION,
        "socket": sock_path.to_string_lossy(),
        "token": token,
        "features": ["pty", "pipe", "handoff"],
    });

    {
        let mut f = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&tmp_service)
            .map_err(|e| format!("open service.json.tmp: {}", e))?;
        let _ = fs::set_permissions(&tmp_service, fs::Permissions::from_mode(0o600));
        f.write_all(service_payload.to_string().as_bytes())
            .map_err(|e| format!("write service.json.tmp: {}", e))?;
        f.sync_all().ok();
    }
    fs::rename(&tmp_service, &service_json_path)
        .map_err(|e| format!("rename service.json: {}", e))?;

    let state = Arc::new(DaemonState {
        instance_id,
        token,
        socket_path: sock_path.clone(),
        service_json_path: service_json_path.clone(),
        runtime_subdir: subdir,
        next_id: AtomicU64::new(1),
        next_sub_id: AtomicU64::new(1),
        entries: Mutex::new(HashMap::new()),
        handoff: Mutex::new(None),
        owner_connected: AtomicBool::new(false),
        shutting_down: AtomicBool::new(false),
        exit_wait: ExitWait::new(),
    });

    let accept_state = Arc::clone(&state);
    thread::spawn(move || {
        for incoming in listener.incoming() {
            if accept_state.shutting_down.load(Ordering::SeqCst) {
                break;
            }
            if let Ok(stream) = incoming {
                let st = Arc::clone(&accept_state);
                thread::spawn(move || handle_client(st, stream));
            }
        }
    });

    let mut done_guard = state.exit_wait.done.lock().unwrap();
    while !*done_guard {
        done_guard = state.exit_wait.cv.wait(done_guard).unwrap();
    }

    let _ = fs::remove_file(&state.service_json_path);
    let _ = fs::remove_file(&state.socket_path);
    let _ = fs::remove_dir(&state.runtime_subdir);
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() || args[0] == "--version" || args[0] == "-V" {
        println!("opencode-pty {}", env!("CARGO_PKG_VERSION"));
        return;
    }

    if args[0] == "daemon" {
        let mut runtime_dir: Option<String> = None;
        let mut name: Option<String> = None;
        let mut i = 1;
        while i < args.len() {
            match args[i].as_str() {
                "--runtime-dir" if i + 1 < args.len() => {
                    runtime_dir = Some(args[i + 1].clone());
                    i += 2;
                }
                "--name" if i + 1 < args.len() => {
                    name = Some(args[i + 1].clone());
                    i += 2;
                }
                _ => {
                    i += 1;
                }
            }
        }
        let Some(rt_dir) = runtime_dir else {
            eprintln!("opencode-pty: missing --runtime-dir");
            std::process::exit(2);
        };
        let Some(inst_name) = name else {
            eprintln!("opencode-pty: missing --name");
            std::process::exit(2);
        };
        if let Err(e) = run_daemon(&rt_dir, &inst_name) {
            eprintln!("opencode-pty error: {}", e);
            std::process::exit(1);
        }
        return;
    }

    eprintln!("usage: opencode-pty daemon --runtime-dir <dir> --name <id>");
    std::process::exit(2);
}
