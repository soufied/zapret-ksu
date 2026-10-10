#!/system/bin/sh

MODDIR=${0%/*}
export MLOG_ECHO=0
. "$MODDIR/scripts/common.sh"
CTL="$MODDIR/scripts/zapret-ctl.sh"

conf_value() {
    sed -n "s/^$1=//p" "$MODDIR/config/zapret.conf" 2>/dev/null | tail -n 1 | tr -d '"'
}

try_start() { # ignore_profile label
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
    mlog svc ERROR "$2 did not start after 12 attempts; press Start in the WebUI"
    return 1
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do
    sleep 2
done
sleep 20

rotate_log
mlog svc INFO "boot completed; checking startup options"
rm -f "$MODDIR/run/auto.wanted" "$MODDIR/run/auto.pid" "$MODDIR/run/auto.applied"

AUTO_NETWORK=$(conf_value AUTO_NETWORK)
AUTO_NETWORK_START=$(conf_value AUTO_NETWORK_START)
AUTO_START=$(conf_value AUTO_START)

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
    mlog svc INFO "no automatic start selected; press Start in the WebUI"
fi
