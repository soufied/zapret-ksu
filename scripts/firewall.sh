#!/system/bin/sh

MODDIR="${ZAPRET_MODDIR:-${0%/*}/..}"
CONF="$MODDIR/config/zapret.conf"
RUN_DIR="$MODDIR/run"
STATE_FILE="$RUN_DIR/firewall.state"
NFT_RULES="$RUN_DIR/zapret.nft"

NFT_TABLE="zapret"
IPT_POST="ZAPRET_POST"
IPT_PRE="ZAPRET_PRE"
IPT_WAIT="-w 5"

NETS4_CSV="10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,127.0.0.0/8"
NETS6_CSV="fc00::/7,fe80::/10,::1/128"
NETS4_SP=$(echo "$NETS4_CSV" | tr ',' ' ')
NETS6_SP=$(echo "$NETS6_CSV" | tr ',' ' ')

BACKEND="auto"
IFACE="any"
QNUM=200
FWMARK="0x40000000"
TCP_PORTS="80,443"
QUIC_ENABLED=1
GF_TCP=""
GF_UDP=""
BYPASS_PRIVATE=1
BYPASS_VPN=1
CONNTRACK_ACCT=1

. "$MODDIR/scripts/common.sh"
log() { mlog fw INFO "$*"; }
die() { mlog fw ERROR "$*"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

load_conf() {
    if [ -f "$CONF" ]; then
        . "$CONF"
    fi
}

clean_ports() {
    echo "$1" | tr -d ' ' | tr -s ',' | sed -e 's/^,//' -e 's/,$//'
}

validate() {
    case "$BACKEND" in auto|nft|iptables) ;; *) die "BACKEND must be auto, nft or iptables" ;; esac
    case "$QNUM" in ''|*[!0-9]*) die "QNUM must be an integer" ;; esac
    case "$FWMARK" in 0x[0-9a-fA-F]*) ;; *) die "FWMARK must be hex, e.g. 0x40000000" ;; esac
    case "$IFACE" in ''|*[!A-Za-z0-9._+-]*) die "invalid IFACE '$IFACE'" ;; esac
    [ -z "$IFACE" ] && IFACE=any
    TCP_PORTS=$(clean_ports "$TCP_PORTS")
    GF_TCP=$(clean_ports "$GF_TCP")
    GF_UDP=$(clean_ports "$GF_UDP")
    for v in "$TCP_PORTS" "$GF_TCP" "$GF_UDP"; do
        case "$v" in *[!0-9,-]*) die "invalid port list '$v'" ;; esac
    done
    [ -n "$TCP_PORTS" ] || die "TCP_PORTS is empty"
}

join_ports() {
    if [ -z "$1" ]; then echo "$2"
    elif [ -z "$2" ]; then echo "$1"
    else echo "$1,$2"; fi
}

uniq_ports() {
    echo "$1" | tr ',' '\n' | grep -v '^$' | sort -u | tr '\n' ',' | sed 's/,$//'
}

compute_ports() {
    TCP_EFF=$(join_ports "$TCP_PORTS" "$GF_TCP")
    UDP_BASE=""
    [ "$QUIC_ENABLED" = 1 ] && UDP_BASE="443"
    UDP_EFF=$(join_ports "$UDP_BASE" "$GF_UDP")
    TCP_EFF=$(uniq_ports "$(join_ports "$TCP_EFF" "$ZAPRET_EXTRA_TCP")")
    UDP_EFF=$(uniq_ports "$(join_ports "$UDP_EFF" "$ZAPRET_EXTRA_UDP")")
}

enable_acct() {
    [ "$CONNTRACK_ACCT" = 1 ] || return 0
    P=/proc/sys/net/netfilter/nf_conntrack_acct
    if [ -w "$P" ]; then
        echo 1 > "$P" 2>/dev/null || log "warning: could not enable conntrack accounting"
    fi
}


nft_usable() {
    have nft || return 1
    nft list tables >/dev/null 2>&1 || return 1
    nft add table inet zapret_probe >/dev/null 2>&1 || return 1
    nft delete table inet zapret_probe >/dev/null 2>&1
    return 0
}

pick_bin() {
    if have "$1-legacy"; then echo "$1-legacy"
    elif have "$1"; then echo "$1"
    else return 1; fi
}

pick_backend() {
    case "$BACKEND" in
        nft)
            nft_usable || die "BACKEND=nft but nftables is not usable on this kernel"
            BE=nft ;;
        iptables)
            pick_bin iptables >/dev/null || die "BACKEND=iptables but no iptables binary exists"
            BE=iptables ;;
        auto)
            if nft_usable; then BE=nft
            elif pick_bin iptables >/dev/null; then BE=iptables
            else die "neither nftables nor iptables is usable"
            fi ;;
    esac
}

nft_set() { echo "$1" | sed 's/,/, /g'; }

nft_build() {
    IFO=""
    IFI=""
    if [ "$IFACE" != any ]; then
        NFT_IF=$(echo "$IFACE" | sed 's/+$/*/')
        IFO="oifname \"$NFT_IF\" "
        IFI="iifname \"$NFT_IF\" "
    fi

    mkdir -p "$RUN_DIR"
    {
        echo "table inet $NFT_TABLE {"
        echo "  chain post {"
        echo "    type filter hook postrouting priority -150; policy accept;"
        echo "    oifname \"lo\" return"
        [ "$BYPASS_VPN" = 1 ] && echo "    oifname { \"tun*\", \"dummy*\" } return"
        if [ "$BYPASS_PRIVATE" = 1 ]; then
            echo "    ip daddr { $NETS4_CSV } return"
            echo "    ip6 daddr { $NETS6_CSV } return"
        fi
        echo "    meta mark & $FWMARK == $FWMARK return"
        if [ -n "$TCP_EFF" ]; then
            echo "    ${IFO}tcp dport { $(nft_set "$TCP_EFF") } ct original packets 1-6 queue num $QNUM bypass"
        fi
        if [ -n "$UDP_EFF" ]; then
            echo "    ${IFO}udp dport { $(nft_set "$UDP_EFF") } ct original packets 1-6 queue num $QNUM bypass"
        fi
        echo "  }"
        echo "  chain pre {"
        echo "    type filter hook prerouting priority -150; policy accept;"
        echo "    iifname \"lo\" return"
        if [ "$BYPASS_PRIVATE" = 1 ]; then
            echo "    ip saddr { $NETS4_CSV } return"
            echo "    ip6 saddr { $NETS6_CSV } return"
        fi
        echo "    meta mark & $FWMARK == $FWMARK return"
        if [ -n "$TCP_EFF" ]; then
            echo "    ${IFI}tcp sport { $(nft_set "$TCP_EFF") } ct reply packets 1-3 queue num $QNUM bypass"
        fi
        echo "  }"
        echo "}"
    } > "$NFT_RULES"
}

nft_apply() {
    nft delete table inet "$NFT_TABLE" >/dev/null 2>&1
    nft_build
    nft -f "$NFT_RULES"
}

nft_clear() {
    have nft || return 0
    nft delete table inet "$NFT_TABLE" >/dev/null 2>&1
    return 0
}

ipt_port() { echo "$1" | tr '-' ':'; }

ipt_probe() {
    T="$1 $IPT_WAIT -t mangle"
    HAVE_CONNBYTES=0
    HAVE_NFQ=0
    $T -N ZAPRET_PROBE >/dev/null 2>&1
    if $T -A ZAPRET_PROBE -p tcp --dport 1 -m connbytes --connbytes-dir=original \
        --connbytes-mode=packets --connbytes 1:6 -j RETURN >/dev/null 2>&1; then
        HAVE_CONNBYTES=1
    fi
    if $T -A ZAPRET_PROBE -p tcp --dport 1 -j NFQUEUE --queue-num "$QNUM" \
        --queue-bypass >/dev/null 2>&1; then
        HAVE_NFQ=1
    fi
    $T -F ZAPRET_PROBE >/dev/null 2>&1
    $T -X ZAPRET_PROBE >/dev/null 2>&1
    [ "$HAVE_NFQ" = 1 ]
}

ipt_family() {
    B="$1"
    F="$2"
    T="$B $IPT_WAIT -t mangle"
    if [ "$F" = 6 ]; then PRIV="$NETS6_SP"; else PRIV="$NETS4_SP"; fi

    if ! ipt_probe "$B"; then
        log "error: $B lacks the NFQUEUE target (kernel built without xt_NFQUEUE)"
        return 1
    fi

    if [ "$HAVE_CONNBYTES" = 1 ]; then
        CB_OUT="-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:6"
        CB_IN="-m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes 1:3"
    else
        CB_OUT=""
        CB_IN=""
        log "note: xt_connbytes missing for $B; all packets on intercepted ports go to nfqws (higher CPU)"
    fi

    $T -N "$IPT_POST" 2>/dev/null
    $T -N "$IPT_PRE" 2>/dev/null
    $T -F "$IPT_POST" || return 1
    $T -F "$IPT_PRE" || return 1
    $T -C POSTROUTING -j "$IPT_POST" 2>/dev/null || $T -I POSTROUTING -j "$IPT_POST" || return 1
    $T -C PREROUTING -j "$IPT_PRE" 2>/dev/null || $T -I PREROUTING -j "$IPT_PRE" || return 1

    $T -A "$IPT_POST" -o lo -j RETURN || return 1
    if [ "$BYPASS_VPN" = 1 ]; then
        $T -A "$IPT_POST" -o tun+ -j RETURN || return 1
        $T -A "$IPT_POST" -o dummy+ -j RETURN || return 1
    fi
    if [ "$BYPASS_PRIVATE" = 1 ]; then
        for n in $PRIV; do
            $T -A "$IPT_POST" -d "$n" -j RETURN || return 1
        done
    fi
    $T -A "$IPT_POST" -m mark --mark "$FWMARK/$FWMARK" -j RETURN || return 1

    OUTIF=""
    [ "$IFACE" != any ] && OUTIF="-o $IFACE"
    if [ -n "$TCP_EFF" ]; then
        for p in $(echo "$TCP_EFF" | tr ',' ' '); do
            $T -A "$IPT_POST" $OUTIF -p tcp --dport "$(ipt_port "$p")" $CB_OUT \
                -j NFQUEUE --queue-num "$QNUM" --queue-bypass || return 1
        done
    fi
    if [ -n "$UDP_EFF" ]; then
        for p in $(echo "$UDP_EFF" | tr ',' ' '); do
            $T -A "$IPT_POST" $OUTIF -p udp --dport "$(ipt_port "$p")" $CB_OUT \
                -j NFQUEUE --queue-num "$QNUM" --queue-bypass || return 1
        done
    fi

    $T -A "$IPT_PRE" -i lo -j RETURN || return 1
    if [ "$BYPASS_PRIVATE" = 1 ]; then
        for n in $PRIV; do
            $T -A "$IPT_PRE" -s "$n" -j RETURN || return 1
        done
    fi
    $T -A "$IPT_PRE" -m mark --mark "$FWMARK/$FWMARK" -j RETURN || return 1

    INIF=""
    [ "$IFACE" != any ] && INIF="-i $IFACE"
    if [ -n "$TCP_EFF" ]; then
        for p in $(echo "$TCP_EFF" | tr ',' ' '); do
            $T -A "$IPT_PRE" $INIF -p tcp --sport "$(ipt_port "$p")" $CB_IN \
                -j NFQUEUE --queue-num "$QNUM" --queue-bypass || return 1
        done
    fi
    return 0
}

ipt_clear() {
    B="$1"
    T="$B $IPT_WAIT -t mangle"
    while $T -D POSTROUTING -j "$IPT_POST" 2>/dev/null; do :; done
    while $T -D PREROUTING -j "$IPT_PRE" 2>/dev/null; do :; done
    $T -F "$IPT_POST" 2>/dev/null
    $T -F "$IPT_PRE" 2>/dev/null
    $T -X "$IPT_POST" 2>/dev/null
    $T -X "$IPT_PRE" 2>/dev/null
    return 0
}

ipt_clear_all() {
    for b in iptables-legacy iptables ip6tables-legacy ip6tables; do
        have "$b" && ipt_clear "$b"
    done
    return 0
}

ipt_apply() {
    B4=$(pick_bin iptables) || return 1
    ipt_family "$B4" 4 || return 1
    if B6=$(pick_bin ip6tables 2>/dev/null); then
        if ! ipt_family "$B6" 6; then
            ipt_clear "$B6"
            log "warning: IPv6 interception unavailable, continuing with IPv4 only"
        fi
    else
        log "note: ip6tables not present, IPv4 only"
    fi
    return 0
}


clear_all() {
    nft_clear
    ipt_clear_all
}

write_state() {
    mkdir -p "$RUN_DIR"
    {
        echo "backend=$BE"
        echo "queue=$QNUM"
        echo "tcp=$TCP_EFF"
        echo "udp=$UDP_EFF"
        echo "iface=$IFACE"
    } > "$STATE_FILE"
}

cmd_start() {
    load_conf
    validate
    compute_ports
    pick_backend
    enable_acct
    clear_all

    case "$BE" in
        nft)
            if ! nft_apply; then
                clear_all
                die "nftables ruleset failed to load (check kernel nf_tables/NFQUEUE support)"
            fi ;;
        iptables)
            if ! ipt_apply; then
                clear_all
                die "iptables rules failed (see messages above)"
            fi ;;
    esac

    write_state
    log "active backend=$BE queue=$QNUM iface=$IFACE tcp=$TCP_EFF udp=${UDP_EFF:-none}"
}

cmd_stop() {
    load_conf
    clear_all
    rm -f "$STATE_FILE"
    log "rules removed"
}

cmd_status() {
    load_conf
    ACTIVE=0
    ACTIVE_BE=none
    if have nft && nft list table inet "$NFT_TABLE" >/dev/null 2>&1; then
        ACTIVE=1
        ACTIVE_BE=nft
    elif B=$(pick_bin iptables 2>/dev/null) && $B $IPT_WAIT -t mangle -S "$IPT_POST" >/dev/null 2>&1; then
        ACTIVE=1
        ACTIVE_BE=iptables
    fi
    echo "firewall_active=$ACTIVE"
    echo "firewall_backend=$ACTIVE_BE"
    echo "queue=$QNUM"
    [ "$ACTIVE" = 1 ]
}

usage() {
    echo "usage: $0 {start|stop|restart|status}" >&2
    exit 2
}

case "${1:-}" in
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    restart) cmd_stop; cmd_start ;;
    status)  cmd_status ;;
    *)       usage ;;
esac
