#!/system/bin/sh

MODDIR="${0%/*}/.."
export MLOG_ECHO=0
. "$MODDIR/scripts/common.sh"

CTL="$MODDIR/scripts/zapret-ctl.sh"
Z1_DIR="$MODDIR/zapret1"
Z2_DIR="$MODDIR/zapret2"
DOMAINS_FILE="$MODDIR/config/blockcheck-domains.txt"
BC_STATE="$RUN_DIR/blockcheck.state"
BC_CSV="$RUN_DIR/blockcheck.csv"
TIMEOUT=3
STARTED="$(date +%s)"

bc_log() { mlog blockcheck "$1" "$2"; }

ENGINE_BC=""

write_state() {
    mkrun
    {
        echo "engine=$ENGINE_BC"
        echo "state=$1"
        echo "step=$2"
        echo "done=$3"
        echo "total=$4"
        echo "message=$5"
        echo "was_active=$6"
        echo "started=$STARTED"
        echo "updated=$(date +%s)"
    } > "$BC_STATE.tmp" && mv -f "$BC_STATE.tmp" "$BC_STATE"
}

probe() {
    code=$(curl -s -k -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time "$TIMEOUT" "$@" 2>/dev/null)
    [ -n "$code" ] && [ "$code" != "000" ]
}

domain_probes() {
    d="$1"; r=""
    probe "http://$d/" && r="$r h"
    probe --tlsv1.2 --tls-max 1.2 "https://$d/" && r="$r 2"
    probe --tlsv1.3 --tls-max 1.3 "https://$d/" && r="$r 3"
    if [ "$HAVE_H3" = 1 ]; then
        probe --http3-only "https://$d/" && r="$r q"
    fi
    echo "$r" > "$2"
}

measure() {
    M_HTTP=0; M_TLS12=0; M_TLS13=0; M_QUIC=0
    tmp="$RUN_DIR/bc-probe"
    rm -rf "$tmp"; mkdir -p "$tmp"
    n=0
    for d in $DOMAINS; do
        n=$((n + 1))
        domain_probes "$d" "$tmp/$n" &
    done
    wait
    for f in "$tmp"/*; do
        [ -f "$f" ] || continue
        r=" $(cat "$f") "
        case "$r" in *" h "*) M_HTTP=$((M_HTTP + 1)) ;; esac
        case "$r" in *" 2 "*) M_TLS12=$((M_TLS12 + 1)) ;; esac
        case "$r" in *" 3 "*) M_TLS13=$((M_TLS13 + 1)) ;; esac
        case "$r" in *" q "*) M_QUIC=$((M_QUIC + 1)) ;; esac
    done
    rm -rf "$tmp"
    per=3
    [ "$HAVE_H3" = 1 ] && per=4
    M_MAX=$((n * per))
    M_SCORE=$((M_HTTP + M_TLS12 + M_TLS13 + M_QUIC))
}

record() {
    echo "$1|$M_SCORE|$M_MAX|$M_HTTP|$M_TLS12|$M_TLS13|$M_QUIC|$2" >> "$RUN_DIR/blockcheck.raw"
}

fail_run() {
    bc_log ERROR "$1"
    write_state error "" 0 0 "$1" 0
    exit 1
}

[ -n "$(command -v curl)" ] || fail_run "curl is not installed; install curl to use Blockcheck"

mkrun
rm -f "$RUN_DIR/blockcheck.raw"

ENGINE_NOW=$(sh "$CTL" dump | sed -n 's/^ENGINE=//p')
case "$ENGINE_NOW" in
    nfqws1) STRAT_DIR="$Z1_DIR"; STRAT_GLOB='*.bat' ;;
    nfqws2) STRAT_DIR="$Z2_DIR/presets"; STRAT_GLOB='*.txt' ;;
    *) fail_run "unknown ENGINE '$ENGINE_NOW'" ;;
esac
ENGINE_BC="$ENGINE_NOW"
[ -f "$DOMAINS_FILE" ] || fail_run "missing config/blockcheck-domains.txt"

DOMAINS=$(grep -v '^[[:space:]]*#' "$DOMAINS_FILE" | grep -v '^[[:space:]]*$' | tr -d '\r' | tr '\n' ' ')
[ -n "$DOMAINS" ] || fail_run "no domains in blockcheck-domains.txt"

BC_LISTS="$MODDIR/config/blockcheck-lists.txt"
if [ "$ENGINE_NOW" = nfqws2 ] && [ -s "$BC_LISTS" ]; then
    picked=""
    while IFS= read -r ln; do
        [ -n "$ln" ] || continue
        f="$Z2_DIR/lists/$ln"
        [ -f "$f" ] || continue
        picked="$picked $(tr -d '\r' < "$f" | grep -E '^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' | head -n 3 | tr '\n' ' ')"
    done < "$BC_LISTS"
    DOMAINS=$(echo $picked | tr ' ' '\n' | grep -v '^$' | sort -u | head -n 12 | tr '\n' ' ')
    [ -n "$DOMAINS" ] || fail_run "the chosen target lists contain no domains"
    bc_log INFO "targets from zapret2 lists: $(echo "$DOMAINS" | tr -s ' ')"
fi

HAVE_H3=0
curl -V 2>/dev/null | grep -q HTTP3 && HAVE_H3=1

[ "${1:-}" = run ] && shift
LIMIT="${1:-15}"
case "$LIMIT" in ''|*[!0-9]*) fail_run "limit must be a number" ;; esac

WAS_ACTIVE="${ZAPRET_BC_RESTORE:-0}"

presets=""
count=0
for f in "$STRAT_DIR"/$STRAT_GLOB; do
    [ -f "$f" ] || continue
    if [ "$LIMIT" -gt 0 ] && [ "$count" -ge "$LIMIT" ]; then break; fi
    presets="$presets
$(basename "$f")"
    count=$((count + 1))
done
TOTAL=$((count + 1))
[ "$count" -gt 0 ] || fail_run "no $ENGINE_NOW strategies installed; run Update components first"

bc_log INFO "started: $count presets, domains: $DOMAINS, curl HTTP3=$HAVE_H3"
write_state running "baseline" 0 "$TOTAL" "measuring without bypass" "$WAS_ACTIVE"

sh "$CTL" stop >/dev/null 2>&1
measure
record "__baseline__" "no bypass"
bc_log INFO "baseline: score $M_SCORE/$M_MAX"

done_n=1
echo "$presets" | while IFS= read -r preset; do
    [ -n "$preset" ] || continue
    done_n=$((done_n + 1))
    write_state running "$preset" "$done_n" "$TOTAL" "testing $preset" "$WAS_ACTIVE"
    bc_log INFO "testing $preset ($done_n/$TOTAL)"
    if ! ZAPRET_OVERRIDE_STRATEGY="$preset" sh "$CTL" start >>"$LOG" 2>&1; then
        M_SCORE=0; M_MAX=0; M_HTTP=0; M_TLS12=0; M_TLS13=0; M_QUIC=0
        record "$preset" "engine did not start"
        bc_log WARN "$preset: engine did not start"
        sh "$CTL" stop >/dev/null 2>&1
        continue
    fi
    measure
    record "$preset" ""
    bc_log INFO "$preset: score $M_SCORE/$M_MAX"
    sh "$CTL" stop >/dev/null 2>&1
done

{
    grep '^__baseline__|' "$RUN_DIR/blockcheck.raw"
    grep -v '^__baseline__|' "$RUN_DIR/blockcheck.raw" | sort -t'|' -k2,2nr
} > "$BC_CSV"
rm -f "$RUN_DIR/blockcheck.raw"

if [ "$WAS_ACTIVE" = 1 ]; then
    bc_log INFO "restoring the firewall that was running before the test"
    sh "$CTL" start >>"$LOG" 2>&1
fi

best=$(grep -v '^__baseline__|' "$BC_CSV" | head -n 1 | cut -d'|' -f1)
bc_log INFO "finished in $(( $(date +%s) - STARTED )) s; best preset: ${best:-none}"
write_state done "" "$TOTAL" "$TOTAL" "finished; best: ${best:-none}" "$WAS_ACTIVE"
