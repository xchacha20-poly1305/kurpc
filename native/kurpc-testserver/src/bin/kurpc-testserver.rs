//! Runs the test server for the Kotlin tests.
//!
//! Usage: `kurpc-testserver (--tcp <addr> | --unix <path|@name> | --pipe <name>) [--tls]`.
//! Prints `listening <address>` on stdout once it accepts connections, then flushes.
//! Stdout is block-buffered when piped, and the parent waits on this line. With `--tls`,
//! a self-signed certificate PEM follows in a delimited block. A `--unix` value starting
//! with `@` is an abstract-namespace socket (`@name` binds `\0name`); process arguments
//! cannot carry the leading NUL. Serves until stdin closes, so a parent process that
//! dies takes the server with it.

use std::io::Write;
use std::process::ExitCode;

use kurpc_testserver::{Listener, TestCert, TestService, serve};
use tokio::io::AsyncReadExt;
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (mode, tls) = match parse(&args) {
        Ok(parsed) => parsed,
        Err(usage) => {
            eprintln!("{usage}");
            return ExitCode::FAILURE;
        }
    };
    let (listener, address) = match bind(mode).await {
        Ok(bound) => bound,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::FAILURE;
        }
    };
    let cert = tls.then(TestCert::generate);
    println!("listening {address}");
    if let Some(cert) = &cert {
        println!("--- kurpc-testserver tls certificate ---");
        println!("{}", cert.certificate_pem.trim_end());
        println!("--- end kurpc-testserver tls certificate ---");
    }
    let _ = std::io::stdout().flush();

    let stdin_closed = async {
        let mut sink = [0u8; 64];
        let mut stdin = tokio::io::stdin();
        while matches!(stdin.read(&mut sink).await, Ok(n) if n > 0) {}
    };
    let tls = cert.map(|cert| cert.server_config());
    match serve(listener, TestService::new(), tls, stdin_closed).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("server failed: {error}");
            ExitCode::FAILURE
        }
    }
}

enum Mode {
    Tcp(String),
    #[cfg(unix)]
    Unix(String),
    #[cfg(windows)]
    Pipe(String),
}

fn parse(args: &[String]) -> Result<(Mode, bool), String> {
    let mut mode = None;
    let mut tls = false;
    let mut args = args.iter();
    while let Some(arg) = args.next() {
        let selected = match arg.as_str() {
            "--tls" => {
                tls = true;
                None
            }
            "--tcp" => Some(Mode::Tcp(flag_value(&mut args, "--tcp")?)),
            #[cfg(unix)]
            "--unix" => Some(Mode::Unix(flag_value(&mut args, "--unix")?)),
            #[cfg(windows)]
            "--pipe" => Some(Mode::Pipe(flag_value(&mut args, "--pipe")?)),
            other => return Err(format!("unknown argument {other}\n{}", usage())),
        };
        if let Some(selected) = selected {
            if mode.is_some() {
                return Err(usage());
            }
            mode = Some(selected);
        }
    }
    mode.map(|mode| (mode, tls)).ok_or_else(usage)
}

fn flag_value(args: &mut std::slice::Iter<'_, String>, flag: &str) -> Result<String, String> {
    args.next()
        .cloned()
        .ok_or_else(|| format!("missing value for {flag}\n{}", usage()))
}

async fn bind(mode: Mode) -> Result<(Listener, String), String> {
    match mode {
        Mode::Tcp(address) => {
            let listener = TcpListener::bind(&address)
                .await
                .map_err(|error| format!("bind TCP {address}: {error}"))?;
            let address = listener
                .local_addr()
                .map_err(|error| format!("local address: {error}"))?
                .to_string();
            Ok((Listener::Tcp(listener), address))
        }
        #[cfg(unix)]
        Mode::Unix(path) => {
            let path = abstract_path(path)?;
            let listener = tokio::net::UnixListener::bind(&path)
                .map_err(|error| format!("bind Unix socket {path}: {error}"))?;
            Ok((Listener::Unix(listener), path))
        }
        #[cfg(windows)]
        Mode::Pipe(name) => {
            let listener = kurpc_testserver::PipeListener::bind(&name)
                .map_err(|error| format!("bind named pipe {name}: {error}"))?;
            Ok((Listener::NamedPipe(listener), name))
        }
    }
}

/// `@name` stands in for an abstract-namespace path. The bound address keeps the leading
/// NUL, which is what the client dials; only the command-line form uses `@`.
#[cfg(unix)]
fn abstract_path(path: String) -> Result<String, String> {
    match path.strip_prefix('@') {
        Some(name) if !name.is_empty() => Ok(format!("\0{name}")),
        Some(_) => Err("abstract socket name must not be empty".to_owned()),
        None => Ok(path),
    }
}

fn usage() -> String {
    let mut options = "--tcp <addr>".to_owned();
    #[cfg(unix)]
    options.push_str(" | --unix <path|@name>");
    #[cfg(windows)]
    options.push_str(" | --pipe <name>");
    format!("usage: kurpc-testserver ({options}) [--tls]")
}
