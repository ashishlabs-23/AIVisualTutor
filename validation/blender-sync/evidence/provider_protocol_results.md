# Blender State Provider Protocol Results

**Protocol**: `AIVT_STATE_V1` TSV & HTTP loopback  
**Host**: `127.0.0.1`  
**Port**: `47629`  
**Socket Option**: `SO_REUSEADDR`  
**Default Timeout**: `5000ms` (with 2-attempt backoff on `SocketTimeoutException`)

---

## 1. Protocol Agreement Verification

| Specification | Kotlin Client | Python Addon | Status |
|---|---|---|---|
| Request Header | `AIVT_STATE_V1\n` | `b"AIVT_STATE_V1\n"` | AGREED |
| Response Header | `AIVT_STATE_V1\t<session>\t<scene>\t<mode>\t...` | TSV 9-field header | AGREED |
| Object Delimiter | Newline (`\n`) separated lines | `ident\tname\ttype\t...` | AGREED |
| Error Delimiter | `ERROR\t<base64_diagnostic>\n` | `ERROR\t<base64_diagnostic>\n` | AGREED |
| Max Response Bytes | 16 MB | Bounded by `MAX_OBJECTS = 10000` | AGREED |
| Delimiter Decoding | UTF-8, base64 urlsafe without padding | base64 urlsafe rstrip("=") | AGREED |

---

## 2. Telemetry Latency Characteristics

- **Warmup Query (Blender startup/window layout)**: ~2,800ms – 2,900ms.
- **Subsequent Queries (Steady State)**: 18ms – 95ms.
- **Previous Failure Point**: With `timeoutMillis = 1,500ms`, the warmup query timed out 100% of the time, throwing `SocketTimeoutException`.
- **Current Behavior**: With `timeoutMillis = 5,000ms`, the warmup query succeeds reliably on the first connect.
