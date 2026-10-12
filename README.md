# zapret KSU

A KernelSU module that runs zapret to bypass DPI blocking. It uses nfqws (zapret 1) and nfqws2 (zapret 2), and includes a WebUI to control everything from the phone. It also runs an encrypted DNS proxy.

Primary target: KernelSU and KernelSU-Next (arm64). Magisk and APatch are supported but untested.

## Install

1. Flash `zapret-ksu-v1.1.1.zip` in the KernelSU manager and reboot.
2. Open the WebUI, go to **Components** and tap **Update all**. This downloads nfqws, nfqws2, the zapret 2 presets, the zapret 1 strategies and dnscrypt-proxy.
3. In **Settings → Engine and preset**, choose an engine and a preset or strategy.
4. Tap **Start** on the **Home** tab.

Requirements: internet access for the first download, and `curl` on the phone (recommended). Without `curl`, downloads still work but HTTPS certificates are not checked.

## Engines

- **nfqws2 (zapret 2, recommended)** uses the presets. Each preset is checked with `--dry-run` before it starts.
- **nfqws1 (zapret 1)** uses the `.bat` strategies from zapret-discord-youtube. It also offers the game filter, ipset mode and fake-packet options.

## Blockcheck

Finds the preset that gets your blocked domains through. Each domain is checked over HTTP, TLS 1.2, TLS 1.3 and QUIC, once without bypass (baseline) and once per preset. Starting it stops the running engine and locks Home, Settings and Components until it finishes.

## Startup

- **Start engine after boot**: starts the saved engine and strategy after boot.
- **Start DoH proxy after boot**: starts the DNS proxy after boot.
- **Enable auto network profile**: uses a different engine, preset and interface for Wi-Fi and mobile data. Wi-Fi takes priority. With **Stop the engine when no connection is up** on, the engine stops when neither network is up.

After boot the module waits 20 seconds, then tries to start up to 12 times, 10 seconds apart. Each attempt is logged under the Service tag.

## Interception and bypass

- **Web ports** (TCP, default 80 and 443) and **QUIC** (UDP 443). **Custom UDP ports** adds ports or ranges for WireGuard, Amnezia and other UDP protocols, for example `51820` or `40000-40100`.
- **Skip private networks**, **Skip VPN tunnels** and **Conntrack accounting** reduce CPU use and avoid touching local or VPN traffic.
- **App filtering** applies the bypass to all apps, only the selected apps, or all apps except the selected ones. Apps are matched by Android UID, so only outgoing traffic can be matched.
- The app picker shows user apps by default. Turn on **System apps** to list the rest.

## Encrypted DNS

The **Local DoH proxy** runs dnscrypt-proxy on `127.0.0.1:5354`. It redirects port 53 (UDP and TCP, IPv4 and IPv6 when available) of every app that uses the system DNS to the proxy, so those apps send encrypted DoH queries.

- Private DNS is turned off while the proxy runs, because Private DNS bypasses port 53.
- Apps with their own secure DNS (Chrome, Firefox) skip the proxy. With their secure DNS off, they use it like any other app.
- If the proxy stops unexpectedly, the redirect is removed at once so DNS keeps working.
- Pick providers in the card. **Blocking only** selects malware and ad blockers that don't forward your client subnet. Providers come from `config/doh-providers.txt`.
- **Use fastest (Private DNS)** instead sets Android Private DNS (DoT) to the fastest provider with a DoT host. It cannot run together with the proxy.

Home shows the active DNS mode: DoH with the provider count, DoT with the host name, or off.

## Backup and import

- **Export backup** writes a `.tar.gz` of the settings, lists and presets to the Download folder. **Import backup** restores one from a full path. A corrupt `zapret.conf` is refused, and the previous config is kept as `zapret.conf.bak-<time>`.
- In the lists and strategy editor, **Import** loads a `.bat` (must contain `winws.exe`) or a zapret 2 preset (must contain `--` options). Existing names are never overwritten.

## Data and updates

Downloaded engines, presets, strategies and dnscrypt-proxy are kept in `/data/adb/zapret-ksu-data`, so module updates keep them. Uninstalling the module does not delete this folder.

## Troubleshooting

- **Engine fails to start**: open Logs and filter for Engine. The first lines show what nfqws rejected.
- **Download fails**: filter for Downloads. Each step and checksum result is logged.
- **DNS proxy fails to start**: filter for Control.
- **Start is greyed out**: Blockcheck is running. It unlocks when it finishes or when you tap Cancel.

## Files

- `scripts/zapret-ctl.sh`: controller for the WebUI and boot script.
- `scripts/firewall.sh`: nftables or iptables rules.
- `scripts/fetch.sh`: downloads engines, presets, strategies and dnscrypt-proxy.
- `scripts/blockcheck.sh`: Blockcheck.
- `config/zapret.conf`: settings.
- `config/doh-providers.txt`: DoH providers.
- `zapret.log`: log.

## Credits

- [bol-van/zapret](https://github.com/bol-van/zapret) (nfqws) and [bol-van/zapret2](https://github.com/bol-van/zapret2) (nfqws2)
- [Flowseal/zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube): zapret 1 strategies
- [zapretdiscordyoutube/zapret2-youtube-discord](https://git.zapret.moe/zapretdiscordyoutube/zapret2-youtube-discord): zapret 2 presets, Lua scripts and lists
- [zapretdiscordyoutube/magisk-zapret2](https://git.zapret.moe/zapretdiscordyoutube/magisk-zapret2): the base of this module
- [adivenxnataly/KsuWebUI](https://github.com/adivenxnataly/KsuWebUI): WebUI runtime
- [DNSCrypt/dnscrypt-proxy](https://github.com/DNSCrypt/dnscrypt-proxy): DNS proxy
