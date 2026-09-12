# Internet Optimizer — Android App (sem root)

Um app Android nativo (Kotlin + Rust) que otimiza a navegação e uso de internet
do aparelho **sem root, sem Magisk, sem Xposed, sem ADB**.

## Como funciona

O app resolve dois problemas complementares:

1. **Tráfego de todo o aparelho** — capturado via `VpnService` (API pública Android) e
   redirecionado por um túnel local `TUN` → `TUN2SOCKS` implementado em **Rust**.
   Dentro do túnel, consultas DNS são interceptadas e resolvidas via **DNS-over-HTTPS (DoH)**.

2. **Tráfego do próprio app** — checagem de latência, busca de configuração e
   telemetria opcional usam **Cronet** (Chromium HTTP/2 + HTTP/3 QUIC) via
   `com.google.android.gms:play-services-cronet`, com fallback para **OkHttp**.

## Stack técnica

| Camada         | Tecnologia                              |
|----------------|------------------------------------------|
| UI             | Kotlin + Jetpack Compose + Material 3    |
| Túnel (núcleo) | Rust (via JNI) — crate `tun_interface`   |
| DNS            | DoH (Cloudflare 1.1.1.1, Google 8.8.8.8) |
| Proxy          | SOCKS5 / HTTP CONNECT (cliente externo)  |
| HTTP app       | Cronet → OkHttp fallback                 |
| Build          | Gradle + Android Gradle Plugin 8.7       |

## Estrutura de módulos

```
internet-optimizer/
├── app/                          # Módulo Android (Kotlin + Compose)
│   ├── src/main/
│   │   ├── java/com/internetoptimizer/
│   │   │   ├── app/              # Entry point, config, boot receiver
│   │   │   ├── tunnel/           # VpnService, TunnelService, JNI wrapper
│   │   │   ├── network/          # Cronet/OkHttp client, speed test
│   │   │   └── ui/               # Jetpack Compose screens
│   │   ├── cpp/                  # CMakeLists + build script for Rust
│   │   ├── res/                  # strings, colors, themes, icons
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── rust/                         # Crate Rust tun_interface (native)
│   ├── Cargo.toml
│   └── src/lib.rs
├── build.gradle                  # Top-level
├── settings.gradle
├── gradle.properties
└── README.md
```

## Build

### Pré-requisitos
- JDK 17+
- Android SDK (compileSdk 35, build-tools 35)
- Android NDK r26+
- Rust + `cargo-ndk` (`cargo install cargo-ndk`)

### 1. Compilar a biblioteca nativa (Rust)

```bash
cd app/src/main/cpp
./build-native.sh
```

Isso gera `libtun_interface.so` para `arm64-v8a`, `armeabi-v7a` e `x86_64`
e os coloca em `app/src/main/jniLibs/`.

### 2. Build do APK

```bash
cd internet-optimizer
./gradlew assembleDebug
```

O APK resultante estará em `app/build/outputs/apk/debug/`.

## Permissões

| Permissão               | Motivo                                            |
|-------------------------|---------------------------------------------------|
| `BIND_VPN_SERVICE`      | Criar o TUN (VpnService)                          |
| `INTERNET`              | Requisições HTTP do próprio app                   |
| `ACCESS_NETWORK_STATE`  | Verificar conectividade antes de testes           |
| `FOREGROUND_SERVICE`    | Manter o VpnService ativo em primeiro plano       |
| `RECEIVE_BOOT_COMPLETED`| Auto-start após reboot                            |

## Privacidade

- **Nenhum dado pessoal ou de tráfego é coletado.**
- O DNS sobre HTTPS é resolvido localmente — o endpoint é configurável pelo usuário.
- A telemetria é **opcional e desativada por padrão**.
- O proxy é sempre um servidor **externo** — nenhum proxy é embutido no app.

## Roadmap

### MVP (implementado)
- [x] Botão liga/desliga do túnel
- [x] Túnel local com DoH (sem proxy, sem exclusão de apps)
- [x] Teste de velocidade simples (latência antes/depois)

### Fase 2 (parcialmente implementado)
- [ ] Exclusão de apps do túnel (`addDisallowedApplication`)
- [ ] Suporte a proxy SOCKS5/HTTP externo
- [ ] Múltiplos endpoints de DNS com teste automático

## Arquivo nativo (Rust)

O crate `tun_interface` implementa:

- `Tunnel::run()` — loop de eventos de leitura/escrita no TUN fd
- `handle_ipv4()` / `handle_ipv6()` — parsing de pacotes IP
- `handle_tcp_packet()` / `handle_udp_packet()` — encaminhamento de fluxos
- `resolve_dns_over_https()` — interceptação de consultas DNS (stub; DoH completo com rustls em Fase 2)
- JNI: `nativeInitTunnel`, `nativeRunTunnel`, `nativeShutdownTunnel`,
  `nativeSetDnsOverride`, `nativeSetProxy`

```rust
// Exemplo de uso JNI:
let handle = NativeTunnel.initTunnel();
NativeTunnel.setDnsOverride(handle, "https://1.1.1.1/dns-query", "1.1.1.1", true);
// runTunnel blocks on a background thread
```
