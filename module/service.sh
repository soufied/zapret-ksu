#!/system/bin/sh
MODDIR=${0%/*}
export MLOG_ECHO=0
. "$MODDIR/scripts/common.sh"
CTL="$MODDIR/scripts/zapret-ctl.sh"

try_start() {
    n=1
    while [ "$n" -le 12 ]; do
        if ZAPRET_IGNORE_PROFILE="$1" sh "$CTL" start >>"$LOG" 2>&1; then
            mlog svc INFO "$2 started (attempt $n)"
            return 0
        fi
        mlog svc INFO "$2 attempt $n failed; retrying in 10 s"
        n=$((n + 1))
        sleep 10
    done
    mlog svc ERROR "$2 did not start after 12 attempts; press Start in the zapret-ksu app"
    return 1
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do
    sleep 2
done
sleep 5

rotate_log
mlog svc INFO "boot completed; checking startup options"
sh "$CTL" network-clean boot >>"$LOG" 2>&1
rm -f "$MODDIR/run/auto.wanted" "$MODDIR/run/auto.pid" "$MODDIR/run/auto.applied"

AUTO_NETWORK=$(conf_get AUTO_NETWORK)
AUTO_NETWORK_START=$(conf_get AUTO_NETWORK_START)
AUTO_START=$(conf_get AUTO_START)

if [ "$(conf_get DOH_AUTO_START)" = 1 ]; then
    if [ ! -x "$MODDIR/bin/dnscrypt-proxy" ] && [ -x /data/adb/zapret-ksu-data/bin/dnscrypt-proxy ]; then
        mkdir -p "$MODDIR/bin"
        cp -f /data/adb/zapret-ksu-data/bin/dnscrypt-proxy "$MODDIR/bin/dnscrypt-proxy"
        chmod 0755 "$MODDIR/bin/dnscrypt-proxy"
    fi
    sh "$CTL" doh-start >>"$LOG" 2>&1 || mlog svc ERROR "DoH proxy did not start; press Start in the zapret-ksu app"
fi

if [ ! -x "$MODDIR/bin/tg-ws-proxy" ] && [ -x /data/adb/zapret-ksu-data/bin/tg-ws-proxy ]; then
    mkdir -p "$MODDIR/bin"
    cp -f /data/adb/zapret-ksu-data/bin/tg-ws-proxy "$MODDIR/bin/tg-ws-proxy"
    chmod 0755 "$MODDIR/bin/tg-ws-proxy"
fi

if [ "$(conf_get TG_AUTO_START)" = 1 ]; then
    sh "$CTL" tg-start >>"$LOG" 2>&1 || mlog svc ERROR "TG WS Proxy did not start; press Start in the zapret-ksu app"
fi

if [ "$AUTO_NETWORK" = 1 ]; then
    sh "$CTL" auto-ensure >>"$LOG" 2>&1
fi

if [ "$AUTO_NETWORK" = 1 ] && [ "$AUTO_NETWORK_START" = 1 ]; then
    mkdir -p "$MODDIR/run"
    echo 1 > "$MODDIR/run/auto.wanted"
    try_start "" "auto network profile"
elif [ "$AUTO_START" = 1 ]; then
    try_start 1 "engine (saved settings)"
else
    mlog svc INFO "no automatic start selected; press Start in the zapret-ksu app"
fi
