# zapret KSU

A KernelSU module that runs zapret to bypass DPI blocking. It uses nfqws (zapret 1) and nfqws2 (zapret 2), and includes a WebUI to control everything from the phone. It also runs an encrypted DNS proxy.

Primary target: KernelSU and KernelSU-Next (arm64). Magisk and APatch are supported but untested.

## Install

1. Flash `zapret-ksu-v1.2.2.zip` in the KernelSU manager and reboot.
2. Open the WebUI, go to **Components** and tap **Update all**. This downloads nfqws, nfqws2, the zapret 2 presets, the zapret 1 strategies and dnscrypt-proxy.
3. In **Settings → Engine and preset**, choose an engine and a preset or strategy.
4. Tap **Start** on the **Home** tab.

Requirements: internet access for the first download, and `curl` on the phone (recommended). Without `curl`, downloads still work but HTTPS certificates are not checked.

## Engines

- **nfqws2 (zapret 2, recommended)** uses the presets. Each preset is checked with `--dry-run` before it starts.
- **nfqws1 (zapret 1)** uses the `.bat` strategies from zapret-discord-youtube. It also offers the game filter, ipset mode and fake-packet options.

## Network safety

Every firewall rule and DNS change the module makes is removed on each failure path, without a reboot:

- **Blockcheck** cleans up when it finishes, fails, is cancelled, or is killed. A run that dies without its own cleanup is detected on the next status check and cleaned up then.
- **Start** rolls back the firewall if the engine fails to start.
- **DoH proxy:** if the proxy dies, its DNS redirect and Private DNS change are removed. If the proxy keeps running but DNS through it fails three checks in a row (about 30 seconds), the redirect is removed too.
- **Watchdog:** while the firewall or the DNS redirect is active, a watchdog removes the firewall if no engine is running, and the redirect if the proxy is gone.
- **Boot:** leftover state from before the boot is cleared at startup.
- **Restore network now** (Home) removes everything the module added, stops the engines and the DoH proxy, and restores Private DNS. It only touches the module's own tables and chains, never Android's rules.

## Blockcheck

Finds the preset that gets your blocked domains through. Starting it stops the running engine and locks Home, Settings and Components until it finishes.

It runs in this order:

1. **Plain baseline**: no bypass and no encrypted DNS. Each domain is checked over HTTP, TLS 1.2, TLS 1.3 and QUIC.
2. **Encrypted-DNS baseline**: the same checks with no bypass, using DoH (or DoT when DoH is not available). If this reaches more domains than the plain baseline, every preset is tested with encrypted DNS on. Otherwise presets run on plain DNS.
3. **Each preset**, up to the number chosen in the list.

A domain counts as available when its HTTPS probe succeeds. Results fill in as each step finishes, sorted best-first, so you can see progress before the run ends. Results show the domains reached, each probe type, and whether encrypted DNS was used. n/a means the probe could not run. QUIC needs a curl build with HTTP/3: if the default curl has none, Blockcheck uses any HTTP/3 build found at Termux, `/system/xbin/curl`, or the KernelSU or Magisk bin paths, and logs which one it chose. Otherwise QUIC shows n/a. The log lists curl exit codes for failed probes (6 DNS, 7 refused, 28 timeout, 35 TLS handshake, 56 reset) so you can tell a real block from a tool problem.

Blockcheck writes its own log to `run/blockcheck.log`, so `zapret.log` stays readable. Each preset line shows whether the engine came up, and each failed probe shows curl's own error text. **Copy report** in the Blockcheck tab copies the state, the results and that log.

The app filter is switched off for the run. In include mode the engine only handles the listed apps, and Blockcheck's own curl is not one of them, so its results would not reflect the presets. The previous setting is restored when the run ends, including on cancel.

The **DNS during the run** option sets how encrypted DNS is used. **Auto** keeps the behaviour above. **Force DoH** or **Force DoT** runs every preset with that mode on, even when it adds no domains. If the forced mode can't start, the run stops with an error instead of falling back.

Encrypted DNS is turned off again when Blockcheck finishes, and the previous Private DNS setting is restored. **Use** selects a preset but does not turn encrypted DNS on; do that yourself if the best row needs it.

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
- App icons are off by default. Turn on **Icons** in the picker to load them. Each icon is read from its APK, so the first load of a long list takes a while. Icons are cached once loaded, and only rows on screen are loaded.
- Names in the picker come from `config/app-labels.txt`. To rename an app, add `package=Name` to `config/app-names.txt`; it overrides the built-in name.

## Encrypted DNS

The **Local DoH proxy** runs dnscrypt-proxy on `127.0.0.1:5354`. It redirects port 53 (UDP and TCP, IPv4 and IPv6 when available) of every app that uses the system DNS to the proxy, so those apps send encrypted DoH queries.

- Private DNS is turned off while the proxy runs, because Private DNS bypasses port 53.
- Apps with their own secure DNS (Chrome, Firefox) skip the proxy. With their secure DNS off, they use it like any other app.
- If the proxy stops unexpectedly, the redirect is removed at once so DNS keeps working.
- **Test speed** sends three DNS queries to each ticked provider and shows the ping, which is the TCP round trip with the DNS lookup excluded, so hostname and IP providers are compared fairly.
- **Best 3** tests every provider, keeps the three that answer most often with the lowest ping, and saves them as your providers. It keeps at most one provider per operator, so the three are not three addresses of the same service. If fewer than three operators answer, the list is filled with another address of an operator that already answered.
- Pick providers in the card. **Blocking only** selects malware and ad blockers that don't forward your client subnet. Providers come from `config/doh-providers.txt`. The ticked providers are saved as you change them. Restart the DoH proxy to apply a changed selection.
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
