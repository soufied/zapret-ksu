#!/system/bin/sh
LOG="${ZAPRET_LOG:-$MODDIR/zapret.log}"
RUN_DIR="$MODDIR/run"
LOG_MAX=524288
MIRRORS_CONF="$MODDIR/config/mirrors.conf"
DEFAULT_GH_MIRRORS="https://cdn.akaere.online/ https://fastgit.cc/ https://jiashu.1win.eu.org/ https://ghm.078465.xyz/ https://gh-proxy.com/"
DEFAULT_ZAPRET_MIRRORS="https://git2.zapret.moe https://git3.zapret.moe https://git4.zapret.moe"
mconf_has() { grep -q "^$1=" "$MIRRORS_CONF" 2>/dev/null; }
mconf_get() { sed -n "s/^$1=//p" "$MIRRORS_CONF" 2>/dev/null | tail -n 1 | tr -d '"'; }
MIRRORS_ENABLED=1
if mconf_has MIRRORS_ENABLED; then MIRRORS_ENABLED=$(mconf_get MIRRORS_ENABLED); fi
GH_MIRRORS=$DEFAULT_GH_MIRRORS
if mconf_has GH_MIRRORS; then GH_MIRRORS=$(mconf_get GH_MIRRORS); fi
ZAPRET_MIRRORS=$DEFAULT_ZAPRET_MIRRORS
if mconf_has ZAPRET_MIRRORS; then ZAPRET_MIRRORS=$(mconf_get ZAPRET_MIRRORS); fi
MLOG_ECHO="${MLOG_ECHO:-1}"

mkrun() { mkdir -p "$RUN_DIR" 2>/dev/null; }

BB=""
for _bb in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
    if [ -x "$_bb" ]; then
        BB="$_bb"
        break
    fi
done

rotate_log() {
    [ -f "$LOG" ] || return 0
    size=$(wc -c < "$LOG" 2>/dev/null)
    if [ "${size:-0}" -gt "$LOG_MAX" ]; then
        mv -f "$LOG" "$LOG.1" 2>/dev/null
    fi
    return 0
}

bbx() {
    if [ -n "$BB" ]; then "$BB" "$@"; else "$@"; fi
}

conf_get() { sed -n "s/^$1=//p" "$MODDIR/config/zapret.conf" 2>/dev/null | tail -n 1 | tr -d '"'; }

LOG_ENABLED=$(conf_get LOG_ENABLED)

mlog() {
    _line="$(date '+%Y-%m-%d %H:%M:%S') [$1] $2: $3"
    if [ "${LOG_ENABLED:-1}" != 0 ]; then echo "$_line" >> "$LOG" 2>/dev/null; fi
    if [ "$MLOG_ECHO" = 1 ]; then echo "$_line"; fi
    return 0
}
