#!/system/bin/sh
MODDIR=${0%/*}
export MLOG_ECHO=0
. "$MODDIR/scripts/common.sh"

mlog svc INFO "uninstalling: stopping engines and removing firewall rules"
if [ -f "$MODDIR/scripts/zapret-ctl.sh" ]; then
    sh "$MODDIR/scripts/zapret-ctl.sh" stop >>"$LOG" 2>&1
fi

if [ -f "$MODDIR/run/auto.pid" ]; then
    kill "$(cat "$MODDIR/run/auto.pid")" 2>/dev/null
fi

for p in $(pidof zapret-daemon nfqws nfqws2 tg-ws-proxy 2>/dev/null); do
    kill "$p" 2>/dev/null
done
mlog svc INFO "uninstall cleanup finished"
