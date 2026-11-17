#!/system/bin/sh

MODDIR="${0%/*}/.."
. "$MODDIR/scripts/common.sh"

YT_FILE="$MODDIR/config/youtube-ips.txt"
TETHER_STATE="$RUN_DIR/tether.state"
TETHER_WATCH_PID="$RUN_DIR/tether-watch.pid"
TETHER_NFT="$RUN_DIR/tether.nft"
QUIC_STATE="$RUN_DIR/quic.state"
QUIC_NFT="$RUN_DIR/quic.nft"
NFT_T=zapret_tether
NFT_Q=zapret_quic
IPT_CH=ZAPRET_TETHER
IPT_QCH=ZAPRET_QUIC
IPT_WAIT="-w 5"
RT_PREF=3000
TC_BIN="${ZAPRET_TC:-/system/bin/tc}"
OFFLOAD_SAVE="$RUN_DIR/offload.saved"
FWD_SYS="${ZAPRET_FWD_SYS:-/proc/sys/net/ipv4/ip_forward}"
NET_SYS="${ZAPRET_NET_SYS:-/sys/class/net}"
PKG_LIST="${ZAPRET_PKG_LIST:-/data/system/packages.list}"

info() { mlog tether INFO "$*"; }
warn() { mlog tether WARN "$*"; }
err()  { mlog tether ERROR "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

load() {
    ENABLED=$(conf_get TETHER_ENABLED); [ "$ENABLED" = 1 ] || ENABLED=0
    MODE=$(conf_get TETHER_MODE); [ "$MODE" = vpn ] || MODE=direct
    IFACES=$(conf_get TETHER_IFACES); [ -n "$IFACES" ] || IFACES="ap0,swlan0,wlan1,rndis0,usb0,ncm0"
    IFACES=$(printf '%s\n%s\n' "$(iface_lines "$IFACES")" "$(auto_ifaces)" | awk 'NF && !seen[$0]++' | paste -sd, -)
    VPN_IF=$(conf_get TETHER_VPN_IFACE); [ -n "$VPN_IF" ] || VPN_IF="tun+"
    KS=$(conf_get TETHER_KILLSWITCH); [ "$KS" = 0 ] || KS=1
    NOPROV=$(conf_get TETHER_NOPROV); [ "$NOPROV" = 1 ] || NOPROV=0
    QUIC_YT=$(conf_get TETHER_QUIC_YT); [ "$QUIC_YT" = 1 ] || QUIC_YT=0
    OFFLOAD_OFF=$(conf_get TETHER_OFFLOAD_OFF); [ "$OFFLOAD_OFF" = 1 ] || OFFLOAD_OFF=0
    TTL=$(conf_get TETHER_TTL)
    case "$TTL" in ''|*[!0-9]*) TTL=0 ;; esac
    [ "$TTL" -le 255 ] || TTL=0
    APP_QUIC=$(conf_get APP_QUIC_LIST)
}

nft_ok() { have nft && nft list tables >/dev/null 2>&1; }

pick_iptables() {
    for b in iptables-legacy iptables; do
        if have "$b"; then echo "$b"; return 0; fi
    done
    return 1
}

nft_list() {
    echo "$1" | awk -F, '{
        for (i = 1; i <= NF; i++) {
            n = $i; gsub(/ /, "", n)
            if (n == "") continue
            sub(/\+$/, "*", n)
            s = s (s == "" ? "" : ", ") "\"" n "\""
        }
        print s
    }'
}

iface_lines() { echo "$1" | tr ',' '\n' | tr -d ' ' | grep -v '^$'; }

auto_ifaces() {
    for n in $(ls "$NET_SYS" 2>/dev/null); do
        case "$n" in
            ap[0-9]*|swlan[0-9]*|wlan[1-9]*|rndis[0-9]*|usb[0-9]*|ncm[0-9]*) echo "$n" ;;
        esac
    done
}

nft_name() { echo "$1" | sed 's/+$/*/'; }

vpn_re() { nft_name "$VPN_IF" | sed 's/\*/.*/g'; }

vpn_table() {
    re="^$(vpn_re)\$"
    ip -4 route show table all 2>/dev/null | awk -v re="$re" '
        {
            dev = ""; tbl = "main"
            for (i = 2; i < NF; i++) {
                if ($i == "dev") dev = $(i + 1)
                if ($i == "table") tbl = $(i + 1)
            }
            if (dev !~ re) next
            if ($1 == "default") { print tbl; exit }
            bits = 32
            if (index($1, "/") > 0) { split($1, a, "/"); bits = a[2] + 0 }
            cov[tbl] += 2 ^ (32 - bits)
        }
        END {
            for (t in cov) if (cov[t] >= 2 ^ 31) { print t; exit }
        }'
}

vpn_iface() {
    re="^$(vpn_re)\$"
    for d in $(ls "$NET_SYS" 2>/dev/null); do
        if echo "$d" | grep -qE "$re"; then echo "$d"; return 0; fi
    done
    return 1
}

yt_entries() {
    [ -f "$YT_FILE" ] || return 0
    tr -d '\r' < "$YT_FILE" | grep -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$'
}

noprov_apply() {
    if [ "$NOPROV" = 1 ]; then
        if have resetprop; then resetprop -n net.tethering.noprovisioning true
        else setprop net.tethering.noprovisioning true; fi
    else
        if have resetprop; then resetprop -d net.tethering.noprovisioning 2>/dev/null
        else setprop net.tethering.noprovisioning false; fi
    fi
}

set_forward() {
    echo "$1" > "$FWD_SYS" 2>/dev/null || warn "could not set ip_forward to $1"
}

tether_compute() {
    FULL=0
    VT=""
    if [ "$MODE" = vpn ]; then
        VT=$(vpn_table)
        if [ -n "$VT" ]; then FULL=1; fi
    fi
    QUIC_EFF=0
    YT_N=$(yt_entries | wc -l | tr -d ' ')
    if [ "$QUIC_YT" = 1 ] && [ "$YT_N" -gt 0 ]; then QUIC_EFF=1; fi
}

tether_line() {
    echo "mode=$MODE full=$FULL ks=$KS ttl=$TTL quic=$QUIC_EFF yt=$YT_N ifaces=$IFACES vpn=$VPN_IF table=$VT"
}

nft_tether() {
    full=$1
    NI=$(nft_list "$IFACES")
    [ -n "$NI" ] || return 1
    NV=$(nft_name "$VPN_IF")
    {
        echo "table inet $NFT_T {"
        if [ "$QUIC_EFF" = 1 ]; then
            echo "  set yt4 {"
            echo "    type ipv4_addr"
            echo "    flags interval"
            echo "    elements = { $(yt_entries | awk 'NR > 1 { printf ", " } { printf "%s", $0 }') }"
            echo "  }"
        fi
        echo "  chain zapret_fwd {"
        echo "    type filter hook forward priority -10; policy accept;"
        if [ "$TTL" -gt 0 ]; then
            echo "    iifname { $NI } ip ttl set $TTL"
            echo "    iifname { $NI } ip6 hoplimit set $TTL"
        fi
        if [ "$QUIC_EFF" = 1 ]; then
            echo "    iifname { $NI } udp dport 443 ip daddr @yt4 reject"
        fi
        if [ "$MODE" = vpn ]; then
            if [ "$full" = 1 ]; then
                echo "    iifname { $NI } oifname \"$NV\" accept"
            fi
            if [ "$KS" = 1 ]; then
                echo "    iifname { $NI } drop"
            fi
        fi
        echo "  }"
        if [ "$MODE" = vpn ] && [ "$full" = 1 ]; then
            echo "  chain zapret_nat {"
            echo "    type nat hook postrouting priority srcnat; policy accept;"
            echo "    iifname { $NI } oifname \"$NV\" masquerade"
            echo "  }"
        fi
        echo "}"
    } > "$TETHER_NFT"
}

ipt_tether_clear() {
    B=$1
    while $B $IPT_WAIT -t filter -D FORWARD -j "$IPT_CH" 2>/dev/null; do :; done
    while $B $IPT_WAIT -t mangle -D FORWARD -j "$IPT_CH" 2>/dev/null; do :; done
    while $B $IPT_WAIT -t nat -D POSTROUTING -j "$IPT_CH" 2>/dev/null; do :; done
    for t in filter mangle nat; do
        $B $IPT_WAIT -t $t -F "$IPT_CH" 2>/dev/null
        $B $IPT_WAIT -t $t -X "$IPT_CH" 2>/dev/null
    done
    return 0
}

ipt_tether_set() {
    B=$1
    full=$2
    ipt_tether_clear "$B"
    VPN_IPT=$(echo "$VPN_IF" | sed 's/\*/+/')
    $B $IPT_WAIT -t filter -N "$IPT_CH" 2>/dev/null
    $B $IPT_WAIT -t filter -I FORWARD 1 -j "$IPT_CH" || return 1
    TTL_OK=0
    if [ "$TTL" -gt 0 ]; then
        $B $IPT_WAIT -t mangle -N "$IPT_CH" 2>/dev/null
        if $B $IPT_WAIT -t mangle -I FORWARD 1 -j "$IPT_CH" 2>/dev/null; then
            TTL_OK=1
        else
            warn "iptables mangle table is unavailable; TTL rules skipped"
        fi
    fi
    if [ "$MODE" = vpn ] && [ "$full" = 1 ]; then
        $B $IPT_WAIT -t nat -N "$IPT_CH" 2>/dev/null
        $B $IPT_WAIT -t nat -I POSTROUTING 1 -j "$IPT_CH" || return 1
    fi
    for IF in $(iface_lines "$IFACES"); do
        if [ "$TTL_OK" = 1 ]; then
            if ! $B $IPT_WAIT -t mangle -A "$IPT_CH" -i "$IF" -j TTL --ttl-set "$TTL" 2>/dev/null; then
                warn "kernel has no TTL target (xt_TTL); TTL rules skipped"
                TTL_OK=0
            fi
        fi
        if [ "$QUIC_EFF" = 1 ]; then
            for c in $(yt_entries); do
                $B $IPT_WAIT -t filter -A "$IPT_CH" -i "$IF" -p udp --dport 443 -d "$c" -j REJECT || return 1
            done
        fi
        if [ "$MODE" = vpn ]; then
            if [ "$full" = 1 ]; then
                $B $IPT_WAIT -t filter -A "$IPT_CH" -i "$IF" -o "$VPN_IPT" -j ACCEPT || return 1
                $B $IPT_WAIT -t nat -A "$IPT_CH" -i "$IF" -o "$VPN_IPT" -j MASQUERADE || return 1
            fi
            if [ "$KS" = 1 ]; then
                $B $IPT_WAIT -t filter -A "$IPT_CH" -i "$IF" -j DROP || return 1
            fi
        fi
    done
    return 0
}

ipt6_tether_set() {
    have ip6tables || return 0
    ipt_tether_clear ip6tables
    if [ "$MODE" != vpn ] || [ "$KS" != 1 ]; then return 0; fi
    VPN_IPT=$(echo "$VPN_IF" | sed 's/\*/+/')
    ip6tables $IPT_WAIT -t filter -N "$IPT_CH" 2>/dev/null
    ip6tables $IPT_WAIT -t filter -I FORWARD 1 -j "$IPT_CH" || return 1
    for IF in $(iface_lines "$IFACES"); do
        if [ "$1" = 1 ]; then
            ip6tables $IPT_WAIT -t filter -A "$IPT_CH" -i "$IF" -o "$VPN_IPT" -j ACCEPT || return 1
        fi
        ip6tables $IPT_WAIT -t filter -A "$IPT_CH" -i "$IF" -j DROP || return 1
    done
    return 0
}

nft_try() {
    full=$1
    t_save=$TTL
    q_save=$QUIC_EFF
    [ "$2" = 1 ] || TTL=0
    [ "$3" = 1 ] || QUIC_EFF=0
    nft delete table inet "$NFT_T" >/dev/null 2>&1
    nft_tether "$full" && nft -f "$TETHER_NFT" >>"$LOG" 2>&1
    rc=$?
    TTL=$t_save
    QUIC_EFF=$q_save
    return $rc
}

install_tether() {
    full=$1
    if nft_ok; then
        for combo in "1 1" "0 1" "1 0" "0 0"; do
            ttl=${combo%% *}
            quic=${combo##* }
            if nft_try "$full" "$ttl" "$quic"; then
                [ "$ttl" = 0 ] && [ "$TTL" -gt 0 ] && warn "nftables refused the TTL rules; TTL skipped"
                [ "$quic" = 0 ] && [ "$QUIC_EFF" = 1 ] && warn "nftables refused the QUIC rules; QUIC block skipped"
                return 0
            fi
        done
        warn "nftables tethering rules failed; trying iptables"
    fi
    B=$(pick_iptables) || { err "neither nftables nor iptables is usable for tethering"; return 1; }
    ipt_tether_set "$B" "$full" && ipt6_tether_set "$full"
}

route_clear() {
    n=0
    while [ "$n" -lt 16 ] && ip -4 rule del pref "$RT_PREF" 2>/dev/null; do n=$((n + 1)); done
    return 0
}

route_set() {
    route_clear
    for IF in $(iface_lines "$IFACES"); do
        ip -4 rule add iif "$IF" lookup "$1" pref "$RT_PREF" 2>/dev/null || warn "policy route for $IF was not added"
    done
}

tether_installed() {
    if nft_ok && nft list table inet "$NFT_T" >/dev/null 2>&1; then return 0; fi
    B=$(pick_iptables) || return 1
    $B $IPT_WAIT -t filter -S "$IPT_CH" >/dev/null 2>&1
}

remove_tether_rules() {
    nft delete table inet "$NFT_T" >/dev/null 2>&1
    for b in iptables-legacy iptables ip6tables; do
        have "$b" && ipt_tether_clear "$b"
    done
    route_clear
    rm -f "$TETHER_STATE"
    set_forward 1
    return 0
}

tether_apply() {
    load
    if [ "$ENABLED" != 1 ]; then
        remove_tether_rules
        return 0
    fi
    tether_compute
    want=$(tether_line)
    current=$(sed -n 1p "$TETHER_STATE" 2>/dev/null)
    rules=$(sed -n 2p "$TETHER_STATE" 2>/dev/null | sed 's/^rules=//')
    if [ "$want" = "$current" ] && { [ "$rules" = none ] || tether_installed; }; then
        return 0
    fi

    bpf_detach
    if [ "$MODE" != vpn ]; then
        INSTALLED=direct
        install_tether 0 || INSTALLED=none
    else
        INSTALLED=none
        if install_tether "$FULL"; then
            if [ "$FULL" = 1 ]; then INSTALLED=full; else INSTALLED=block; fi
        elif [ "$FULL" = 1 ]; then
            warn "VPN route is not usable; tethered clients are blocked until it is"
            if install_tether 0; then INSTALLED=block; fi
        fi
    fi

    if [ "$INSTALLED" = full ]; then
        route_set "$VT"
    else
        route_clear
    fi
    if [ "$MODE" = vpn ] && [ "$KS" = 1 ] && [ "$INSTALLED" != full ]; then
        set_forward 0
    else
        set_forward 1
    fi

    mkrun
    printf '%s\nrules=%s\n' "$want" "$INSTALLED" > "$TETHER_STATE"
    if [ "$INSTALLED" = none ]; then
        err "tethering rules could not be installed; forwarding is off"
        return 1
    fi
    info "tethering rules: $INSTALLED ($want)"
    return 0
}

quic_uids() {
    for entry in $(echo "$APP_QUIC" | tr ',' ' '); do
        case "$entry" in
            *[!0-9]*) u=$(lookup_uid "$entry") ;;
            *)        u="$entry" ;;
        esac
        [ -n "$u" ] && echo "$u"
    done | sort -un
}

lookup_uid() {
    u=$(awk -v p="$1" '$1==p {print $2; exit}' "$PKG_LIST" 2>/dev/null)
    if [ -z "$u" ] && have pm; then
        u=$(pm list packages -U "$1" 2>/dev/null | sed -n "s/^package:$1 uid:\([0-9]*\).*/\1/p" | head -n 1)
    fi
    echo "$u"
}

quic_installed() {
    if nft_ok && nft list table inet "$NFT_Q" >/dev/null 2>&1; then return 0; fi
    B=$(pick_iptables) || return 1
    $B $IPT_WAIT -t filter -S "$IPT_QCH" >/dev/null 2>&1
}

quic_remove() {
    nft delete table inet "$NFT_Q" >/dev/null 2>&1
    for b in iptables-legacy iptables ip6tables; do
        have "$b" || continue
        while $b $IPT_WAIT -t filter -D OUTPUT -j "$IPT_QCH" 2>/dev/null; do :; done
        $b $IPT_WAIT -t filter -F "$IPT_QCH" 2>/dev/null
        $b $IPT_WAIT -t filter -X "$IPT_QCH" 2>/dev/null
    done
    rm -f "$QUIC_STATE"
    return 0
}

quic_apply() {
    load
    ids=$(quic_uids)
    if [ -z "$ids" ]; then
        if [ -f "$QUIC_STATE" ] || quic_installed; then quic_remove; fi
        return 0
    fi
    want=$(echo "$ids" | tr '\n' ' ')
    if [ "$want" = "$(cat "$QUIC_STATE" 2>/dev/null)" ] && quic_installed; then
        return 0
    fi
    quic_remove
    if nft_ok; then
        UIDSET=$(echo "$ids" | awk 'NR > 1 { printf ", " } { printf "%s", $0 }')
        {
            echo "table inet $NFT_Q {"
            echo "  chain zapret_out {"
            echo "    type filter hook output priority -10; policy accept;"
            echo "    meta skuid { $UIDSET } udp dport 443 reject"
            echo "  }"
            echo "}"
        } > "$QUIC_NFT"
        if nft -f "$QUIC_NFT" >>"$LOG" 2>&1; then
            mkrun
            echo "$want" > "$QUIC_STATE"
            info "QUIC blocked for uids: $want"
            return 0
        fi
        warn "nftables QUIC block failed; trying iptables"
    fi
    B4=$(pick_iptables) || { err "QUIC block: neither nftables nor iptables is usable"; return 1; }
    ok=1
    for B in "$B4" ip6tables; do
        have "$B" || continue
        $B $IPT_WAIT -t filter -N "$IPT_QCH" 2>/dev/null
        $B $IPT_WAIT -t filter -F "$IPT_QCH" 2>/dev/null
        $B $IPT_WAIT -t filter -C OUTPUT -j "$IPT_QCH" 2>/dev/null || $B $IPT_WAIT -t filter -I OUTPUT 1 -j "$IPT_QCH"
        for u in $ids; do
            $B $IPT_WAIT -t filter -A "$IPT_QCH" -m owner --uid-owner "$u" -p udp --dport 443 -j REJECT || ok=0
        done
    done
    if [ "$ok" = 1 ]; then
        mkrun
        echo "$want" > "$QUIC_STATE"
        info "QUIC blocked for uids: $want (iptables)"
        return 0
    fi
    err "QUIC block could not be installed"
    return 1
}

ensure_watch() {
    if [ -f "$TETHER_WATCH_PID" ] && kill -0 "$(cat "$TETHER_WATCH_PID")" 2>/dev/null; then
        return 0
    fi
    nohup sh "$MODDIR/scripts/tether.sh" watch >/dev/null 2>&1 </dev/null &
}

stop_watch() {
    if [ -f "$TETHER_WATCH_PID" ]; then
        kill "$(cat "$TETHER_WATCH_PID")" 2>/dev/null
        rm -f "$TETHER_WATCH_PID"
    fi
}

cmd_watch() {
    trap '' HUP
    mkrun
    echo $$ > "$TETHER_WATCH_PID"
    while :; do
        load
        if [ "$ENABLED" != 1 ] && [ -z "$APP_QUIC" ]; then break; fi
        if [ "$ENABLED" = 1 ]; then bpf_detach >/dev/null 2>&1; tether_apply >/dev/null 2>&1; fi
        quic_apply >/dev/null 2>&1
        if [ "$ENABLED" = 1 ] && [ "$MODE" = vpn ] && [ "$KS" = 1 ]; then sleep 1; else sleep 5; fi
    done
    rm -f "$TETHER_WATCH_PID"
}

bpf_detach() {
    [ "$MODE" = vpn ] || return 0
    [ -x "$TC_BIN" ] || return 0
    for d in $(ls "$NET_SYS" 2>/dev/null); do
        out=$("$TC_BIN" filter show dev "$d" ingress 2>/dev/null)
        case "$out" in *schedcls_tether_*) ;; *) continue ;; esac
        printf '%s\n' "$out" | awk '/schedcls_tether_/ {
                p = ""; r = ""
                for (i = 1; i < NF; i++) { if ($i == "protocol") p = $(i + 1); if ($i == "pref") r = $(i + 1) }
                if (p != "" && r != "") print p, r }' | sort -u | while read -r p r; do
            if "$TC_BIN" filter del dev "$d" ingress protocol "$p" pref "$r" 2>/dev/null; then
                info "BPF offload filter removed from $d (traffic must go through netfilter)"
            else
                warn "could not remove BPF offload filter from $d"
            fi
        done
    done
}

offload_apply() {
    if [ "$OFFLOAD_OFF" = 1 ]; then
        if [ ! -f "$OFFLOAD_SAVE" ]; then
            mkrun
            printf 'setting=%s\nbpf=%s\n' \
                "$(settings get global tether_offload_disabled 2>/dev/null)" \
                "$(device_config get tethering override_bpf_offload 2>/dev/null)" > "$OFFLOAD_SAVE"
        fi
        have settings && settings put global tether_offload_disabled 1 2>/dev/null
        have device_config && device_config put tethering override_bpf_offload false 2>/dev/null
    elif [ -f "$OFFLOAD_SAVE" ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            key="${line%%=*}"
            val="${line#*=}"
            case "$key" in
                setting)
                    case "$val" in
                        ""|null) settings delete global tether_offload_disabled 2>/dev/null ;;
                        *)       settings put global tether_offload_disabled "$val" 2>/dev/null ;;
                    esac ;;
                bpf)
                    case "$val" in
                        ""|null) device_config delete tethering override_bpf_offload 2>/dev/null ;;
                        *)       device_config put tethering override_bpf_offload "$val" 2>/dev/null ;;
                    esac ;;
            esac
        done < "$OFFLOAD_SAVE"
        rm -f "$OFFLOAD_SAVE"
    fi
}

cmd_apply() {
    load
    noprov_apply
    offload_apply
    rc=0
    bpf_detach
    tether_apply || rc=1
    quic_apply || rc=1
    if [ "$ENABLED" = 1 ] || [ -n "$APP_QUIC" ]; then ensure_watch; else stop_watch; fi
    return $rc
}

cmd_status() {
    load
    W=dead
    if [ -f "$TETHER_WATCH_PID" ] && kill -0 "$(cat "$TETHER_WATCH_PID")" 2>/dev/null; then W=alive; fi
    VT=$(vpn_table)
    RULES=$(sed -n 2p "$TETHER_STATE" 2>/dev/null | sed 's/^rules=//')
    [ -n "$RULES" ] || RULES=none
    if [ "$RULES" != none ] && ! tether_installed; then RULES=missing; fi
    echo "tether_enabled=$ENABLED"
    echo "tether_mode=$MODE"
    echo "tether_ifaces=$IFACES"
    echo "tether_vpn_iface=$VPN_IF"
    echo "tether_vpn_table=${VT:-none}"
    echo "tether_killswitch=$KS"
    echo "tether_rules=$RULES"
    echo "tether_watch=$W"
    echo "tether_ip_forward=$(cat "$FWD_SYS" 2>/dev/null)"
    echo "tether_noprovisioning=$(getprop net.tethering.noprovisioning 2>/dev/null)"
    echo "tether_offload_off=$OFFLOAD_OFF"
    echo "tether_offload_setting=$(settings get global tether_offload_disabled 2>/dev/null)"
    echo "tether_ttl=$TTL"
    echo "tether_quic_yt=$QUIC_YT"
    echo "tether_yt_ranges=$(yt_entries | wc -l | tr -d ' ')"
    echo "app_quic_uids=$(quic_uids | wc -l | tr -d ' ')"
    if quic_installed; then echo "app_quic_rules=1"; else echo "app_quic_rules=0"; fi
    return 0
}

cmd_interfaces() {
    for d in "$NET_SYS"/*; do
        n=${d##*/}
        [ "$n" = lo ] || echo "$n"
    done
}

cmd_youtube_refresh() {
    url="https://www.gstatic.com/ipranges/goog.json"
    tmp="$RUN_DIR/goog.json.tmp"
    mkrun
    if have curl; then
        curl -fsSL --connect-timeout 8 --max-time 60 "$url" -o "$tmp" 2>/dev/null
    elif [ -n "$BB" ]; then
        "$BB" wget -q -T 30 -O "$tmp" "$url" 2>/dev/null
    else
        err "neither curl nor busybox wget is available"
        return 1
    fi
    grep -o '"ipv4Prefix": *"[^"]*"' "$tmp" 2>/dev/null \
        | sed 's/.*"\([^"]*\)"$/\1/' \
        | grep -E '^[0-9.]+/[0-9]+$' > "$YT_FILE.new"
    rm -f "$tmp"
    n=$(wc -l < "$YT_FILE.new" 2>/dev/null | tr -d ' ')
    if [ "${n:-0}" -gt 0 ]; then
        cat "$YT_FILE.new" > "$YT_FILE"
        rm -f "$YT_FILE.new"
        info "Google ranges refreshed: $n"
        echo "ok ranges=$n"
        return 0
    fi
    rm -f "$YT_FILE.new"
    err "could not download the Google ranges"
    return 1
}

nft_probe() {
    nft_ok || return 1
    {
        echo "table inet zapret_probe {"
        echo "  chain $1 {"
        echo "    type filter hook $2 priority -10; policy accept;"
        echo "    $3"
        echo "  }"
        echo "}"
    } > "$RUN_DIR/diag.nft"
    nft -c -f "$RUN_DIR/diag.nft" >/dev/null 2>&1
}

nft_nat_probe() {
    nft_ok || return 1
    {
        echo "table inet zapret_probe {"
        echo "  chain p {"
        echo "    type nat hook postrouting priority srcnat; policy accept;"
        echo "    oifname \"tun*\" masquerade"
        echo "  }"
        echo "}"
    } > "$RUN_DIR/diag.nft"
    nft -c -f "$RUN_DIR/diag.nft" >/dev/null 2>&1
}

ipt_probe() {
    t=$1
    shift
    $IPT $IPT_WAIT -t "$t" -N ZAPRET_DIAG 2>/dev/null
    $IPT $IPT_WAIT -t "$t" -F ZAPRET_DIAG 2>/dev/null
    if $IPT $IPT_WAIT -t "$t" -A ZAPRET_DIAG "$@" 2>/dev/null; then r=0; else r=1; fi
    $IPT $IPT_WAIT -t "$t" -F ZAPRET_DIAG 2>/dev/null
    $IPT $IPT_WAIT -t "$t" -X ZAPRET_DIAG 2>/dev/null
    return $r
}

diag_line() {
    echo "$1|$2|$3"
}

cmd_diagnose() {
    load
    IPT=$(pick_iptables 2>/dev/null)
    if nft_ok; then NFT_OK=1; else NFT_OK=0; fi
    [ -n "$IPT" ] && IPT_OK=1 || IPT_OK=0

    ttl_ok=0
    if [ "$TTL" -gt 0 ]; then
        if nft_probe zapret_fwd forward "iifname \"ap0\" ip ttl set $TTL" || \
           { [ "$IPT_OK" = 1 ] && ipt_probe mangle -j TTL --ttl-set "$TTL"; }; then ttl_ok=1; fi
    fi
    nat_ok=0
    if nft_nat_probe || { [ "$IPT_OK" = 1 ] && ipt_probe nat -o tun+ -j MASQUERADE; }; then nat_ok=1; fi
    reject_ok=0
    if nft_probe zapret_fwd forward "iifname \"ap0\" udp dport 443 reject" || \
       { [ "$IPT_OK" = 1 ] && ipt_probe filter -p udp --dport 443 -j REJECT; }; then reject_ok=1; fi
    drop_ok=0
    if nft_probe zapret_fwd forward "iifname \"ap0\" drop" || \
       { [ "$IPT_OK" = 1 ] && ipt_probe filter -j DROP; }; then drop_ok=1; fi
    skuid_ok=0
    if nft_probe zapret_out output "meta skuid 10123 udp dport 443 reject" || \
       { [ "$IPT_OK" = 1 ] && ipt_probe filter -m owner --uid-owner 10123 -p udp --dport 443 -j REJECT; }; then skuid_ok=1; fi

    if [ "$NFT_OK" = 1 ] || [ "$IPT_OK" = 1 ]; then
        diag_line tethering ok "Clients are forwarded (nftables: $NFT_OK, iptables: $IPT_OK)"
    else
        diag_line tethering fail "Neither nftables nor iptables is usable; clients cannot be forwarded"
    fi
    if [ -w "$FWD_SYS" ]; then
        diag_line forwarding ok "IP forwarding can be switched"
    else
        diag_line forwarding fail "$FWD_SYS is not writable; the kill-switch cannot stop clients"
    fi

    VT=$(vpn_table)
    if [ "$MODE" = vpn ]; then
        if [ -n "$VT" ]; then
            diag_line vpn-routing ok "VPN route found (table $VT)"
        else
            diag_line vpn-routing warn "No default route through $VPN_IF: the tunnel is down or the interface name differs. Clients are blocked while the kill-switch is on"
        fi
        if [ "$nat_ok" = 1 ]; then
            diag_line vpn-nat ok "NAT into the tunnel is supported"
        else
            diag_line vpn-nat fail "NAT is not supported by the kernel: clients cannot reach the internet through the VPN"
        fi
        if [ "$KS" = 1 ]; then
            if [ "$drop_ok" = 1 ]; then
                ks_if=""
                for IF in $(iface_lines "$IFACES"); do
                    if [ -e "$NET_SYS/$IF" ]; then ks_if="$ks_if $IF"; fi
                done
                if [ -n "$ks_if" ]; then
                    diag_line kill-switch ok "Clients on:$ks_if are dropped when the tunnel is down"
                else
                    diag_line kill-switch warn "No tethering interface is up yet; the kill-switch applies as soon as one comes up"
                fi
            else
                diag_line kill-switch fail "Dropping client traffic is not supported; the kill-switch cannot work"
            fi
        else
            diag_line kill-switch info "Kill-switch is off: clients leave directly if the tunnel is down"
        fi
        vpn_present=0
        re=$(vpn_re)
        for d in "$NET_SYS"/*; do
            n=${d##*/}
            if echo "$n" | grep -qE "^$re\$"; then vpn_present=1; fi
        done
        if [ "$vpn_present" = 1 ]; then
            diag_line vpn-interface ok "An interface matches $VPN_IF"
        else
            diag_line vpn-interface warn "No interface matches $VPN_IF; start the VPN or change the VPN interface"
        fi
    else
        diag_line vpn-routing info "Direct mode: clients leave through the upstream network"
    fi

    if [ "$TTL" -gt 0 ]; then
        if [ "$ttl_ok" = 1 ]; then
            diag_line ttl ok "TTL $TTL will be applied to client traffic"
        else
            diag_line ttl warn "TTL $TTL will be skipped: the kernel has no TTL support (xt_TTL / nft ip ttl)"
        fi
    else
        diag_line ttl info "TTL is off"
    fi

    if [ "$QUIC_YT" = 1 ]; then
        yt_n=$(yt_entries | wc -l | tr -d ' ')
        if [ "$yt_n" -eq 0 ]; then
            diag_line quic-google fail "Google ranges are not downloaded: press Refresh Google ranges"
        elif [ "$reject_ok" = 1 ]; then
            diag_line quic-google ok "UDP 443 to $yt_n Google ranges will be refused for clients"
        else
            diag_line quic-google fail "The kernel cannot refuse UDP: the QUIC block will be skipped"
        fi
    else
        diag_line quic-google info "QUIC block for clients is off"
    fi

    off_now=$(settings get global tether_offload_disabled 2>/dev/null)
    bpf_now=$(device_config get tethering override_bpf_offload 2>/dev/null)
    [ "$off_now" = null ] && off_now=""
    [ "$bpf_now" = null ] && bpf_now=""
    if [ "$OFFLOAD_OFF" = 1 ]; then
        if [ "$off_now" = 1 ] && [ "$bpf_now" = false ]; then
            diag_line offload ok "Hardware and BPF offload are disabled. Restart the hotspot if clients were connected before"
        else
            diag_line offload warn "Offload disable is set, but the system reports hardware=${off_now:-default}, BPF=${bpf_now:-default}: restart the hotspot"
        fi
    else
        diag_line offload info "Offload is left to the system (hardware=${off_now:-default}, BPF=${bpf_now:-default})"
    fi

    vpn_if_name=$(vpn_iface)
    first_if=""
    for IF in $(iface_lines "$IFACES"); do
        if [ -e "$NET_SYS/$IF" ]; then first_if=${first_if:-$IF}; fi
    done
    if [ "$MODE" = vpn ]; then
        if [ ! -x "$TC_BIN" ]; then
            diag_line bpf-offload fail "$TC_BIN is missing: BPF offload cannot be detached and bypasses the VPN"
        else
            bpf_n=0
            for IF in $(iface_lines "$IFACES"); do
                [ -e "$NET_SYS/$IF" ] || continue
                if "$TC_BIN" filter show dev "$IF" ingress 2>/dev/null | grep -q schedcls_tether_; then bpf_n=$((bpf_n + 1)); fi
            done
            if [ "$bpf_n" -gt 0 ]; then
                diag_line bpf-offload warn "BPF offload is attached to $bpf_n tethering interface(s). The watcher removes it, and until then it bypasses netfilter"
            else
                diag_line bpf-offload ok "No BPF offload filters on tethering interfaces"
            fi
        fi

        if [ -z "$first_if" ]; then
            diag_line ip-rule info "No tethering interface is up: policy routes are checked when one comes up"
        elif [ -z "$VT" ]; then
            diag_line ip-rule info "No VPN table found, so no policy route is expected"
        else
            ip4_rules=$(ip -4 rule show 2>/dev/null)
            rules_missing=""
            conflict=""
            for IF in $(iface_lines "$IFACES"); do
                [ -e "$NET_SYS/$IF" ] || continue
                echo "$ip4_rules" | grep -q "iif $IF lookup $VT" || rules_missing="$rules_missing $IF"
                c=$(echo "$ip4_rules" | awk -F: -v i="$IF" '$1 + 0 < 3000 && index($0, "iif " i " ") { print; exit }')
                [ -n "$c" ] && conflict="$conflict [$c]"
            done
            if [ -n "$rules_missing" ]; then
                diag_line ip-rule fail "No policy route to table $VT for:$rules_missing"
            elif [ -n "$conflict" ]; then
                diag_line ip-rule warn "An earlier rule catches tethered traffic:$conflict"
            else
                diag_line ip-rule ok "Tethered traffic is routed into table $VT"
            fi
        fi

        if [ -n "$first_if" ] && [ -n "$VT" ]; then
            rdev=$(ip -4 route get 1.1.1.1 iif "$first_if" 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }')
            if [ -z "$rdev" ]; then
                diag_line route-check warn "The kernel found no route for traffic from $first_if"
            elif [ "$rdev" = "$vpn_if_name" ]; then
                diag_line route-check ok "Traffic from $first_if leaves via $rdev"
            else
                diag_line route-check fail "Traffic from $first_if would leave via $rdev, not the VPN"
            fi
        fi

        if [ -n "$IPT" ]; then
            fwfirst=$($IPT $IPT_WAIT -S FORWARD 2>/dev/null | sed -n 2p)
            case "$fwfirst" in
                *"$IPT_CH"*) diag_line forward-hook ok "The tethering chain is the first FORWARD rule" ;;
                *) diag_line forward-hook warn "Another FORWARD rule comes first: $fwfirst" ;;
            esac
        fi

        if [ -n "$vpn_if_name" ]; then
            vmtu=$(ip link show "$vpn_if_name" 2>/dev/null | awk '{ for (i = 1; i <= NF; i++) if ($i == "mtu") { print $(i + 1); exit } }')
            diag_line vpn-mtu info "VPN interface $vpn_if_name has MTU ${vmtu:-unknown}"
        fi
    fi

    if [ "$NOPROV" = 1 ]; then
        if have resetprop || have setprop; then
            diag_line noprovisioning ok "net.tethering.noprovisioning is set. Restart the hotspot or USB tethering to apply it"
        else
            diag_line noprovisioning fail "Neither resetprop nor setprop is available; the property cannot be set"
        fi
    else
        diag_line noprovisioning info "Carrier provisioning check is not changed"
    fi

    if [ -n "$APP_QUIC" ]; then
        app_n=$(quic_uids | wc -l | tr -d ' ')
        if [ "$app_n" -eq 0 ]; then
            diag_line app-quic fail "None of the selected apps were found in packages.list"
        elif [ "$skuid_ok" = 1 ]; then
            diag_line app-quic ok "QUIC is refused for $app_n selected app(s)"
        else
            diag_line app-quic fail "The kernel cannot refuse UDP per app: the app QUIC block will be skipped"
        fi
    else
        diag_line app-quic info "No apps selected for the QUIC block"
    fi

    present=""
    missing=""
    for IF in $(iface_lines "$IFACES"); do
        if [ -e "$NET_SYS/$IF" ]; then present="$present $IF"; else missing="$missing $IF"; fi
    done
    if [ -n "$present" ]; then
        diag_line interfaces ok "Present:$present"
    else
        diag_line interfaces info "No tethering interface is up now; turn on the hotspot or USB tethering to test"
    fi
    if [ -n "$missing" ]; then
        diag_line interfaces-absent info "Not present:$missing (normal when unused)"
    fi

    if [ "$ENABLED" = 1 ]; then
        if [ -f "$TETHER_WATCH_PID" ] && kill -0 "$(cat "$TETHER_WATCH_PID")" 2>/dev/null; then
            diag_line watcher ok "The watcher keeps the rules in place"
        else
            diag_line watcher warn "The watcher is not running: press Save and apply to restart it"
        fi
    fi

    if tether_installed; then
        diag_line rules ok "Tethering rules are installed"
    elif [ "$ENABLED" = 1 ]; then
        diag_line rules warn "Tethering rules are not installed"
    fi
}

case "${1:-status}" in
    apply)           cmd_apply ;;
    remove-tether)   stop_watch; remove_tether_rules ;;
    status)          cmd_status ;;
    interfaces)      cmd_interfaces ;;
    diagnose)        cmd_diagnose ;;
    watch)           cmd_watch ;;
    youtube-refresh) cmd_youtube_refresh ;;
    quic-apply)      load; quic_apply && { if [ "$ENABLED" = 1 ] || [ -n "$APP_QUIC" ]; then ensure_watch; fi; } ;;
    quic-remove)     quic_remove ;;
    *) echo "usage: $0 {apply|remove-tether|status|interfaces|diagnose|youtube-refresh|quic-apply|quic-remove}" >&2; exit 2 ;;
esac
