#!/system/bin/sh
MODDIR="${0%/*}/.."
export MLOG_ECHO=0
. "$MODDIR/scripts/common.sh"

CTL="$MODDIR/scripts/zapret-ctl.sh"
Z1_DIR="$MODDIR/zapret1"
Z2_DIR="$MODDIR/zapret2"
DNSCRYPT_BIN="$MODDIR/bin/dnscrypt-proxy"
DOMAINS_FILE="$MODDIR/config/blockcheck-domains.txt"
BC_STATE="$RUN_DIR/blockcheck.state"
BC_RESULTS="$RUN_DIR/blockcheck.results"
BC_LOG="$RUN_DIR/blockcheck.log"
MAIN_LOG="$LOG"
TIMEOUT=5
STARTED="$(date +%s)"

mkrun
: > "$BC_LOG"
LOG="$BC_LOG"
export ZAPRET_LOG="$BC_LOG"

bc_log() { mlog blockcheck "$1" "$2"; }
main_log() {
    _keep="$LOG"
    LOG="$MAIN_LOG"
    mlog blockcheck "$1" "$2"
    LOG="$_keep"
}

ENGINE_BC=""
DNS_NOTE=""
PRE_DOH=0
PRE_DOH_CONF=0
PRE_DNS_MODE=""
PRE_DNS_HOST=""
DNS_RESTORED=0
HAVE_TLSMAX=0
HAVE_TLS13=0
HAVE_H3=0

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
        echo "dns=$DNS_NOTE"
        echo "started=$STARTED"
        echo "updated=$(date +%s)"
    } > "$BC_STATE.tmp" && mv -f "$BC_STATE.tmp" "$BC_STATE"
}

fail_run() {
    bc_log ERROR "$1"
    main_log ERROR "Blockcheck failed: $1"
    write_state error "" 0 0 "$1" 0
    exit 1
}

pick_curl() {
    CURL=$(command -v curl 2>/dev/null)
    for c in /data/data/com.termux/files/usr/bin/curl /system/xbin/curl /data/adb/ksu/bin/curl /data/adb/magisk/curl; do
        [ -x "$c" ] || continue
        if "$c" -V 2>/dev/null | grep -q HTTP3; then
            CURL="$c"
            bc_log INFO "using $c: it has HTTP/3"
            return 0
        fi
    done
    return 0
}

probe_caps() {
    "$CURL" --tls-max 1.2 --tlsv1.2 -V >/dev/null 2>&1 && HAVE_TLSMAX=1
    "$CURL" --tlsv1.3 --tls-max 1.3 -V >/dev/null 2>&1 && HAVE_TLS13=1
    if "$CURL" --http3-only -V >/dev/null 2>&1 && "$CURL" -V 2>/dev/null | grep -q HTTP3; then
        HAVE_H3=1
    fi
    return 0
}

probe() {
    k="$1"
    shift
    errf="$RAWBASE.$k"
    out=$("$CURL" -sS -k -o /dev/null -w '%{http_code} %{time_total}' --connect-timeout 3 \
        --max-time "$TIMEOUT" "$@" 2>"$errf")
    rc=$?
    code=${out%% *}
    t=${out#* }
    case "$t" in ''|*[!0-9.]*) t=0 ;; esac
    if [ -n "$code" ] && [ "$code" != "000" ]; then
        ms=$(awk "BEGIN{printf \"%d\", $t*1000}" 2>/dev/null)
        PROBE_TOK="$k:ok:${ms:-0}"
    else
        PROBE_TOK="$k:fail:$rc"
        msg=$(tail -n 1 "$errf" 2>/dev/null | tr -d '\r')
        [ -n "$msg" ] && echo "$k|$msg" >> "$ERRF"
    fi
    rm -f "$errf"
}

domain_probes() {
    d="$1"
    ERRF="$3"
    RAWBASE="$4"
    r=""
    probe h "http://$d/"
    r="$r $PROBE_TOK"
    if [ "$HAVE_TLSMAX" = 1 ]; then
        probe t2 --tlsv1.2 --tls-max 1.2 "https://$d/"
    else
        
        probe t2 --tlsv1.2 "https://$d/"
    fi
    r="$r $PROBE_TOK"
    if [ "$HAVE_TLS13" = 1 ]; then
        probe t3 --tlsv1.3 --tls-max 1.3 "https://$d/"
        r="$r $PROBE_TOK"
    fi
    if [ "$HAVE_H3" = 1 ]; then
        probe q --http3-only "https://$d/"
        r="$r $PROBE_TOK"
    fi
    echo "$d$r" > "$2"
}

measure() {
    M_HTTP=0; M_TLS12=0; M_TLS13=0; M_QUIC=0
    M_SCORE=0; M_MAX=0; M_DOM_OK=0; M_DOM_TOTAL=0; M_AVG=0
    LAT_SUM=0; LAT_N=0; RC_LOG=""; M_ERRS=""
    tmp="$RUN_DIR/bc-probe"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    n=0
    for d in $DOMAINS; do
        n=$((n + 1))
        domain_probes "$d" "$tmp/d$n" "$tmp/e$n" "$tmp/raw$n" &
    done
    wait
    for f in "$tmp"/d*; do
        [ -f "$f" ] || continue
        M_DOM_TOTAL=$((M_DOM_TOTAL + 1))
        dom_ok=0
        for tok in $(cut -d' ' -f2- "$f"); do
            case "$tok" in
                h:ok:*|t2:ok:*|t3:ok:*|q:ok:*)
                    dom_ok=1
                    M_SCORE=$((M_SCORE + 1))
                    M_MAX=$((M_MAX + 1))
                    LAT_SUM=$((LAT_SUM + ${tok##*:}))
                    LAT_N=$((LAT_N + 1))
                    case "$tok" in
                        h:*)  M_HTTP=$((M_HTTP + 1)) ;;
                        t2:*) M_TLS12=$((M_TLS12 + 1)) ;;
                        t3:*) M_TLS13=$((M_TLS13 + 1)) ;;
                        q:*)  M_QUIC=$((M_QUIC + 1)) ;;
                    esac
                    ;;
                *:fail:*)
                    M_MAX=$((M_MAX + 1))
                    RC_LOG="$RC_LOG ${tok##*:}"
                    ;;
            esac
        done
        [ "$dom_ok" = 1 ] && M_DOM_OK=$((M_DOM_OK + 1))
    done
    M_ERRS=$(cat "$tmp"/e* 2>/dev/null | sort | uniq -c | sort -rn | head -n 3 \
        | sed 's/^ *\([0-9][0-9]*\) /x\1 /; s/|/: /')
    rm -rf "$tmp"
    [ "$LAT_N" -gt 0 ] && M_AVG=$((LAT_SUM / LAT_N))
    return 0
}

measure_reset() {
    M_HTTP=0; M_TLS12=0; M_TLS13=0; M_QUIC=0
    M_SCORE=0; M_MAX=0; M_DOM_OK=0; M_DOM_TOTAL=0; M_AVG=0
    RC_LOG=""; M_ERRS=""
}

rc_summary() {
    echo "$1" | tr ' ' '\n' | grep -v '^$' | sort | uniq -c \
        | awk '{ printf "%s%s x%s", (NR > 1 ? ", " : ""), $2, $1 } END { print "" }'
}

report() {
    if [ -n "$RC_LOG" ]; then
        bc_log INFO "$1: failed probes, curl exit codes: $(rc_summary "$RC_LOG")"
    fi
    echo "$M_ERRS" | while IFS= read -r l; do
        [ -n "$l" ] && bc_log INFO "$1: curl said: $l"
    done
    return 0
}

write_results() {
    raw="$RUN_DIR/blockcheck.raw"
    {
        grep '^__baseline' "$raw" 2>/dev/null
        grep -v '^__baseline' "$raw" 2>/dev/null | sort -t'|' -k10,10nr -k2,2nr -k8,8n
    } > "$BC_RESULTS.tmp" && mv -f "$BC_RESULTS.tmp" "$BC_RESULTS"
}

record() {
    v12=$M_TLS12
    v13=$M_TLS13; [ "$HAVE_TLS13" = 1 ] || v13=n/a
    vq=$M_QUIC;   [ "$HAVE_H3" = 1 ] || vq=n/a
    echo "$1|$M_SCORE|$M_MAX|$M_HTTP|$v12|$v13|$vq|$M_AVG|$2|$M_DOM_OK|$M_DOM_TOTAL|$3" \
        >> "$RUN_DIR/blockcheck.raw"
    write_results
}

control_probe() {
    out=$("$CURL" -sS -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 \
        https://www.cloudflare.com/cdn-cgi/trace 2>&1)
    rc=$?
    if [ "$rc" = 0 ]; then
        bc_log INFO "control: https://www.cloudflare.com/ answered $out with no bypass"
    else
        bc_log WARN "control: https://www.cloudflare.com/ failed, curl exit $rc: $(echo "$out" | tail -n 1)"
    fi
}

engine_up() {
    sh "$CTL" status 2>/dev/null | grep -q '^nfqws_running=1'
}
engine_status() {
    sh "$CTL" status 2>/dev/null | grep -E '^(nfqws_running|firewall_active|active_strategy)=' | tr '\n' ' '
}

dot_provider() {
    prefs=$(sh "$CTL" dump 2>/dev/null | sed -n 's/^DOH_PROVIDERS=//p' | tr ',' ' ')
    have=$(sh "$CTL" dns-providers 2>/dev/null | awk -F'|' '$4 != "" {print $1}')
    for p in $prefs; do
        case " $(echo $have) " in *" $p "*) echo "$p"; return 0 ;; esac
    done
    for p in $have; do
        echo "$p"
        return 0
    done
    return 1
}

dns_pick_mode() {
    if [ -x "$DNSCRYPT_BIN" ] && [ -n "$(sh "$CTL" dump 2>/dev/null | sed -n 's/^DOH_PROVIDERS=//p')" ]; then
        echo doh
    elif dot_provider >/dev/null; then
        echo dot
    fi
}

dns_label() {
    case "$1" in doh) echo DoH ;; dot) echo DoT ;; *) echo none ;; esac
}

dns_on() {
    case "$1" in
        doh) sh "$CTL" doh-start >>"$LOG" 2>&1 ;;
        dot)
            id=$(dot_provider)
            [ -n "$id" ] || return 1
            sh "$CTL" dns-apply "$id" >>"$LOG" 2>&1 || return 1
            sleep 2
            ;;
        *) return 1 ;;
    esac
}

dns_off() {
    sh "$CTL" doh-stop >>"$LOG" 2>&1
    settings put global private_dns_mode off >/dev/null 2>&1
}

dns_save_state() {
    PRE_DOH=$(sh "$CTL" doh-status 2>/dev/null | sed -n 's/^doh_running=//p')
    PRE_DOH_CONF=$(sh "$CTL" dump 2>/dev/null | sed -n 's/^DOH_ENABLED=//p')
    PRE_DNS_MODE=$(settings get global private_dns_mode 2>/dev/null)
    PRE_DNS_HOST=$(settings get global private_dns_specifier 2>/dev/null)
}

APP_PREV=""
app_filter_off() {
    APP_PREV=$(sh "$CTL" dump 2>/dev/null | sed -n 's/^APP_MODE=//p')
    case "$APP_PREV" in ""|disabled) APP_PREV=""; return 0 ;; esac
    if sh "$CTL" set APP_MODE disabled >>"$LOG" 2>&1; then
        bc_log WARN "app filter was '$APP_PREV': off for this run, so Blockcheck traffic goes through the engine. Restored when the run ends."
    else
        APP_PREV=""
        bc_log WARN "could not switch the app filter off; results may not reflect the presets"
    fi
}

app_filter_restore() {
    [ -n "$APP_PREV" ] || return 0
    sh "$CTL" set APP_MODE "$APP_PREV" >>"$LOG" 2>&1 && bc_log INFO "app filter restored to '$APP_PREV'"
    APP_PREV=""
}

dns_restore() {
    [ "$DNS_RESTORED" = 1 ] && return 0
    DNS_RESTORED=1
    sh "$CTL" doh-stop >>"$LOG" 2>&1
    if [ "$PRE_DNS_MODE" = hostname ] && [ -n "$PRE_DNS_HOST" ] && [ "$PRE_DNS_HOST" != null ]; then
        settings put global private_dns_specifier "$PRE_DNS_HOST" >/dev/null 2>&1
        settings put global private_dns_mode hostname >/dev/null 2>&1
    else
        case "$PRE_DNS_MODE" in ""|null) PRE_DNS_MODE=automatic ;; esac
        settings put global private_dns_mode "$PRE_DNS_MODE" >/dev/null 2>&1
    fi
    if [ "$PRE_DOH" = 1 ]; then
        sh "$CTL" doh-start >>"$LOG" 2>&1 || bc_log WARN "could not restart the DoH proxy that was running before"
    else
        sh "$CTL" set DOH_ENABLED "${PRE_DOH_CONF:-0}" >/dev/null 2>&1
    fi
    bc_log INFO "encrypted DNS restored to the state before Blockcheck"
}

pick_curl
[ -n "$CURL" ] || fail_run "curl is not installed; install curl to use Blockcheck"
probe_caps

rm -f "$RUN_DIR/blockcheck.raw" "$BC_RESULTS"

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
fi

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
[ "$count" -gt 0 ] || fail_run "no $ENGINE_NOW strategies installed; run Update components first"

TOTAL=$((count + 2))

bc_log INFO "run started $(date '+%Y-%m-%d %H:%M:%S'), engine $ENGINE_NOW, $count presets"
bc_log INFO "domains: $DOMAINS"
bc_log INFO "curl: $("$CURL" --version 2>/dev/null | head -n 1)"
bc_log INFO "curl options: --tls-max=$HAVE_TLSMAX --tlsv1.3=$HAVE_TLS13 http3=$HAVE_H3"
[ "$HAVE_H3" = 1 ] || bc_log WARN "QUIC is not tested: no curl with HTTP/3 was found (checked the default curl, Termux, /system/xbin, KernelSU and Magisk paths). A curl build with HTTP/3 placed at one of those paths is used automatically."
bc_log INFO "probe result codes: h=HTTP, t2=TLS1.2, t3=TLS1.3, q=QUIC; a domain is available when any probe succeeds"
bc_log INFO "curl exit codes: 6 DNS, 7 refused, 28 timeout, 35 TLS handshake, 56 connection reset"
main_log INFO "Blockcheck started: $count presets, log in the Blockcheck tab"

restore_all() {
    dns_restore
    app_filter_restore
}

dns_save_state
trap restore_all EXIT
trap 'exit 130' INT TERM
app_filter_off

write_state running "baseline" 0 "$TOTAL" "measuring without bypass or encrypted DNS" "$WAS_ACTIVE"
sh "$CTL" stop >/dev/null 2>&1
dns_off
control_probe
measure
record "__baseline__" "no bypass" none
report "baseline"
PLAIN_DOM=$M_DOM_OK
PLAIN_SCORE=$M_SCORE
bc_log INFO "baseline: $M_DOM_OK/$M_DOM_TOTAL domains available, score $M_SCORE/$M_MAX"

DNS_MODE=$(dns_pick_mode)
USE_DNS=""
DNS_LABEL=$(dns_label "$DNS_MODE")
if [ -z "$DNS_MODE" ]; then
    DNS_NOTE="no DoH or DoT available; presets run on plain DNS"
else
    write_state running "baseline+dns" 1 "$TOTAL" "measuring with $DNS_LABEL, no bypass" "$WAS_ACTIVE"
    if dns_on "$DNS_MODE"; then
        measure
        record "__baseline_enc__" "no bypass, $DNS_LABEL" "$DNS_MODE"
        report "baseline + $DNS_LABEL"
        ENC_DOM=$M_DOM_OK
        ENC_SCORE=$M_SCORE
        dns_off
        bc_log INFO "baseline + $DNS_LABEL: $ENC_DOM/$M_DOM_TOTAL domains available, score $ENC_SCORE (plain DNS: $PLAIN_DOM, score $PLAIN_SCORE)"
        if [ "$ENC_DOM" -gt "$PLAIN_DOM" ] || { [ "$ENC_DOM" -eq "$PLAIN_DOM" ] && [ "$ENC_SCORE" -gt "$PLAIN_SCORE" ]; }; then
            USE_DNS="$DNS_MODE"
            DNS_NOTE="$DNS_LABEL: $ENC_DOM vs $PLAIN_DOM domains available; presets run with it"
        else
            DNS_NOTE="$DNS_LABEL: $ENC_DOM vs $PLAIN_DOM domains available; presets run without it"
        fi
    else
        dns_off
        DNS_NOTE="$DNS_LABEL did not start; presets run on plain DNS"
        bc_log WARN "$DNS_NOTE"
    fi
fi
bc_log INFO "DNS for presets: $DNS_NOTE"

if [ -n "$USE_DNS" ] && ! dns_on "$USE_DNS"; then
    bc_log WARN "could not turn $DNS_LABEL back on; presets run on plain DNS"
    DNS_NOTE="$DNS_LABEL did not restart; presets run on plain DNS"
    USE_DNS=""
fi
RUN_DNS="${USE_DNS:-none}"

done_n=2
echo "$presets" | while IFS= read -r preset; do
    [ -n "$preset" ] || continue
    done_n=$((done_n + 1))
    write_state running "$preset" "$done_n" "$TOTAL" "testing $preset" "$WAS_ACTIVE"
    bc_log INFO "--- $preset ($done_n/$TOTAL) dns=$RUN_DNS"
    if ! ZAPRET_OVERRIDE_STRATEGY="$preset" sh "$CTL" start >>"$LOG" 2>&1 || ! engine_up; then
        bc_log WARN "$preset: engine did not come up"
        measure_reset
        record "$preset" "engine did not start" "$RUN_DNS"
        sh "$CTL" stop >/dev/null 2>&1
        continue
    fi
    bc_log INFO "$preset: $(engine_status)"
    measure
    record "$preset" "" "$RUN_DNS"
    report "$preset"
    bc_log INFO "$preset: $M_DOM_OK/$M_DOM_TOTAL domains available, score $M_SCORE/$M_MAX"
    sh "$CTL" stop >/dev/null 2>&1
done

sh "$CTL" stop >/dev/null 2>&1
restore_all

if [ "$WAS_ACTIVE" = 1 ]; then
    bc_log INFO "restoring the firewall that was running before the test"
    sh "$CTL" start >>"$LOG" 2>&1
fi

best=$(grep -v '^__baseline' "$BC_RESULTS" 2>/dev/null | head -n 1 | cut -d'|' -f1)
bc_log INFO "results (name|score|max|http|tls12|tls13|quic|avg_ms|note|dom_ok|dom_total|dns):"
while IFS= read -r l; do bc_log INFO "  $l"; done < "$BC_RESULTS"
bc_log INFO "finished in $(( $(date +%s) - STARTED )) s; best preset: ${best:-none}"
write_state done "" "$TOTAL" "$TOTAL" "finished; best: ${best:-none}" "$WAS_ACTIVE"
main_log INFO "Blockcheck finished; best preset: ${best:-none}"
