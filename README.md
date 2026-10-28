# Simple Tunnel (HTTP proxy)

Tunneling semua trafik HP lewat HTTP proxy di VPS. Tanpa iklan, tanpa tracker, tanpa library tambahan.

Cara kerja: VpnService -> engine hev-socks5-tunnel -> jembatan SOCKS5 lokal (HttpBridge.kt) -> HTTP CONNECT ke VPS.
Nama domain di-resolve di sisi proxy (mapdns), jadi DNS tidak bocor.

## Build tanpa Android Studio (GitHub Actions)
1. Buat repo GitHub baru, upload semua isi folder ini (termasuk folder .github).
2. Tab Actions > Build APK > Run workflow.
3. Unduh artifact `SimpleTunnel-apk` (app-debug.apk), install di HP.

## Build lokal (Android Studio)
1. git clone --recursive https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
2. Buka folder ini di Android Studio (pasang NDK), lalu Build > Build APK.

## Pakai
Isi host VPS, port HTTP proxy, username/password (jika ada), Start, izinkan permintaan VPN.

## Batasan
- Hanya TCP. UDP (misalnya QUIC/HTTP3) tidak lewat; aplikasi biasanya otomatis pindah ke TCP.
- Proxy harus mengizinkan metode CONNECT ke port tujuan (sebagian proxy hanya mengizinkan 443).
- IPv6 tidak di-tunnel.
