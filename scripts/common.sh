#!/system/bin/sh
LOG="${ZAPRET_LOG:-$MODDIR/zapret.log}"
RUN_DIR="$MODDIR/run"
LOG_MAX=524288
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

LOG_ENABLED=$(sed -n 's/^LOG_ENABLED=//p' "$MODDIR/config/zapret.conf" 2>/dev/null | tail -n 1 | tr -d '"')

mlog() {
    _line="$(date '+%Y-%m-%d %H:%M:%S') [$1] $2: $3"
    if [ "${LOG_ENABLED:-1}" != 0 ]; then echo "$_line" >> "$LOG" 2>/dev/null; fi
    if [ "$MLOG_ECHO" = 1 ]; then echo "$_line"; fi
    return 0
}
