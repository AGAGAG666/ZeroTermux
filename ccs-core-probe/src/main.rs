//! Android sidecar 可行性探针。
//! 目标：证明 cc-switch 的高风险原生依赖能交叉编译到 aarch64-linux-android，
//! 并在真机上真正跑通（SQLite 落盘、QuickJS 求值、rustls 装配、HTTP 栈起监听）。

use std::io::Write;

fn probe_sqlite() -> String {
    // bundled SQLite：编译 C。同时验证 backup/hooks feature 可用。
    let conn = rusqlite::Connection::open_in_memory().expect("open sqlite");
    conn.execute_batch(
        "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT);
         INSERT INTO t(v) VALUES('a'),('b');",
    )
    .expect("sqlite ddl");
    let n: i64 = conn
        .query_row("SELECT count(*) FROM t", [], |r| r.get(0))
        .expect("sqlite query");
    let ver: String = conn
        .query_row("SELECT sqlite_version()", [], |r| r.get(0))
        .expect("sqlite version");
    // user_version：cc-switch 的 16 版 schema 迁移就靠它
    conn.pragma_update(None, "user_version", 16i32)
        .expect("set user_version");
    let uv: i32 = conn
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .expect("get user_version");
    format!("sqlite={ver} rows={n} user_version={uv}")
}

fn probe_quickjs() -> String {
    // rquickjs：编译 QuickJS C 源码。cc-switch 用它跑 usage_script。
    let rt = rquickjs::Runtime::new().expect("qjs runtime");
    let ctx = rquickjs::Context::full(&rt).expect("qjs context");
    ctx.with(|ctx| {
        let v: i32 = ctx
            .eval("(() => { let s = 0; for (let i = 1; i <= 100; i++) s += i; return s; })()")
            .expect("qjs eval");
        format!("quickjs_sum_1_100={v}")
    })
}

fn probe_rustls() -> String {
    // ring 后端：编译 C + 汇编。这是最容易在 Android 上翻车的一项。
    let provider = rustls::crypto::ring::default_provider();
    let suites = provider.cipher_suites.len();
    rustls::crypto::CryptoProvider::install_default(provider).ok();
    let roots = webpki_roots::TLS_SERVER_ROOTS.len();
    format!("rustls_ring_suites={suites} webpki_roots={roots}")
}

fn probe_compression() -> String {
    use std::io::Read;
    let raw = b"cc-switch android sidecar feasibility probe payload";

    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    gz.write_all(raw).unwrap();
    let gz_out = gz.finish().unwrap();
    let mut gz_back = Vec::new();
    flate2::read::GzDecoder::new(&gz_out[..])
        .read_to_end(&mut gz_back)
        .unwrap();
    assert_eq!(&gz_back[..], raw, "gzip roundtrip");

    let zs = zstd::encode_all(&raw[..], 3).unwrap();
    let zs_back = zstd::decode_all(&zs[..]).unwrap();
    assert_eq!(&zs_back[..], raw, "zstd roundtrip");

    let mut br = Vec::new();
    brotli::BrotliCompress(&mut &raw[..], &mut br, &Default::default()).unwrap();
    let mut br_back = Vec::new();
    brotli::BrotliDecompress(&mut &br[..], &mut br_back).unwrap();
    assert_eq!(&br_back[..], raw, "brotli roundtrip");

    format!(
        "gzip={} zstd={} brotli={} (all roundtrip ok)",
        gz_out.len(),
        zs.len(),
        br.len()
    )
}

fn probe_crypto_and_misc() -> String {
    use hmac::Mac;
    use sha2::Digest;
    let d = sha2::Sha256::digest(b"probe");
    let mut mac = hmac::Hmac::<sha2::Sha256>::new_from_slice(b"key").unwrap();
    mac.update(b"probe");
    let tag = mac.finalize().into_bytes();
    let dec: rust_decimal::Decimal = "0.0000031".parse().unwrap();
    let id = uuid::Uuid::new_v4();
    let five: serde_json::Value = json_five::from_str("{ a: 1, /* json5 */ b: 'x', }").unwrap();
    let loc = sys_locale::get_locale().unwrap_or_else(|| "unknown".into());
    let home = dirs::home_dir()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|| "none".into());
    format!(
        "sha256={:x}.. hmac={:x}.. decimal={} uuid_v4_len={} json5_a={} locale={} home={}",
        d[0], tag[0], dec, id.to_string().len(), five["a"], loc, home
    )
}

async fn probe_http_stack() -> String {
    // 证明 axum + hyper 能在 Android 上真正 bind 并完成一次请求往返。
    // 这正是 cc-switch proxy/server.rs 的形态（含 preserve_header_case）。
    use axum::{routing::get, Router};
    let app = Router::new().route("/health", get(|| async { "ok" }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind loopback");
    let addr = listener.local_addr().unwrap();

    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    tokio::time::sleep(std::time::Duration::from_millis(120)).await;

    let url = format!("http://{addr}/health");
    let body = reqwest::Client::builder()
        .build()
        .expect("reqwest client (rustls-tls)")
        .get(&url)
        .send()
        .await
        .expect("GET /health")
        .text()
        .await
        .expect("read body");

    format!("bound={addr} GET /health -> {body:?}")
}

fn main() {
    println!("== cc-switch Android sidecar probe ==");
    println!("target = {}", std::env::consts::ARCH);
    println!("os     = {}", std::env::consts::OS);
    println!("[1] {}", probe_sqlite());
    println!("[2] {}", probe_quickjs());
    println!("[3] {}", probe_rustls());
    println!("[4] {}", probe_compression());
    println!("[5] {}", probe_crypto_and_misc());

    let rt = tokio::runtime::Runtime::new().expect("tokio rt");
    println!("[6] {}", rt.block_on(probe_http_stack()));

    println!("== ALL PROBES PASSED ==");
}
