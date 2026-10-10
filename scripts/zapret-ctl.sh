#!/system/bin/sh

MODDIR="${0%/*}/.."
. "$MODDIR/scripts/common.sh"

CONF="$MODDIR/config/zapret.conf"
FW="$MODDIR/scripts/firewall.sh"
FETCH="$MODDIR/scripts/fetch.sh"
Z1_DIR="$MODDIR/zapret1"
Z2_DIR="$MODDIR/zapret2"
NFQWS1_PID="$RUN_DIR/nfqws.pid"
NFQWS2_PID="$RUN_DIR/nfqws2.pid"
UPDATE_STATE="$RUN_DIR/update.state"
UPDATE_PID="$RUN_DIR/update.pid"

EDITABLE="ENGINE STRATEGY NFQWS_UID BACKEND IFACE TCP_PORTS QUIC_ENABLED GAME_FILTER_TCP GAME_FILTER_UDP BYPASS_PRIVATE BYPASS_VPN CONNTRACK_ACCT AUTO_START BACKUP_LISTS LOG_ENABLED AUTO_NETWORK AUTO_NETWORK_START AUTO_STOP_NONE WIFI_IFACE WIFI_ENGINE WIFI_STRATEGY MOBILE_IFACE MOBILE_ENGINE MOBILE_STRATEGY"
NET_ROUTE="${ZAPRET_NET_ROUTE:-/proc/net/route}"
NET_SYS="${ZAPRET_NET_SYS:-/sys/class/net}"
AUTO_PID="$RUN_DIR/auto.pid"
AUTO_WANTED="$RUN_DIR/auto.wanted"
BC_STATE="$RUN_DIR/blockcheck.state"
BC_CSV="$RUN_DIR/blockcheck.csv"
BC_PID="$RUN_DIR/blockcheck.pid"

die() {
    mlog ctl ERROR "$*"
    echo "error: $*" >&2
    exit 1
}

is_editable() {
    case " $EDITABLE " in *" $1 "*) return 0 ;; esac
    return 1
}

load_conf() {
    ENGINE="nfqws2"
    STRATEGY="Default (game filter).txt"
    QNUM=200
    FWMARK="0x40000000"
    NFQWS_UID="0:0"
    AUTO_START=0
    BACKUP_LISTS=0
    GAME_FILTER_TCP=0
    GAME_FILTER_UDP=0
    LOG_ENABLED=1
    AUTO_NETWORK=0
    AUTO_NETWORK_START=0
    AUTO_STOP_NONE=1
    WIFI_IFACE=wlan0
    WIFI_ENGINE=nfqws2
    WIFI_STRATEGY="Default (game filter).txt"
    MOBILE_IFACE="rmnet_data+"
    MOBILE_ENGINE=nfqws1
    MOBILE_STRATEGY="general (ALT).bat"
    [ -f "$CONF" ] && . "$CONF"
    GF_TCP=""; GF_UDP=""
    if [ "$ENGINE" = nfqws1 ]; then
        [ "$GAME_FILTER_TCP" = 1 ] && GF_TCP="1024-65535"
        [ "$GAME_FILTER_UDP" = 1 ] && GF_UDP="1024-65535"
    fi
    if [ "$LOG_ENABLED" = 0 ]; then ENGINE_OUT=/dev/null; else ENGINE_OUT="$LOG"; fi
    [ -n "${ZAPRET_OVERRIDE_STRATEGY:-}" ] && STRATEGY="$ZAPRET_OVERRIDE_STRATEGY"
    return 0
}

pid_alive() {
    [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null
}

engine_running() {
    pid_alive "$NFQWS1_PID" || pid_alive "$NFQWS2_PID" || pidof nfqws nfqws2 >/dev/null 2>&1
}

stop_engines() {
    for pf in "$NFQWS1_PID" "$NFQWS2_PID"; do
        if [ -f "$pf" ]; then
            kill "$(cat "$pf")" 2>/dev/null
            rm -f "$pf"
        fi
    done
    for p in $(pidof nfqws nfqws2 2>/dev/null); do
        kill "$p" 2>/dev/null
    done
    return 0
}

bat_args() {
    tr -d '\r' < "$1" | sed '/^[[:space:]]*$/d' \
        | sed -e ':a' -e '/\^[[:space:]]*$/N;s/\^[[:space:]]*\n/ /;ta' \
        | grep -i 'winws\.exe' | head -n 1 \
        | sed -e 's/.*winws\.exe"*//' \
              -e "s|%BIN%|$3/|g" \
              -e "s|%LISTS%|$2/|g" \
              -e "s|%GameFilterTCP%|$4|g" \
              -e "s|%GameFilterUDP%|$5|g" \
              -e 's/\^//g' -e 's/"//g' \
        | tr -s " $TAB" "\n" \
        | grep -v '^$' \
        | grep -v '^--wf-' \
        | sed -e 's/,,*/,/g' -e 's/,$//' -e 's/=,/=/' \
        | grep -v '=$'
}

b64() {
    if command -v base64 >/dev/null 2>&1; then
        base64 "$@"
    elif [ -n "$BB" ]; then
        "$BB" base64 "$@"
    else
        return 1
    fi
}

Z1_LISTS="$Z1_DIR/lists"
Z2_LISTS="$Z2_DIR/lists"
IPSET_FILE="$Z1_LISTS/ipset-all.txt"
IPSET_BACKUP="$IPSET_FILE.backup"
IPSET_CUSTOM="$IPSET_FILE.custom"
UPLOAD_FILE="$RUN_DIR/upload.b64"
UPLOAD_MAX=262144
BC_LISTS_FILE="$MODDIR/config/blockcheck-lists.txt"

file_root() {
    case "$1" in
        lists1) FR_DIR="$Z1_LISTS"; FR_EXT=txt ;;
        lists2) FR_DIR="$Z2_LISTS"; FR_EXT=txt ;;
        strat1) FR_DIR="$Z1_DIR";   FR_EXT=bat ;;
        strat2) FR_DIR="$Z2_DIR/presets"; FR_EXT=txt ;;
        *) die "unknown editor root: $1" ;;
    esac
}

file_name_ok() {
    case "$1" in
        ""|.*|*/*|*..*) die "invalid file name: $1" ;;
    esac
    case "$1" in *."$2") ;; *) die "file name must end in .$2" ;; esac
}

cmd_file() {
    sub="${1:-}"; root="${2:-}"
    file_root "$root"
    mkrun
    case "$sub" in
        list)
            [ -d "$FR_DIR" ] || return 0
            for f in "$FR_DIR"/*."$FR_EXT"; do [ -f "$f" ] && basename "$f"; done
            ;;
        read)
            name="${3:-}"; file_name_ok "$name" "$FR_EXT"
            path="$FR_DIR/$name"
            [ -f "$path" ] || die "no such file: $name"
            size=$(wc -c < "$path")
            [ "$size" -le "$UPLOAD_MAX" ] || die "$name is $size bytes; the editor handles files up to 256 KB"
            b64 "$path" | tr -d '\n'
            ;;
        write-begin)
            name="${3:-}"; file_name_ok "$name" "$FR_EXT"
            : > "$UPLOAD_FILE"
            echo "$name" > "$RUN_DIR/upload.name"
            ;;
        write-chunk)
            chunk="${4:-}"
            case "$chunk" in ""|*[!A-Za-z0-9+/=]*) die "bad upload chunk" ;; esac
            [ -f "$RUN_DIR/upload.name" ] || die "no upload in progress"
            printf '%s' "$chunk" >> "$UPLOAD_FILE"
            ;;
        write-commit)
            name="${3:-}"; file_name_ok "$name" "$FR_EXT"
            [ "$(cat "$RUN_DIR/upload.name" 2>/dev/null)" = "$name" ] || die "upload does not match $name"
            tmp="$RUN_DIR/upload.bin"
            b64 -d "$UPLOAD_FILE" > "$tmp" 2>/dev/null || die "upload is not valid base64"
            size=$(wc -c < "$tmp")
            [ "$size" -le "$UPLOAD_MAX" ] || die "file is larger than 256 KB"
            path="$FR_DIR/$name"
            if [ -f "$path" ] && [ "$(sed -n 's/^BACKUP_LISTS=//p' "$CONF" | tail -n 1 | tr -d '\"')" = 1 ]; then
                cp -f "$path" "$path.bak-$(date +%Y%m%d-%H%M%S)" 2>/dev/null
                mlog ctl INFO "backup kept before saving $name"
            fi
            cp -f "$tmp" "$path.new" && mv -f "$path.new" "$path" || die "could not write $name"
            rm -f "$UPLOAD_FILE" "$RUN_DIR/upload.name" "$tmp"
            mlog ctl INFO "saved $root/$name ($size bytes)"
            echo "saved $name"
            ;;
        create)
            name="${3:-}"; file_name_ok "$name" "$FR_EXT"
            [ -e "$FR_DIR/$name" ] && die "$name already exists"
            mkdir -p "$FR_DIR" || die "cannot create $FR_DIR"
            if [ "$root" = strat1 ]; then
                printf '%s\n' '@echo off' 'cd /d "%~dp0"' 'set "BIN=%~dp0bin\"' 'set "LISTS=%~dp0lists\"' > "$FR_DIR/$name"
                printf '%s\n' 'start "zapret" /min "%BIN%winws.exe" --filter-tcp=80,443 --dpi-desync=fake --dpi-desync-repeats=6' >> "$FR_DIR/$name"
            else
                : > "$FR_DIR/$name"
            fi
            mlog ctl INFO "created $root/$name"
            echo "created $name"
            ;;
        delete)
            name="${3:-}"; file_name_ok "$name" "$FR_EXT"
            [ -f "$FR_DIR/$name" ] || die "no such file: $name"
            rm -f "$FR_DIR/$name" || die "could not delete $name"
            mlog ctl WARN "deleted $root/$name"
            echo "deleted $name"
            ;;
        copy)
            name="${3:-}"; new="${4:-}"
            file_name_ok "$name" "$FR_EXT"; file_name_ok "$new" "$FR_EXT"
            [ -f "$FR_DIR/$name" ] || die "no such file: $name"
            [ -e "$FR_DIR/$new" ] && die "$new already exists"
            cp "$FR_DIR/$name" "$FR_DIR/$new" || die "could not copy"
            mlog ctl INFO "copied $root/$name to $new"
            echo "copied to $new"
            ;;
        import)
            src="${3:-}"
            case "$src" in
                /*) ;;
                *) die "give the full path, starting with /" ;;
            esac
            [ -f "$src" ] || die "no such file: $src"
            [ -L "$src" ] && die "symlinks cannot be imported"
            base=$(basename "$src")
            file_name_ok "$base" "$FR_EXT"
            size=$(wc -c < "$src")
            [ "$size" -le "$UPLOAD_MAX" ] || die "$base is larger than 256 KB"
            [ "$size" -gt 0 ] || die "$base is empty"
            if [ "$root" = strat1 ] && ! tr -d '\r' < "$src" | grep -qi 'winws\.exe'; then
                die "not a zapret 1 strategy: no winws.exe line"
            fi
            if [ "$root" = strat2 ] && ! tr -d '\r' < "$src" | grep -q '^[[:space:]]*--'; then
                die "not a zapret 2 preset: no --option lines"
            fi
            stem="${base%.*}"
            name="$base"
            n=2
            while [ -e "$FR_DIR/$name" ]; do
                name="$stem ($n).$FR_EXT"
                n=$((n + 1))
            done
            mkdir -p "$FR_DIR" || die "cannot create $FR_DIR"
            cp "$src" "$FR_DIR/$name.new" && mv -f "$FR_DIR/$name.new" "$FR_DIR/$name" || die "could not write $name"
            mlog ctl INFO "imported $root/$name from $src"
            echo "imported $name"
            ;;
        *) die "file subcommand: list|read|write-begin|write-chunk|write-commit|create|delete|copy|import" ;;
    esac
}

ipset_current() {
    [ -f "$IPSET_FILE" ] || { echo unknown; return; }
    size=$(wc -c < "$IPSET_FILE")
    if [ "$size" = 0 ]; then echo any; return; fi
    lines=$(grep -c . "$IPSET_FILE")
    if [ "$lines" = 1 ] && grep -q '^203\.0\.113\.113/32' "$IPSET_FILE"; then echo none; return; fi
    if [ -f "$IPSET_BACKUP" ] && cmp -s "$IPSET_FILE" "$IPSET_BACKUP"; then echo loaded; return; fi
    echo custom
}

cmd_ipset() {
    case "${1:-}" in
        get) ipset_current ;;
        set)
            mode="${2:-}"
            [ -d "$Z1_LISTS" ] || die "zapret1 lists are not installed; run Update first"
            cur=$(ipset_current)
            if [ "$cur" = custom ] && [ "$mode" != custom ] && [ -f "$IPSET_FILE" ]; then
                cp -f "$IPSET_FILE" "$IPSET_CUSTOM"
            fi
            if [ ! -f "$IPSET_BACKUP" ] && [ -f "$IPSET_FILE" ] && [ "$cur" != none ] && [ "$cur" != any ]; then
                cp -f "$IPSET_FILE" "$IPSET_BACKUP"
            fi
            case "$mode" in
                none)   printf '%s\n' '203.0.113.113/32' > "$IPSET_FILE" ;;
                any)    : > "$IPSET_FILE" ;;
                loaded)
                    if [ -f "$IPSET_BACKUP" ]; then cp -f "$IPSET_BACKUP" "$IPSET_FILE"; else : > "$IPSET_FILE"; fi ;;
                custom)
                    if [ -f "$IPSET_CUSTOM" ]; then cp -f "$IPSET_CUSTOM" "$IPSET_FILE"; else die "no custom ipset saved yet; edit the list first"; fi ;;
                *) die "ipset mode must be none, any, loaded or custom" ;;
            esac
            mlog ctl INFO "ipset mode set to $mode"
            echo "ipset=$mode"
            ;;
        *) die "ipset subcommand: get | set MODE" ;;
    esac
}

ensure_active_fakes() {
    [ -d "$Z1_DIR/bin" ] || return 0
    for active in ACTIVE_DISCORD_UDP.bin ACTIVE_GAME_UDP.bin; do
        [ -f "$Z1_DIR/bin/$active" ] && continue
        src="$Z1_DIR/bin/quic_initial_www_google_com.bin"
        [ -f "$src" ] || src=$(for f in "$Z1_DIR/bin"/*.bin; do [ -f "$f" ] && echo "$f" && break; done)
        [ -n "$src" ] && cp -f "$src" "$Z1_DIR/bin/$active" && mlog engine INFO "created default $active"
    done
    return 0
}

cmd_fakes() {
    case "${1:-}" in
        list)
            [ -d "$Z1_DIR/bin" ] || return 0
            for f in "$Z1_DIR/bin"/*.bin; do
                [ -f "$f" ] || continue
                n=$(basename "$f")
                case "$n" in ACTIVE_*) continue ;; esac
                echo "$n"
            done
            ;;
        get)
            for pair in discord:ACTIVE_DISCORD_UDP.bin game:ACTIVE_GAME_UDP.bin; do
                label="${pair%%:*}"; active="${pair#*:}"
                src=""
                if [ -f "$Z1_DIR/bin/$active" ]; then
                    for f in "$Z1_DIR/bin"/*.bin; do
                        case "$(basename "$f")" in ACTIVE_*) continue ;; esac
                        if [ -f "$f" ] && cmp -s "$f" "$Z1_DIR/bin/$active"; then src=$(basename "$f"); break; fi
                    done
                    [ -n "$src" ] || src="custom"
                fi
                echo "$label=$src"
            done
            ;;
        set)
            target="${2:-}"; name="${3:-}"
            case "$target" in
                discord) active=ACTIVE_DISCORD_UDP.bin ;;
                game)    active=ACTIVE_GAME_UDP.bin ;;
                *) die "fake target must be discord or game" ;;
            esac
            case "$name" in ""|.*|*/*|*..*|ACTIVE_*) die "invalid fake name" ;; esac
            [ -f "$Z1_DIR/bin/$name" ] || die "no such fake: $name"
            cp -f "$Z1_DIR/bin/$name" "$Z1_DIR/bin/$active" || die "could not replace the active fake"
            mlog ctl INFO "active $target fake set to $name"
            echo "$target=$name"
            ;;
        *) die "fakes subcommand: list | get | set discord|game NAME" ;;
    esac
}

cmd_bc_lists() {
    case "${1:-}" in
        show)
            [ -f "$BC_LISTS_FILE" ] && cat "$BC_LISTS_FILE"
            ;;
        clear)
            rm -f "$BC_LISTS_FILE"
            mlog ctl INFO "blockcheck target lists cleared (default domains will be used)"
            echo "cleared"
            ;;
        set)
            names="${2:-}"
            [ -n "$names" ] || die "give a comma-separated list of files"
            mkrun
            : > "$BC_LISTS_FILE.tmp"
            for n in $(echo "$names" | tr ',' ' '); do
                case "$n" in ""|.*|*/*|*..*) rm -f "$BC_LISTS_FILE.tmp"; die "invalid list name: $n" ;; esac
                case "$n" in ipset-*) rm -f "$BC_LISTS_FILE.tmp"; die "ipset lists hold addresses, not domains: $n" ;; esac
                [ -f "$Z2_LISTS/$n" ] || { rm -f "$BC_LISTS_FILE.tmp"; die "no such zapret2 list: $n"; }
                echo "$n" >> "$BC_LISTS_FILE.tmp"
            done
            mv -f "$BC_LISTS_FILE.tmp" "$BC_LISTS_FILE"
            mlog ctl INFO "blockcheck target lists: $names"
            echo "targets=$names"
            ;;
        *) die "bc-lists subcommand: show | set A,B | clear" ;;
    esac
}

cmd_bc_available() {
    [ -d "$Z2_LISTS" ] || return 0
    for f in "$Z2_LISTS"/*.txt; do
        [ -f "$f" ] || continue
        n=$(basename "$f")
        case "$n" in ipset-*|*-user.txt) continue ;; esac
        echo "$n"
    done
}

TAB="$(printf '\t')"
BOM="$(printf '\357\273\277')"

preset_tokens() {
    tr -d '\r' < "$1" | sed -e "1s/^$BOM//" \
        | sed -e ':a' -e '/\^[[:space:]]*$/N;s/\^[[:space:]]*\n/ /;ta' \
        | grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*::' -e '^[[:space:]]*[Rr][Ee][Mm][[:space:]]' \
        | sed -e 's/"//g' -e 's/^[[:space:]]*//' \
        | tr -s " $TAB" '\n' | grep -v '^$'
}

preset_argv() {
    group_filled=0
    pending_new=0
    preset_tokens "$1" | while IFS= read -r tok; do
        case "$tok" in
            --wf-*|--dpi-desync*) continue ;;
            --new) pending_new=$group_filled; continue ;;
        esac
        if [ "$pending_new" = 1 ]; then
            echo "--new"
            pending_new=0
        fi
        echo "$tok"
        group_filled=1
    done
}

preset_ports() {
    preset_tokens "$1" | grep -E "^--(wf|filter)-$2(-in|-out)?=[0-9,-]+\$" \
        | sed 's/^[^=]*=//' | tr ',' '\n' | grep -v '^$' | sort -u | tr '\n' ',' | sed 's/,$//'
}

bat_ports() {
    tr -d '\r' < "$1" | sed '/^[[:space:]]*$/d' \
        | sed -e ':a' -e '/\^[[:space:]]*$/N;s/\^[[:space:]]*\n/ /;ta' \
        | grep -i 'winws\.exe' | head -n 1 | tr -s " $TAB" '\n' \
        | grep -E "^--wf-$2=[0-9,%A-Za-z-]+" | sed 's/^[^=]*=//' | sed 's/%[A-Za-z]*%//g' \
        | tr ',' '\n' | grep -E '^[0-9-]+$' | sort -u | tr '\n' ',' | sed 's/,$//'
}

start_nfqws1() {
    BIN="$MODDIR/bin/nfqws"
    [ -x "$BIN" ] || { mlog engine ERROR "nfqws1 is not installed; run Update components"; return 1; }
    bat="$Z1_DIR/$STRATEGY"
    [ -f "$bat" ] || { mlog engine ERROR "strategy not found: $STRATEGY; run Update components"; return 1; }

    for uf in list-general-user.txt list-exclude-user.txt ipset-exclude-user.txt; do
        [ -f "$Z1_DIR/lists/$uf" ] || : > "$Z1_DIR/lists/$uf" 2>/dev/null
    done

    ensure_active_fakes
    set -- --qnum="$QNUM" --dpi-desync-fwmark="$FWMARK" --uid="$NFQWS_UID"
    n_opts=0
    argfile="$RUN_DIR/nfqws1.args"
    mkrun
    bat_args "$bat" "$Z1_DIR/lists" "$Z1_DIR/bin" "$GF_TCP" "$GF_UDP" > "$argfile"
    while IFS= read -r a; do
        [ -n "$a" ] || continue
        set -- "$@" "$a"
        n_opts=$((n_opts + 1))
    done < "$argfile"
    [ "$n_opts" -gt 0 ] || { mlog engine ERROR "no nfqws options could be read from $STRATEGY"; return 1; }
    mlog engine INFO "$STRATEGY compiled to $n_opts options"

    cd "$Z1_DIR" 2>/dev/null
    "$BIN" "$@" >>"$ENGINE_OUT" 2>&1 &
    echo $! > "$NFQWS1_PID"
    sleep 1
    if ! pid_alive "$NFQWS1_PID"; then
        rm -f "$NFQWS1_PID"
        mlog engine ERROR "nfqws1 exited immediately; see the lines above"
        return 1
    fi
    mlog engine INFO "nfqws1 running (pid $(cat "$NFQWS1_PID"), strategy $STRATEGY)"
}

start_nfqws2() {
    BIN="$MODDIR/bin/nfqws2"
    [ -x "$BIN" ] || { mlog engine ERROR "nfqws2 is not installed; run Update components"; return 1; }
    preset="$Z2_DIR/presets/$STRATEGY"
    [ -f "$preset" ] && [ ! -L "$preset" ] || { mlog engine ERROR "preset not found: $STRATEGY; run Update components"; return 1; }

    set -- --qnum="$QNUM" --fwmark="$FWMARK" --uid="$NFQWS_UID"
    n_opts=0
    argfile="$RUN_DIR/nfqws2.args"
    mkrun
    preset_argv "$preset" > "$argfile"
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        set -- "$@" "$line"
        n_opts=$((n_opts + 1))
    done < "$argfile"
    if [ "$n_opts" -eq 0 ]; then
        mlog engine ERROR "no options could be read from $STRATEGY ($(wc -c < "$preset" 2>/dev/null) bytes); first lines:"
        head -n 3 "$preset" | tr -d '\r' | cut -c1-120 | while IFS= read -r l; do mlog engine ERROR "  | $l"; done
        return 1
    fi
    mlog engine INFO "preset $STRATEGY compiled to $n_opts options"

    cd "$Z2_DIR" || { mlog engine ERROR "zapret2 directory missing"; return 1; }
    mkrun
    if ! "$BIN" --dry-run "$@" >"$RUN_DIR/dryrun.log" 2>&1; then
        mlog engine ERROR "nfqws2 rejected preset $STRATEGY (dry-run):"
        tail -n 20 "$RUN_DIR/dryrun.log" >> "$ENGINE_OUT"
        echo "nfqws2 rejected preset $STRATEGY: $(tail -n 1 "$RUN_DIR/dryrun.log")"
        return 1
    fi
    mlog engine INFO "nfqws2 dry-run accepted the preset"

    rm -f "$NFQWS2_PID"
    "$BIN" --daemon --pidfile="$NFQWS2_PID" "$@" >>"$ENGINE_OUT" 2>&1
    n=0
    while [ "$n" -lt 100 ]; do
        if pid_alive "$NFQWS2_PID"; then
            mlog engine INFO "nfqws2 running (pid $(cat "$NFQWS2_PID"), preset $STRATEGY)"
            return 0
        fi
        n=$((n + 1))
        sleep 0.1
    done
    mlog engine ERROR "nfqws2 did not write a live pidfile within 10 s"
    return 1
}

wifi_up() {
    [ "$(cat "$NET_SYS/$WIFI_IFACE/operstate" 2>/dev/null)" = up ] && [ "$(cat "$NET_SYS/$WIFI_IFACE/carrier" 2>/dev/null)" = 1 ]
}

net_profile() {
    if wifi_up; then
        echo wifi
        return 0
    fi
    pat=$(echo "$MOBILE_IFACE" | tr '+' '*')
    for ifc in $(awk 'NR>1 {print $1}' "$NET_ROUTE" 2>/dev/null | sort -u); do
        case "$ifc" in
            $pat)
                st=$(cat "$NET_SYS/$ifc/operstate" 2>/dev/null)
                if [ "$st" = up ] || [ "$st" = unknown ]; then
                    echo mobile
                    return 0
                fi
                ;;
        esac
    done
    echo none
}

halt_all() {
    stop_engines
    sh "$FW" stop >/dev/null 2>&1
    rm -f "$RUN_DIR/active.engine" "$RUN_DIR/active.strategy"
}

set_wanted() {
    mkrun
    echo "$1" > "$AUTO_WANTED"
}

ensure_auto_loop() {
    if [ -f "$AUTO_PID" ] && kill -0 "$(cat "$AUTO_PID")" 2>/dev/null; then
        return 0
    fi
    if command -v setsid >/dev/null 2>&1; then
        MLOG_ECHO=0 setsid sh "$MODDIR/scripts/zapret-ctl.sh" auto-loop </dev/null >/dev/null 2>&1 &
    else
        MLOG_ECHO=0 sh "$MODDIR/scripts/zapret-ctl.sh" auto-loop </dev/null >/dev/null 2>&1 &
    fi
    mlog ctl INFO "auto profile watcher started"
}

stop_auto_loop() {
    if [ -f "$AUTO_PID" ]; then
        kill "$(cat "$AUTO_PID")" 2>/dev/null
        rm -f "$AUTO_PID"
        mlog ctl INFO "auto profile watcher stopped"
    fi
}

cmd_auto_loop() {
    trap '' HUP
    mkrun
    echo $$ > "$AUTO_PID"
    applied="$(cat "$RUN_DIR/auto.applied" 2>/dev/null)"
    stable=""
    count=0
    while :; do
        load_conf
        [ "$AUTO_NETWORK" = 1 ] || break
        date +%s > "$RUN_DIR/auto.heartbeat"
        if ! bc_busy && [ "$(cat "$AUTO_WANTED" 2>/dev/null)" = 1 ]; then
            p=$(net_profile)
            if [ "$p" = "$stable" ]; then
                count=$((count + 1))
            else
                stable="$p"
                count=1
            fi
            if [ "$count" -ge 2 ] && [ "$p" != "$applied" ]; then
                applied="$p"
                echo "$p" > "$RUN_DIR/auto.applied"
                if [ "$p" = none ]; then
                    mlog ctl INFO "no Wi-Fi or mobile connection"
                    if [ "$AUTO_STOP_NONE" = 1 ]; then
                        halt_all
                        mlog ctl INFO "engine stopped; it starts again when a connection comes up"
                    fi
                else
                    profile_map "$p"
                    mlog ctl INFO "network changed to $p: switching to $PE · $PS"
                    sh "$MODDIR/scripts/zapret-ctl.sh" restart >>"$LOG" 2>&1
                fi
            fi
        fi
        sleep "${AUTO_INTERVAL:-10}"
    done
    rm -f "$AUTO_PID"
}

cmd_auto_ensure() {
    load_conf
    if [ "$AUTO_NETWORK" = 1 ]; then ensure_auto_loop; fi
    return 0
}

profile_map() {
    case "$1" in
        wifi)   PE="$WIFI_ENGINE";   PS="$WIFI_STRATEGY" ;;
        mobile) PE="$MOBILE_ENGINE"; PS="$MOBILE_STRATEGY" ;;
        *)      PE="";               PS="" ;;
    esac
}

engine_for_strategy() {
    case "$1" in
        *.bat) echo nfqws1 ;;
        *.txt) echo nfqws2 ;;
        *)     echo "" ;;
    esac
}

cmd_start() {
    load_conf
    profile=0
    if [ "$AUTO_NETWORK" = 1 ] && [ -z "${ZAPRET_IGNORE_PROFILE:-}" ]; then
        profile=1
        prof=$(net_profile)
        profile_map "$prof"
        mkrun
        echo "$prof" > "$RUN_DIR/auto.applied"
        ensure_auto_loop
        if [ -z "$PE" ]; then
            set_wanted 1
            mlog ctl ERROR "auto profile: no Wi-Fi or mobile connection is up; the engine starts when one comes up"
            return 1
        fi
        ENGINE="$PE"
        STRATEGY="$PS"
        mlog ctl INFO "auto profile: $prof"
    fi
    want=$(engine_for_strategy "$STRATEGY")
    if [ -n "$want" ] && [ "$want" != "$ENGINE" ]; then
        mlog ctl WARN "$STRATEGY is a $want strategy; starting it on $want instead of $ENGINE"
        ENGINE="$want"
    fi
    if [ "$profile" = 1 ]; then set_wanted 1; else set_wanted 0; fi
    mlog ctl INFO "start requested (engine=$ENGINE, preset=$STRATEGY)"
    rm -f "$RUN_DIR/active.engine" "$RUN_DIR/active.strategy"
    stop_engines
    ZAPRET_EXTRA_TCP=""
    ZAPRET_EXTRA_UDP=""
    case "$ENGINE" in
        nfqws2)
            if [ -f "$Z2_DIR/presets/$STRATEGY" ]; then
                ZAPRET_EXTRA_TCP=$(preset_ports "$Z2_DIR/presets/$STRATEGY" tcp)
                ZAPRET_EXTRA_UDP=$(preset_ports "$Z2_DIR/presets/$STRATEGY" udp)
            fi ;;
        nfqws1)
            if [ -f "$Z1_DIR/$STRATEGY" ]; then
                ZAPRET_EXTRA_TCP=$(bat_ports "$Z1_DIR/$STRATEGY" tcp)
                ZAPRET_EXTRA_UDP=$(bat_ports "$Z1_DIR/$STRATEGY" udp)
            fi
            [ -n "$GF_TCP" ] && ZAPRET_EXTRA_TCP="${ZAPRET_EXTRA_TCP:+$ZAPRET_EXTRA_TCP,}$GF_TCP"
            [ -n "$GF_UDP" ] && ZAPRET_EXTRA_UDP="${ZAPRET_EXTRA_UDP:+$ZAPRET_EXTRA_UDP,}$GF_UDP" ;;
    esac
    export ZAPRET_EXTRA_TCP ZAPRET_EXTRA_UDP
    mlog ctl INFO "firewall ports from the strategy: tcp=${ZAPRET_EXTRA_TCP:-none} udp=${ZAPRET_EXTRA_UDP:-none}"
    if ! sh "$FW" start; then
        mlog ctl ERROR "firewall did not start"
        return 1
    fi
    case "$ENGINE" in
        nfqws1) start_nfqws1 ;;
        nfqws2) start_nfqws2 ;;
        *) mlog ctl ERROR "unknown ENGINE '$ENGINE'"; false ;;
    esac || {
        mlog ctl ERROR "engine failed; rolling the firewall back so traffic is not queued"
        sh "$FW" stop >/dev/null 2>&1
        return 1
    }
    mkrun
    echo "$ENGINE" > "$RUN_DIR/active.engine"
    echo "$STRATEGY" > "$RUN_DIR/active.strategy"
    mlog ctl INFO "start complete"
}

cmd_stop() {
    set_wanted 0
    mlog ctl INFO "stop requested"
    halt_all
    mlog ctl INFO "stopped"
}

cmd_status() {
    load_conf
    echo "module_version=$(sed -n 's/^version=//p' "$MODDIR/module.prop")"
    echo "engine=$ENGINE"
    echo "strategy=$STRATEGY"
    echo "active_engine=$(cat "$RUN_DIR/active.engine" 2>/dev/null)"
    echo "active_strategy=$(cat "$RUN_DIR/active.strategy" 2>/dev/null)"
    if [ "$AUTO_NETWORK" = 1 ]; then
        prof=$(net_profile)
        profile_map "$prof"
        hb=$(cat "$RUN_DIR/auto.heartbeat" 2>/dev/null)
        if [ -n "$hb" ] && [ $(( $(date +%s) - hb )) -lt 40 ]; then echo "auto_watcher=alive"; else echo "auto_watcher=dead"; fi
        echo "auto_profile=$prof"
        echo "auto_engine=$PE"
        echo "auto_strategy=$PS"
        echo "auto_wanted=$(cat "$AUTO_WANTED" 2>/dev/null || echo 0)"
    fi
    sh "$FW" status 2>/dev/null
    if engine_running; then echo "nfqws_running=1"; else echo "nfqws_running=0"; fi
    for c in nfqws1 nfqws2 presets; do
        [ -f "$RUN_DIR/$c.version" ] && echo "${c}_version=$(cat "$RUN_DIR/$c.version")"
    done
    echo "presets_count=$(count_files "$Z2_DIR/presets" '*.txt')"
    echo "strategies_count=$(count_files "$Z1_DIR" '*.bat')"
    return 0
}

cmd_dump() {
    for k in $EDITABLE; do
        v=$(sed -n "s/^$k=//p" "$CONF" 2>/dev/null | tail -n 1 | sed 's/^"//; s/"$//')
        echo "$k=$v"
    done
}

cmd_set() {
    k="$1"
    v="$2"
    is_editable "$k" || die "setting not editable: $k"
    case "$k" in
        ENGINE)
            case "$v" in nfqws1|nfqws2) ;; *) die "ENGINE must be nfqws1 or nfqws2" ;; esac ;;
        STRATEGY)
            case "$v" in ""|*/*|*..*) die "invalid strategy name" ;; esac
            load_conf
            d="$Z2_DIR/presets"
            [ "$ENGINE" = nfqws1 ] && d="$Z1_DIR"
            [ -f "$d/$v" ] || die "strategy not installed: $v" ;;
        NFQWS_UID)
            case "$v" in ""|*[!0-9:]*) die "NFQWS_UID must look like 0:0" ;; esac ;;
        AUTO_NETWORK_START|AUTO_STOP_NONE)
            case "$v" in 0|1) ;; *) die "$k must be 0 or 1" ;; esac ;;
        IFACE|WIFI_IFACE|MOBILE_IFACE)
            case "$v" in ""|*[!A-Za-z0-9._+-]*) die "invalid interface name" ;; esac ;;
        AUTO_NETWORK)
            case "$v" in 0|1) ;; *) die "AUTO_NETWORK must be 0 or 1" ;; esac ;;
        WIFI_ENGINE|MOBILE_ENGINE)
            case "$v" in nfqws1|nfqws2) ;; *) die "$k must be nfqws1 or nfqws2" ;; esac ;;
        WIFI_STRATEGY|MOBILE_STRATEGY)
            case "$v" in ""|*/*|*..*) die "invalid strategy name" ;; esac
            if [ "$k" = WIFI_STRATEGY ]; then eng_key=WIFI_ENGINE; else eng_key=MOBILE_ENGINE; fi
            eng=$(sed -n "s/^$eng_key=//p" "$CONF" 2>/dev/null | tail -n 1 | tr -d '"')
            d="$Z2_DIR/presets"
            [ "$eng" = nfqws1 ] && d="$Z1_DIR"
            [ -f "$d/$v" ] || die "strategy not installed for ${eng:-nfqws2}: $v" ;;
        *)
            case "$v" in *[!A-Za-z0-9,._-]*) die "invalid value for $k" ;; esac ;;
    esac
    [ -f "$CONF" ] || die "config missing: $CONF"
    if grep -q "^$k=" "$CONF"; then
        sed -i "s|^$k=.*|$k=\"$v\"|" "$CONF" || die "could not write $CONF"
    else
        echo "$k=\"$v\"" >> "$CONF" || die "could not write $CONF"
    fi
    mlog ctl INFO "setting changed: $k=$v"
    echo "ok $k=$v"
    if [ "$k" = AUTO_NETWORK ]; then
        if [ "$v" = 1 ]; then
            if engine_running; then set_wanted 1; else set_wanted 0; fi
            ensure_auto_loop
        else
            set_wanted 0
            stop_auto_loop
        fi
    fi
}

cmd_strategies() {
    load_conf
    case "${1:-}" in nfqws1|nfqws2) ENGINE="$1" ;; esac
    if [ "$ENGINE" = nfqws1 ]; then
        for f in "$Z1_DIR"/*.bat; do [ -f "$f" ] && basename "$f"; done
    else
        for f in "$Z2_DIR/presets"/*.txt; do [ -f "$f" ] && basename "$f"; done
    fi
    return 0
}

count_files() {
    n=0
    for f in "$1"/$2; do [ -f "$f" ] && n=$((n + 1)); done
    echo "$n"
}

update_busy() {
    [ -f "$UPDATE_PID" ] && kill -0 "$(cat "$UPDATE_PID")" 2>/dev/null
}

cmd_update() {
    tgt="${1:-}"
    case "$tgt" in
        nfqws1|nfqws2|presets|strategies|all) ;;
        *) die "update target must be nfqws1, nfqws2, presets, strategies or all" ;;
    esac
    if update_busy; then
        echo "an update is already running; wait for it to finish"
        mlog ctl WARN "update $tgt refused: another update is running"
        return 1
    fi
    mkrun
    rotate_log
    mlog ctl INFO "update $tgt requested"
    MLOG_ECHO=0 sh "$FETCH" "$tgt" >>"$LOG" 2>&1 </dev/null &
    echo $! > "$UPDATE_PID"
    echo "update $tgt started (pid $!)"
}

cmd_update_status() {
    st=idle; tgt=""; step=""; msg=""; file=""; done=""; started=""; updated=""
    if [ -f "$UPDATE_STATE" ]; then
        while IFS= read -r line; do
            case "$line" in
                state=*)   st="${line#state=}" ;;
                target=*)  tgt="${line#target=}" ;;
                step=*)    step="${line#step=}" ;;
                message=*) msg="${line#message=}" ;;
                file=*)    file="${line#file=}" ;;
                done=*)    done="${line#done=}" ;;
                started=*) started="${line#started=}" ;;
                updated=*) updated="${line#updated=}" ;;
            esac
        done < "$UPDATE_STATE"
    fi
    if [ "$st" = running ] && ! update_busy; then
        st=error
        msg="the update process stopped unexpectedly; see zapret.log"
        mlog ctl ERROR "$msg"
    fi
    bytes=0
    [ -n "$file" ] && [ -f "$file" ] && bytes=$(wc -c < "$file" 2>/dev/null)
    now=$(date +%s)
    elapsed=0
    [ -n "$started" ] && elapsed=$((now - started))
    echo "update_state=$st"
    echo "update_target=$tgt"
    echo "update_step=$step"
    echo "update_message=$msg"
    echo "update_done=$done"
    echo "update_file=$(basename "$file" 2>/dev/null)"
    echo "update_bytes=${bytes:-0}"
    echo "update_elapsed=$elapsed"
    echo "update_idle_s=$((now - ${updated:-$now}))"
}

bc_busy() {
    [ -f "$BC_PID" ] && kill -0 "$(cat "$BC_PID")" 2>/dev/null
}

cmd_blockcheck() {
    case "${1:-}" in
        start)
            if bc_busy; then
                echo "Blockcheck is already running"
                return 1
            fi
            n="${2:-15}"
            case "$n" in ''|*[!0-9]*) die "preset count must be a number" ;; esac
            mkrun
            rm -f "$BC_CSV"
            was=0
            if sh "$FW" status 2>/dev/null | grep -q '^firewall_active=1'; then was=1; fi
            if [ "$was" = 1 ] || engine_running; then
                mlog ctl INFO "stopping the running engine before Blockcheck"
                stop_engines
                sh "$FW" stop >/dev/null 2>&1
            fi
            mlog ctl INFO "blockcheck requested (presets=$n)"
            ZAPRET_BC_RESTORE="$was" MLOG_ECHO=0 sh "$MODDIR/scripts/blockcheck.sh" run "$n" >>"$LOG" 2>&1 </dev/null &
            echo $! > "$BC_PID"
            echo "blockcheck started (pid $!)"
            ;;
        cancel)
            was=0
            [ -f "$BC_STATE" ] && was=$(sed -n 's/^was_active=//p' "$BC_STATE")
            if bc_busy; then
                pkill -P "$(cat "$BC_PID")" 2>/dev/null
                kill "$(cat "$BC_PID")" 2>/dev/null
            fi
            rm -f "$BC_PID"
            stop_engines
            sh "$FW" stop >/dev/null 2>&1
            mkrun
            {
                echo "state=cancelled"
                echo "step="
                echo "done=0"
                echo "total=0"
                echo "message=cancelled by user"
                echo "was_active=$was"
                echo "started=$(date +%s)"
                echo "updated=$(date +%s)"
            } > "$BC_STATE"
            mlog ctl WARN "blockcheck cancelled"
            if [ "$was" = 1 ]; then
                mlog ctl INFO "restoring the firewall that was running before Blockcheck"
                cmd_start
            fi
            echo "blockcheck cancelled"
            ;;
        apply)
            best=$(sed -n '2p' "$BC_CSV" 2>/dev/null | cut -d'|' -f1)
            [ -n "$best" ] && [ "$best" != "__baseline__" ] || die "no Blockcheck result to apply; run Blockcheck first"
            eng=$(sed -n 's/^engine=//p' "$BC_STATE" 2>/dev/null)
            case "$eng" in nfqws1|nfqws2) ;; *) die "Blockcheck result has no engine recorded" ;; esac
            cmd_set ENGINE "$eng" >/dev/null
            cmd_set STRATEGY "$best" || die "could not apply $best"
            mlog ctl INFO "applied best Blockcheck result: $eng $best"
            ;;
        *)
            die "blockcheck subcommand must be start [N], cancel or apply"
            ;;
    esac
}

cmd_bc_status() {
    st=idle; step=""; done=0; total=0; msg=""; was=0; started=""; updated=""; eng=""
    if [ -f "$BC_STATE" ]; then
        while IFS= read -r line; do
            case "$line" in
                state=*)      st="${line#state=}" ;;
                step=*)       step="${line#step=}" ;;
                done=*)       done="${line#done=}" ;;
                total=*)      total="${line#total=}" ;;
                message=*)    msg="${line#message=}" ;;
                was_active=*) was="${line#was_active=}" ;;
                engine=*)     eng="${line#engine=}" ;;
                started=*)    started="${line#started=}" ;;
                updated=*)    updated="${line#updated=}" ;;
            esac
        done < "$BC_STATE"
    fi
    if [ "$st" = running ] && ! bc_busy; then
        st=error
        msg="Blockcheck stopped unexpectedly; see zapret.log"
        mlog ctl ERROR "$msg"
    fi
    now=$(date +%s)
    elapsed=0
    [ -n "$started" ] && elapsed=$((now - started))
    echo "bc_state=$st"
    echo "bc_step=$step"
    echo "bc_done=$done"
    echo "bc_total=$total"
    echo "bc_message=$msg"
    echo "bc_elapsed=$elapsed"
    echo "bc_engine=$eng"
}

cmd_interfaces() {
    for d in /sys/class/net/*; do
        n=$(basename "$d")
        [ "$n" = lo ] && continue
        echo "$n"
    done
    return 0
}

cmd_log_clear() {
    : > "$LOG" 2>/dev/null
    rm -f "$LOG.1"
    echo "log cleared"
}

cmd_log() {
    n="${1:-100}"
    case "$n" in ''|*[!0-9]*) die "log line count must be a number" ;; esac
    [ -f "$LOG" ] || { echo "(no log yet)"; return 0; }
    tail -n "$n" "$LOG"
}

cmd_snapshot() {
    cmd_status
    echo "---UPDATE---"
    cmd_update_status
    echo "---BLOCKCHECK---"
    cmd_bc_status
    echo "---BCRESULTS---"
    if [ -f "$BC_CSV" ]; then cat "$BC_CSV"
    elif [ -f "$RUN_DIR/blockcheck.raw" ]; then cat "$RUN_DIR/blockcheck.raw"; fi
    echo "---LOG---"
    cmd_log "${1:-200}"
}

case "${1:-}" in
    status)        cmd_status ;;
    start)         cmd_start ;;
    stop)          cmd_stop ;;
    restart)       halt_all; cmd_start ;;
    auto-loop)     cmd_auto_loop ;;
    auto-ensure)   cmd_auto_ensure ;;
    dump)          cmd_dump ;;
    set)           cmd_set "${2:-}" "${3-}" ;;
    strategies)    cmd_strategies "${2:-}" ;;
    interfaces)    cmd_interfaces ;;
    ipset)         cmd_ipset "${2:-}" "${3:-}" ;;
    fakes)         cmd_fakes "${2:-}" "${3:-}" "${4:-}" ;;
    file)          cmd_file "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    bc-lists)      cmd_bc_lists "${2:-}" "${3:-}" ;;
    bc-available)  cmd_bc_available ;;
    update)        cmd_update "${2:-}" ;;
    update-status) cmd_update_status ;;
    blockcheck)    cmd_blockcheck "${2:-}" "${3:-}" ;;
    bc-status)     cmd_bc_status ;;
    log)           if [ "${2:-}" = clear ]; then cmd_log_clear; else cmd_log "${2:-}"; fi ;;
    snapshot)      cmd_snapshot "${2:-}" ;;
    *)             echo "usage: $0 {status|start|stop|restart|dump|set K V|strategies [E]|update N|update-status|log [N]|snapshot [N]}" >&2; exit 2 ;;
esac
