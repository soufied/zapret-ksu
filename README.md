# zapret KSU

A root module for Android that runs zapret. It bypasses DPI blocking with nfqws (zapret 1) and nfqws2 (zapret 2), and includes a WebUI to control everything from the phone.

The module is primarily made for **KernelSU** and **KernelSU-Next**. Support for **Magisk** and **APatch** is included but untested.

## Credits

The engines, presets and strategies come from these projects. This module packages and controls them.

- [bol-van/zapret](https://github.com/bol-van/zapret): nfqws.
- [bol-van/zapret2](https://github.com/bol-van/zapret2): nfqws2.
- [Flowseal/zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube): the zapret 1 strategies.
- [zapretdiscordyoutube/zapret2-youtube-discord](https://git.zapret.moe/zapretdiscordyoutube/zapret2-youtube-discord): the zapret 2 presets, Lua scripts and lists.
- [zapretdiscordyoutube/magisk-zapret2](https://git.zapret.moe/zapretdiscordyoutube/magisk-zapret2): the main idea and base of this module, taken from its Magisk module.
- [adivenxnataly/KsuWebUI](https://github.com/adivenxnataly/KsuWebUI): the WebUI runtime this module's interface runs on.

## Requirements

- An arm64 phone with **KernelSU** or **KernelSU-Next**, and root granted to the KsuWebUI. Magisk and APatch are untested.
- Internet access for the first download (Update components).
- Recommended: `curl` on the phone. Blockcheck needs it, and the downloader uses it to check HTTPS certificates. Without it, downloads still work but are not certificate-checked.

## Supported root managers

- **KernelSU and KernelSU-Next**: primary target, tested.
- **Magisk and APatch**: the installer and BusyBox lookup support them, but they are untested. The module finds BusyBox in each manager's folder: `/data/adb/ksu/bin/busybox` (KernelSU), `/data/adb/ap/bin/busybox` (APatch) or `/data/adb/magisk/busybox` (Magisk).

## Install

1. Flash `zapret-ksu-v1.0.0.zip` in the KernelSU manager and reboot.
2. Open the module's WebUI, go to **Components** and tap **Update all**. This downloads nfqws, nfqws2, the zapret 2 presets and the zapret 1 strategies. A banner shows progress.
3. Go to **Settings**, choose an **Engine** and a **Preset / strategy**.
4. Tap **Start** on the **Home** tab.

## Tabs

- **Home**: firewall state, the engine and strategy actually running, the auto network profile mapping, and the start/stop button.
- **Settings**: engine, strategy, network to intercept, ports, bypass rules, game filter (zapret 1), boot start, logging, the auto network profile, the lists and strategy editor, and the zapret 1 ipset and fake-packet options.
- **Blockcheck**: tests which strategy gets your blocked domains through. It checks HTTP, TLS 1.2, TLS 1.3 and QUIC, once with no bypass (the baseline) and once per strategy. Starting it stops any running engine and locks Home, Settings and Components until it finishes. Results appear as each check finishes. Pick the zapret 2 target lists to choose the domains it tests.
- **Components**: installed versions and update buttons for each part.
- **Logs**: the module log, with filters, search, copy and clear.

## Engines

- **nfqws2 (zapret 2, recommended)**: uses the presets. Each preset is turned into nfqws2 options, checked with `--dry-run`, and only then started.
- **nfqws1 (zapret 1)**: uses the `.bat` strategies from zapret-discord-youtube by Flowseal. They are converted to nfqws options. Game filter, ipset mode and replaceable fake packets are available for this engine.

## Startup options

- **Auto Start** (Settings → Startup): starts the saved engine and strategy after boot. It ignores the auto network profile.
- **Enable auto network profile** (Settings → Auto network profile): turns the profile on or off.
- **Start the profile after boot**: starts the profile after boot, separately from Auto Start.

After boot, the module waits 20 seconds, then tries to start up to 12 times, 10 seconds apart. Each attempt is logged under the Service tag, so you can see why a start failed.

## Auto network profile

When enabled, the engine follows the connection. Each network has its own engine, preset or strategy, and interface:

- **Wi-Fi** (default interface `wlan0`) and **mobile data** (default pattern `rmnet_data+`). A trailing `+` matches every interface whose name starts with that text.
- Wi-Fi takes priority when both are up. With **Stop the engine when no connection is up** on (default), the engine stops when neither is up and starts again when one comes back. Turn it off to keep the engine running.
- A network counts as active when it carries the default route (`/proc/net/route`). Wi-Fi is the interface named in the settings (default `wlan0`); mobile data is any interface matching the pattern (default `rmnet_data+`).
- The watcher checks every 10 seconds and only switches after two matching checks, so a flapping connection does not restart the engine.
- It only acts after you press **Start**, unless **Start the profile after boot** is on. **Stop** turns it off until you press Start again.

Interface names differ between phones. The Settings page lists the names the phone reports, so pick the ones that match your connection.

## Import

In the Lists and Strategies editor, **Import** loads a file from your phone. A `.bat` must contain a `winws.exe` line, and a zapret 2 preset must contain `--` options. If the name is already taken, the file is saved as `name (2).ext`, so nothing is overwritten.

The engine always follows the strategy file: a `.bat` runs on nfqws1 and a `.txt` preset runs on nfqws2. If the saved engine doesn't match, the module uses the right one and logs a warning.

## Bypass rules

- **Skip private networks**: traffic to and from local and private addresses is not touched.
- **Skip VPN tunnels**: traffic leaving through `tun*` or `dummy*` interfaces is not touched.
- **Conntrack accounting**: the kernel counts packets per connection. This lets the firewall limit interception to the first packets of each connection, which saves CPU. If the kernel lacks the feature, every packet on the intercepted ports reaches nfqws, which uses more CPU.

## Files

- `scripts/zapret-ctl.sh`: the controller used by the WebUI and the boot script.
- `scripts/firewall.sh`: the nftables or iptables rules.
- `scripts/fetch.sh`: downloads the engines, presets and strategies.
- `scripts/blockcheck.sh`: Blockcheck.
- `config/zapret.conf`: all settings.
- `zapret.log`: the log.

## Troubleshooting

- An engine fails to start: open the Logs tab and filter for Engine. The first lines show what nfqws rejected.
- A download fails: filter for Downloads. Each step and each checksum result is logged.
- Start is greyed out during Blockcheck: Blockcheck is running. It unlocks when it finishes or you tap Cancel.
