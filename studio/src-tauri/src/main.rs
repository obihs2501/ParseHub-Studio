#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{
    fs::{self, File},
    io::{BufRead, BufReader},
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::{mpsc, Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
use tauri::{Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

#[derive(Default)]
struct Backend(Mutex<Option<Child>>);

impl Backend {
    fn stop(&self) {
        if let Some(mut child) = self.0.lock().unwrap().take() {
            drop(child.stdin.take());
            let deadline = Instant::now() + Duration::from_secs(8);
            while Instant::now() < deadline {
                if matches!(child.try_wait(), Ok(Some(_))) {
                    return;
                }
                thread::sleep(Duration::from_millis(100));
            }
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

fn launch_backend(app: &tauri::AppHandle) -> Result<(u16, String), String> {
    let data_dir = app.path().app_local_data_dir().map_err(|error| error.to_string())?;
    fs::create_dir_all(&data_dir).map_err(|error| error.to_string())?;
    let log = File::create(data_dir.join("sidecar.log")).map_err(|error| error.to_string())?;
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let filename = format!("parsehub-sidecar{}", std::env::consts::EXE_SUFFIX);
    let mut sidecar = executable.parent().ok_or("Missing executable directory")?.join(filename);
    if cfg!(debug_assertions) && !sidecar.exists() {
        sidecar = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(format!(
            "binaries/parsehub-sidecar-{}{}", env!("TARGET_TRIPLE"), std::env::consts::EXE_SUFFIX
        ));
    }
    let token = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
    let mut command = Command::new(sidecar);
    command.current_dir(&data_dir)
        .env("PARSEHUB_SESSION_TOKEN", &token)
        .env("PYTHONUTF8", "1")
        .env("PYTHONIOENCODING", "utf-8")
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::from(log));
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn().map_err(|error| format!("无法启动 Python 服务: {error}"))?;
    let output = child.stdout.take().ok_or("Missing sidecar stdout")?;
    *app.state::<Backend>().0.lock().unwrap() = Some(child);
    let (sender, receiver) = mpsc::channel();
    thread::spawn(move || {
        for line in BufReader::new(output).lines().map_while(Result::ok) {
            if let Ok(message) = serde_json::from_str::<serde_json::Value>(&line) {
                if message["event"] == "ready" {
                    if let Some(port) = message["port"].as_u64().filter(|port| *port > 0 && *port <= 65535) {
                        let _ = sender.send(port as u16);
                    }
                }
            }
        }
    });
    receiver.recv_timeout(Duration::from_secs(120))
        .map(|port| (port, token))
        .map_err(|error| format!("Python 服务启动失败或超时 ({error})。诊断日志: {}", data_dir.join("sidecar.log").display()))
}

fn main() {
    let app = tauri::Builder::default()
        .manage(Backend::default())
        .on_window_event(|window, event| {
            if window.label() == "main" && matches!(event, tauri::WindowEvent::CloseRequested { .. }) {
                window.app_handle().exit(0);
            }
        })
        .setup(|app| {
            let allowed_origin = Arc::new(Mutex::new(None::<String>));
            let navigation_origin = allowed_origin.clone();
            let smoke_test = std::env::var_os("PARSEHUB_DESKTOP_SMOKE_TEST").is_some();
            let smoke_handle = app.handle().clone();
            let navigation_handle = app.handle().clone();
            let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("ParseHub Studio")
                .inner_size(1040.0, 680.0)
                .min_inner_size(820.0, 560.0)
                .center()
                .on_navigation(move |url| {
                    let trusted = navigation_origin.lock().unwrap().as_ref().is_some_and(|origin| url.origin().ascii_serialization() == *origin);
                    if trusted && url.path() == "/external/douyin" {
                        let handle = navigation_handle.clone();
                        thread::spawn(move || {
                            if let Some(external) = handle.get_webview_window("external-parser") {
                                let _ = external.show();
                                let _ = external.set_focus();
                            } else {
                                let result = WebviewWindowBuilder::new(&handle, "external-parser", WebviewUrl::External("https://dy.kukutool.com/".parse().unwrap()))
                                    .title("第三方解析 · kukutool（请手动粘贴链接）")
                                    .inner_size(1000.0, 680.0)
                                    .center()
                                    .on_navigation(|destination| destination.scheme() == "https" || destination.as_str() == "about:blank")
                                    .build();
                                if result.is_err() {
                                    if let Some(main) = handle.get_webview_window("main") {
                                        let _ = main.eval("window.alert('无法打开第三方窗口，请在浏览器中访问 https://dy.kukutool.com/')");
                                    }
                                }
                            }
                        });
                        return false;
                    }
                    url.scheme() == "tauri" || url.host_str() == Some("tauri.localhost")
                        || trusted
                })
                .on_page_load(move |_window, payload| {
                    if smoke_test && payload.url().scheme() == "http" && payload.url().path() == "/"
                        && matches!(payload.event(), tauri::webview::PageLoadEvent::Finished) {
                        smoke_handle.exit(0);
                    }
                })
                .build()?;
            let handle = app.handle().clone();
            tauri::async_runtime::spawn_blocking(move || {
                let result = launch_backend(&handle).and_then(|(port, token)| {
                    let origin = format!("http://127.0.0.1:{port}");
                    *allowed_origin.lock().unwrap() = Some(origin.clone());
                    let url = format!("{origin}/desktop?token={token}").parse().map_err(|error| format!("{error}"))?;
                    window.navigate(url).map_err(|error| error.to_string())
                });
                if let Err(error) = result {
                    handle.state::<Backend>().stop();
                    let message = serde_json::to_string(&error).unwrap_or_default();
                    let _ = window.eval(&format!("document.getElementById('status').textContent={message};document.body.dataset.failed='true'"));
                    if smoke_test {
                        handle.exit(1);
                    }
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Could not initialize ParseHub Studio");
    app.run(|handle, event| {
        if matches!(event, RunEvent::Exit) {
            handle.state::<Backend>().stop();
        }
    });
}
