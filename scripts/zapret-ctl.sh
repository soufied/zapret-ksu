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

EDITABLE="ENGINE STRATEGY NFQWS_UID BACKEND IFACE TCP_PORTS QUIC_ENABLED GAME_FILTER_TCP GAME_FILTER_UDP BYPASS_PRIVATE BYPASS_VPN CONNTRACK_ACCT AUTO_START BACKUP_LISTS LOG_ENABLED UDP_PORTS APP_MODE APP_LIST AUTO_NETWORK AUTO_NETWORK_START AUTO_STOP_NONE WIFI_IFACE WIFI_ENGINE WIFI_STRATEGY MOBILE_IFACE MOBILE_ENGINE MOBILE_STRATEGY DOH_ENABLED DOH_AUTO_START DOH_PROVIDERS"
NET_ROUTE="${ZAPRET_NET_ROUTE:-/proc/net/route}"
NET_SYS="${ZAPRET_NET_SYS:-/sys/class/net}"
AUTO_PID="$RUN_DIR/auto.pid"
AUTO_WANTED="$RUN_DIR/auto.wanted"
BC_STATE="$RUN_DIR/blockcheck.state"
BC_RESULTS="$RUN_DIR/blockcheck.results"
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
    [ -n "${ZAPRET_OVERRIDE_ENGINE:-}" ] && ENGINE="$ZAPRET_OVERRIDE_ENGINE"
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
    ensure_safety
}

cmd_stop() {
    set_wanted 0
    mlog ctl INFO "stop requested"
    halt_all
    mlog ctl INFO "stopped"
}

private_dns_cached() {
    cache="$RUN_DIR/private-dns.cache"
    now=$(date +%s)
    if [ -f "$cache" ] && [ $(( now - $(sed -n 1p "$cache") )) -lt 30 ]; then
        sed -n 2,3p "$cache"
        return 0
    fi
    mode=$(settings get global private_dns_mode 2>/dev/null)
    host=$(settings get global private_dns_specifier 2>/dev/null)
    mkrun
    printf '%s\nmode=%s\nhost=%s\n' "$now" "$mode" "$host" > "$cache"
    printf 'mode=%s\nhost=%s\n' "$mode" "$host"
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
    for c in nfqws1 nfqws2 presets dnscrypt; do
        [ -f "$RUN_DIR/$c.version" ] && echo "${c}_version=$(cat "$RUN_DIR/$c.version")"
    done
    dns=$(private_dns_cached)
    echo "dns_mode=$(echo "$dns" | sed -n 's/^mode=//p')"
    echo "dns_host=$(echo "$dns" | sed -n 's/^host=//p')"
    echo "doh_running=$(doh_running && echo 1 || echo 0)"
    echo "doh_count=$(doh_normalize "$(conf_get DOH_PROVIDERS)" | wc -w | tr -d ' ')"
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
        UDP_PORTS)
            case "$v" in ""|*[!0-9,-]*) die "UDP_PORTS may only contain ports, ranges and commas" ;; esac ;;
        APP_MODE)
            case "$v" in disabled|include|exclude) ;; *) die "APP_MODE must be disabled, include or exclude" ;; esac ;;
        APP_LIST)
            case "$v" in *[!A-Za-z0-9._,]*) die "APP_LIST may only contain package names, UIDs and commas" ;; esac ;;
        DOH_ENABLED|DOH_AUTO_START)
            case "$v" in 0|1) ;; *) die "$k must be 0 or 1" ;; esac ;;
        DOH_PROVIDERS)
            case "$v" in *[!A-Za-z0-9,._-]*) die "DOH_PROVIDERS may only contain provider ids and commas" ;; esac ;;
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
        nfqws1|nfqws2|presets|strategies|dnscrypt|all) ;;
        *) die "update target must be nfqws1, nfqws2, presets, strategies, dnscrypt or all" ;;
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
            dns_opt="${3:-auto}"
            eng_opt="${4:-}"
            case "$eng_opt" in ""|nfqws1|nfqws2) ;; *) die "engine must be nfqws1 or nfqws2" ;; esac
            case "$dns_opt" in auto|doh|dot) ;; *) die "DNS option must be auto, doh or dot" ;; esac
            mkrun
            rm -f "$BC_RESULTS" "$RUN_DIR/blockcheck.raw"
            was=0
            if sh "$FW" status 2>/dev/null | grep -q '^firewall_active=1'; then was=1; fi
            if [ "$was" = 1 ] || engine_running; then
                mlog ctl INFO "stopping the running engine before Blockcheck"
                stop_engines
                sh "$FW" stop >/dev/null 2>&1
            fi
            mlog ctl INFO "blockcheck requested (presets=$n, dns=$dns_opt, engine=${eng_opt:-config})"
            {
                echo "engine=$(conf_get ENGINE)"
                echo "state=running"
                echo "step=starting"
                echo "done=0"
                echo "total=0"
                echo "message=starting Blockcheck"
                echo "was_active=$was"
                echo "dns="
                echo "started=$(date +%s)"
                echo "updated=$(date +%s)"
            } > "$BC_STATE"
            ZAPRET_IGNORE_PROFILE=1 ZAPRET_BC_RESTORE="$was" MLOG_ECHO=0 sh "$MODDIR/scripts/blockcheck.sh" run "$n" "$dns_opt" "$eng_opt" >/dev/null 2>&1 </dev/null &
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
            network_clean "blockcheck cancelled" >/dev/null 2>&1
            mkrun
            eng=$(sed -n 's/^engine=//p' "$BC_STATE" 2>/dev/null)
            dnsn=$(sed -n 's/^dns=//p' "$BC_STATE" 2>/dev/null)
            {
                echo "engine=$eng"
                echo "state=cancelled"
                echo "step="
                echo "done=0"
                echo "total=0"
                echo "message=cancelled by user"
                echo "was_active=$was"
                echo "dns=$dnsn"
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
            best=$(grep -v '^__baseline' "$BC_RESULTS" 2>/dev/null | awk -F'|' '$9 == "" {print $1; exit}')
            [ -n "$best" ] || die "no Blockcheck result to apply; run Blockcheck first"
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

network_clean() {
    why="${1:-manual}"
    mlog ctl WARN "network clean ($why): removing module firewall rules and DNS redirect, stopping engines"
    stop_engines
    sh "$FW" stop >/dev/null 2>&1
    doh_drop_dns
    if [ -f "$SAFETY_PID" ]; then kill "$(cat "$SAFETY_PID")" 2>/dev/null; fi
    rm -f "$SAFETY_PID" "$RUN_DIR/active.engine" "$RUN_DIR/active.strategy"
    left=$(network_leftovers)
    if [ -z "$left" ]; then
        mlog ctl INFO "network clean: nothing from the module is left"
    else
        mlog ctl ERROR "network clean: still present:$left"
    fi
    [ -z "$left" ]
}

network_leftovers() {
    l=""
    sh "$FW" status >/dev/null 2>&1 && l="$l firewall"
    nft list table ip "$DOH_NFT_TABLE" >/dev/null 2>&1 && l="$l dns-redirect(nft,v4)"
    nft list table ip6 "$DOH_NFT_TABLE" >/dev/null 2>&1 && l="$l dns-redirect(nft,v6)"
    for b in iptables-legacy iptables ip6tables; do
        command -v "$b" >/dev/null 2>&1 || continue
        $b -w 5 -t nat -S OUTPUT 2>/dev/null | grep -q "$DOH_IPT_CHAIN" && l="$l dns-redirect($b)"
    done
    doh_running && l="$l doh-proxy"
    engine_running && l="$l engine"
    echo "$l"
}

doh_drop_dns() {
    doh_stop_proc
    doh_rules_remove
    rm -f "$RUN_DIR/doh-ips.txt" "$RUN_DIR/private-dns.cache"
    doh_restore_private_dns
    cmd_set DOH_ENABLED 0 >/dev/null 2>&1
}

dns_probe() {
    command -v curl >/dev/null 2>&1 || return 0
    curl -s -o /dev/null --max-time 5 https://www.gstatic.com/generate_204 2>/dev/null
    [ "$?" != 6 ]
}

cmd_safety_loop() {
    mkrun
    if [ -f "$SAFETY_PID" ] && [ "$(cat "$SAFETY_PID")" != "$$" ] && kill -0 "$(cat "$SAFETY_PID")" 2>/dev/null; then
        exit 0
    fi
    echo "$$" > "$SAFETY_PID"
    dns_fail=0
    fw_fail=0
    while :; do
        sleep 10
        if bc_busy; then
            dns_fail=0
            fw_fail=0
            continue
        fi
        fw_on=0
        sh "$FW" status >/dev/null 2>&1 && fw_on=1
        dns_on=0
        [ "$(doh_redirect_active)" = 1 ] && dns_on=1
        if [ "$fw_on" = 0 ] && [ "$dns_on" = 0 ]; then
            rm -f "$SAFETY_PID"
            exit 0
        fi
        if [ "$fw_on" = 1 ] && ! engine_running; then
            fw_fail=$((fw_fail + 1))
            if [ "$fw_fail" -ge 2 ]; then
                mlog ctl ERROR "safety: firewall rules are active but no engine runs; removing them so traffic is not held"
                sh "$FW" stop >/dev/null 2>&1
                fw_fail=0
            fi
        else
            fw_fail=0
        fi
        if [ "$dns_on" = 1 ]; then
            if ! doh_running; then
                mlog ctl ERROR "safety: DoH proxy is not running; removing its DNS redirect"
                doh_drop_dns
                dns_fail=0
            elif dns_probe; then
                dns_fail=0
            else
                dns_fail=$((dns_fail + 1))
                if [ "$dns_fail" -ge 3 ]; then
                    mlog ctl ERROR "safety: DNS through the DoH proxy failed 3 checks in a row; removing the redirect so names resolve again"
                    doh_drop_dns
                    dns_fail=0
                fi
            fi
        else
            dns_fail=0
        fi
    done
}

ensure_safety() {
    if [ -f "$SAFETY_PID" ] && kill -0 "$(cat "$SAFETY_PID")" 2>/dev/null; then
        return 0
    fi
    nohup sh "$MODDIR/scripts/zapret-ctl.sh" safety-loop >/dev/null 2>&1 </dev/null &
}

cmd_emergency() {
    if bc_busy; then
        pkill -P "$(cat "$BC_PID")" 2>/dev/null
        kill "$(cat "$BC_PID")" 2>/dev/null
        rm -f "$BC_PID"
    fi
    set_wanted 0
    network_clean "emergency"
    if [ -f "$BC_STATE" ]; then
        sed 's/^state=running/state=cancelled/' "$BC_STATE" > "$BC_STATE.tmp" && mv -f "$BC_STATE.tmp" "$BC_STATE"
    fi
    echo "network restored"
}

cmd_bc_status() {
    st=idle; step=""; done=0; total=0; msg=""; was=0; started=""; updated=""; eng=""; dnsn=""
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
                dns=*)        dnsn="${line#dns=}" ;;
                started=*)    started="${line#started=}" ;;
                updated=*)    updated="${line#updated=}" ;;
            esac
        done < "$BC_STATE"
    fi
    if [ "$st" = running ] && ! bc_busy && [ $(( $(date +%s) - ${updated:-0} )) -gt 20 ]; then
        st=error
        msg="Blockcheck stopped unexpectedly; network restored"
        mlog ctl ERROR "Blockcheck stopped unexpectedly; cleaning up its firewall and DNS changes"
        network_clean "blockcheck stopped unexpectedly" >/dev/null 2>&1
        sed 's/^state=running/state=error/' "$BC_STATE" > "$BC_STATE.tmp" && mv -f "$BC_STATE.tmp" "$BC_STATE"
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
    echo "bc_dns=$dnsn"
}

bbx() {
    if [ -n "$BB" ]; then "$BB" "$@"; else "$@"; fi
}

PKG="${ZAPRET_PKG_LIST:-/data/system/packages.list}"

pkg_key() {
    [ -f "$PKG" ] && ls -l "$PKG" 2>/dev/null | tr -s ' '
}

cmd_apps_list() {
    mkrun
    CACHE="$RUN_DIR/apps.list"
    KEYF="$RUN_DIR/apps.key"
    key=$(pkg_key)
    if [ -n "$key" ] && [ -f "$CACHE" ] && [ "$(cat "$KEYF" 2>/dev/null)" = "$key" ]; then
        cat "$CACHE"
        return 0
    fi
    USR="$RUN_DIR/user-packages.txt"
    HAVE_USR=0
    if command -v pm >/dev/null 2>&1; then
        pm list packages -3 2>/dev/null | sed -n 's/^package://p' | tr -d '\r' | sort -u > "$USR"
        HAVE_USR=1
    else
        : > "$USR"
    fi
    LIST="$RUN_DIR/apps.raw"
    if [ -f "$PKG" ]; then
        awk '$2>=10000 {print $1"|"$2}' "$PKG" | sort -u > "$LIST"
    elif [ "$HAVE_USR" = 1 ]; then
        pm list packages -U 2>/dev/null | sed -n 's/^package:\([^ ]*\) uid:\([0-9]*\).*/\1|\2/p' | sort -u > "$LIST"
    else
        die "no package list available (neither $PKG nor pm)"
    fi
    awk -F'|' -v LIST="$LIST" -v USRF="$USR" -v HAVE="$HAVE_USR" \
        -v LBL="$MODDIR/config/app-labels.txt" -v OVR="$MODDIR/config/app-names.txt" '
        function fallback(p,  n) {
            n = p; sub(/.*\./, "", n); gsub(/[_-]/, " ", n)
            return toupper(substr(n, 1, 1)) substr(n, 2)
        }
        BEGIN {
            while ((getline l < USRF) > 0) usr[l] = 1
            for (k = 1; k <= 2; k++) {
                f = (k == 1 ? LBL : OVR)
                while ((getline l < f) > 0) {
                    e = index(l, "=")
                    if (e > 1) lab[substr(l, 1, e - 1)] = substr(l, e + 1)
                }
            }
            while ((getline l < LIST) > 0) {
                split(l, a, "|")
                name = (a[1] in lab) ? lab[a[1]] : fallback(a[1])
                t = (HAVE == "1" && !(a[1] in usr)) ? "s" : "u"
                print a[1] "|" a[2] "|" name "|" t
            }
        }' > "$CACHE.tmp" && mv -f "$CACHE.tmp" "$CACHE"
    [ -n "$key" ] && echo "$key" > "$KEYF"
    cat "$CACHE"
}

cmd_latest_versions() {
    t1=$(http_get "https://api.github.com/repos/bol-van/zapret/releases/latest" | grep -o '"tag_name": *"[^"]*"' | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/')
    t2=$(http_get "https://api.github.com/repos/bol-van/zapret2/releases/latest" | grep -o '"tag_name": *"[^"]*"' | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/')
    t3=$(http_get "https://api.github.com/repos/Flowseal/zapret-discord-youtube/commits/main" | grep -o '"sha": *"[0-9a-f]\{40\}"' | head -n 1 | grep -o '[0-9a-f]\{40\}' | cut -c1-7)
    echo "latest_nfqws1=${t1:-unknown}"
    echo "latest_nfqws2=${t2:-unknown}"
    echo "latest_strategies=${t3:-unknown}"
    echo "installed_nfqws1=$(cat "$RUN_DIR/nfqws1.version" 2>/dev/null)"
    echo "installed_nfqws2=$(cat "$RUN_DIR/nfqws2.version" 2>/dev/null)"
    echo "installed_strategies=$(cat "$RUN_DIR/strategies.version" 2>/dev/null)"
}

apk_icon_entry() {
    names="$1"
    entry=""
    for dpi in xxxhdpi xxhdpi xhdpi hdpi mdpi; do
        entry=$(echo "$names" | grep -E "^res/mipmap-$dpi[^/]*/(ic_launcher|ic_launcher_round|icon|app_icon)\.(png|webp)$" | head -n 1)
        [ -n "$entry" ] && break
    done
    if [ -z "$entry" ]; then
        entry=$(echo "$names" | grep -E '^res/(mipmap|drawable)[^/]*/(ic_launcher|icon|app_icon)[^/]*\.(png|webp)$' | head -n 1)
    fi
    echo "$entry"
}

apk_list() {
    bbx unzip -l "$1" 2>/dev/null | grep -o 'res/[^[:space:]]*'
}

apk_map() {
    MAP="$RUN_DIR/apk.map"
    key=$(pkg_key)
    if [ -s "$MAP" ] && [ -n "$key" ] && [ "$(cat "$RUN_DIR/apk.key" 2>/dev/null)" = "$key" ]; then
        return 0
    fi
    command -v pm >/dev/null 2>&1 || return 1
    pm list packages -f 2>/dev/null | sed -n 's/^package:\(.*\)=\([^=]*\)$/\2|\1/p' > "$MAP.tmp" \
        && mv -f "$MAP.tmp" "$MAP"
    [ -n "$key" ] && echo "$key" > "$RUN_DIR/apk.key"
    return 0
}

icon_one() {
    pkg="$1"
    case "$pkg" in
        ""|*[!A-Za-z0-9._]*) return 0 ;;
    esac
    cdir="$RUN_DIR/icons"
    mkdir -p "$cdir" 2>/dev/null
    cache="$cdir/$pkg.txt"
    if [ -f "$cache" ]; then
        cat "$cache"
        return 0
    fi
    apk_map || return 0
    apk=$(awk -F'|' -v p="$pkg" '$1==p {print $2; exit}' "$MAP")
    
    [ -n "$apk" ] && [ -f "$apk" ] || return 0
    entry=$(apk_icon_entry "$(apk_list "$apk")")
    if [ -z "$entry" ]; then
        : > "$cache"
        return 0
    fi
    ext=${entry##*.}
    data=$(bbx unzip -p "$apk" "$entry" 2>/dev/null | b64 | tr -d '\n')
    size=${#data}
    if [ "$size" -eq 0 ] || [ "$size" -gt 120000 ]; then
        : > "$cache"
        return 0
    fi
    echo "$ext $data" > "$cache"
    cat "$cache"
}

cmd_apps_icons() {
    shift
    for pkg in "$@"; do
        r=$(icon_one "$pkg")
        [ -n "$r" ] && printf '%s|%s\n' "$pkg" "$r"
    done
    return 0
}

DOH_PORT=5354
DOH_BIN="$MODDIR/bin/dnscrypt-proxy"
DOH_DIR="$RUN_DIR/doh"
DOH_TOML="$DOH_DIR/dnscrypt-proxy.toml"
DOH_PID="$RUN_DIR/doh.pid"
DOH_WATCH_PID="$RUN_DIR/doh-watch.pid"
DOH_LOG="$RUN_DIR/doh.log"
SAFETY_PID="$RUN_DIR/safety.pid"
DOH_STAMPS="$MODDIR/config/doh-providers.txt"
DOH_NFT_TABLE="zapret_doh"
DOH_IPT_CHAIN="ZAPRET_DOH"
DOH_V6=0

conf_get() { sed -n "s/^$1=//p" "$CONF" 2>/dev/null | tail -n 1 | tr -d '"'; }

doh_running() { [ -f "$DOH_PID" ] && kill -0 "$(cat "$DOH_PID")" 2>/dev/null; }

doh_stop_proc() {
    if [ -f "$DOH_WATCH_PID" ]; then kill "$(cat "$DOH_WATCH_PID")" 2>/dev/null; fi
    rm -f "$DOH_WATCH_PID"
    if [ -f "$DOH_PID" ]; then kill "$(cat "$DOH_PID")" 2>/dev/null; fi
    rm -f "$DOH_PID"
}

doh_ipt_bin() {
    for b in iptables-legacy iptables; do
        command -v "$b" >/dev/null 2>&1 && { echo "$b"; return 0; }
    done
    return 1
}

doh_v6_loopback() {
    grep -q '^00000000000000000000000000000001 ' /proc/net/if_inet6 2>/dev/null
}

doh_nft_redirect() {
    nft -f - >/dev/null 2>&1 <<NFT
table $1 $DOH_NFT_TABLE {
  chain out {
    type nat hook output priority -100; policy accept;
    udp dport 53 counter redirect to :$DOH_PORT
    tcp dport 53 counter redirect to :$DOH_PORT
  }
}
NFT
}

doh_ipt_redirect() {
    $1 -w 5 -t nat -N "$DOH_IPT_CHAIN" 2>/dev/null
    $1 -w 5 -t nat -F "$DOH_IPT_CHAIN" 2>/dev/null || return 1
    $1 -w 5 -t nat -A "$DOH_IPT_CHAIN" -p udp --dport 53 -j REDIRECT --to-ports "$DOH_PORT" || return 1
    $1 -w 5 -t nat -A "$DOH_IPT_CHAIN" -p tcp --dport 53 -j REDIRECT --to-ports "$DOH_PORT" || return 1
    $1 -w 5 -t nat -C OUTPUT -j "$DOH_IPT_CHAIN" 2>/dev/null || $1 -w 5 -t nat -I OUTPUT 1 -j "$DOH_IPT_CHAIN" || return 1
}

doh_rules_add() {
    DOH_V6=0
    if command -v nft >/dev/null 2>&1 && nft list tables >/dev/null 2>&1; then
        nft delete table ip "$DOH_NFT_TABLE" >/dev/null 2>&1
        nft delete table ip6 "$DOH_NFT_TABLE" >/dev/null 2>&1
        if doh_nft_redirect ip; then
            mlog ctl INFO "DNS redirect: IPv4 port 53 -> 127.0.0.1:$DOH_PORT (nftables)"
            if doh_v6_loopback && doh_nft_redirect ip6; then
                DOH_V6=1
                mlog ctl INFO "DNS redirect: IPv6 port 53 -> [::1]:$DOH_PORT (nftables)"
            fi
            return 0
        fi
        nft delete table ip "$DOH_NFT_TABLE" >/dev/null 2>&1
    fi
    IPT=$(doh_ipt_bin) || return 1
    doh_ipt_redirect "$IPT" || return 1
    mlog ctl INFO "DNS redirect: IPv4 port 53 -> 127.0.0.1:$DOH_PORT (iptables)"
    if doh_v6_loopback && command -v ip6tables >/dev/null 2>&1 && doh_ipt_redirect ip6tables; then
        DOH_V6=1
        mlog ctl INFO "DNS redirect: IPv6 port 53 -> [::1]:$DOH_PORT (iptables)"
    fi
    return 0
}

doh_rules_remove() {
    nft delete table ip "$DOH_NFT_TABLE" >/dev/null 2>&1
    nft delete table ip6 "$DOH_NFT_TABLE" >/dev/null 2>&1
    for IPT in iptables-legacy iptables ip6tables; do
        command -v "$IPT" >/dev/null 2>&1 || continue
        n=0
        while [ "$n" -lt 5 ] && $IPT -w 5 -t nat -D OUTPUT -j "$DOH_IPT_CHAIN" 2>/dev/null; do n=$((n + 1)); done
        $IPT -w 5 -t nat -F "$DOH_IPT_CHAIN" 2>/dev/null
        $IPT -w 5 -t nat -X "$DOH_IPT_CHAIN" 2>/dev/null
    done
    return 0
}

doh_redirect_active() {
    if nft list table ip "$DOH_NFT_TABLE" >/dev/null 2>&1; then echo 1; return; fi
    if IPT=$(doh_ipt_bin) && $IPT -w 5 -t nat -S "$DOH_IPT_CHAIN" >/dev/null 2>&1; then echo 1; return; fi
    echo 0
}

doh_queries() {
    n=0
    if nft list table ip "$DOH_NFT_TABLE" >/dev/null 2>&1; then
        n=$(nft list table ip "$DOH_NFT_TABLE" 2>/dev/null | sed -n 's/.*counter packets \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
    elif IPT=$(doh_ipt_bin); then
        n=$($IPT -w 5 -t nat -L "$DOH_IPT_CHAIN" -v -n -x 2>/dev/null | awk 'NR>2{s+=$1} END{print s+0}')
    fi
    echo "${n:-0}"
}

doh_normalize() {
    out=""
    for id in $(echo "$1" | tr ',' ' '); do
        case "$id" in
            adguard) id=adguard-94-140-14-14-dns-adguard-dns-com ;;
            controld-ads) id=controld-76-76-2-11-freedns-controld-com-p2 ;;
            controld-uncensored) id=controld-76-76-2-11-freedns-controld-com-uncensored ;;
            cloudflare-security) id=cloudflare-security-1-0-0-2-1-0-0-2 ;;
            quad9) id=quad9-9-9-9-9-dns9-quad9-net-443 ;;
            cloudflare) id=cloudflare-1-0-0-1-dns-cloudflare-com ;;
        esac
        out="$out${out:+ }$id"
    done
    echo "$out"
}

doh_render_toml() {
    mkdir -p "$DOH_DIR" || return 1
    names=""
    statics=""
    for id in $(echo "$1" | tr ',' ' '); do
        stamp=$(awk -F'|' -v i="$id" '$1==i {print $5; exit}' "$DOH_STAMPS" 2>/dev/null)
        [ -n "$stamp" ] || { mlog ctl WARN "unknown DoH provider skipped: $id"; continue; }
        names="$names${names:+, }'$id'"
        statics="$statics[static.'$id']
stamp = '$stamp'
"
    done
    [ -n "$names" ] || return 1
    if [ "$2" = 1 ]; then
        listen="'127.0.0.1:$DOH_PORT', '[::1]:$DOH_PORT'"
    else
        listen="'127.0.0.1:$DOH_PORT'"
    fi
    {
        echo "listen_addresses = [$listen]"
        echo "server_names = [$names]"
        echo "ipv4_servers = true"
        echo "ipv6_servers = true"
        echo "dnscrypt_servers = false"
        echo "doh_servers = true"
        echo "require_dnssec = false"
        echo "require_nolog = false"
        echo "require_nofilter = false"
        echo "ignore_system_dns = true"
        echo "lb_strategy = 'p2'"
        echo "lb_estimator = true"
        echo "cache = true"
        echo "timeout = 5000"
        echo "log_level = 1"
        echo "use_syslog = false"
        echo ""
        printf '%s' "$statics"
    } > "$DOH_TOML"
}

doh_start_proxy() {
    doh_render_toml "$1" "$2" || return 2
    : > "$DOH_LOG"
    SSL_CERT_DIR=/system/etc/security/cacerts:/apex/com.android.conscrypt/cacerts \
        nohup "$DOH_BIN" -config "$DOH_TOML" >>"$DOH_LOG" 2>&1 </dev/null &
    pid=$!
    echo "$pid" > "$DOH_PID"
    sleep 2
    kill -0 "$pid" 2>/dev/null
}

doh_wait_ready() {
    n=0
    while [ "$n" -lt 20 ]; do
        doh_running || return 1
        if grep -Eqi 'is ready|lowest initial latency|live servers: [1-9]' "$DOH_LOG" 2>/dev/null; then
            return 0
        fi
        sleep 1
        n=$((n + 1))
    done
    return 1
}

doh_save_private_dns() {
    mkrun
    {
        echo "mode=$(settings get global private_dns_mode 2>/dev/null)"
        echo "host=$(settings get global private_dns_specifier 2>/dev/null)"
    } > "$RUN_DIR/doh-prev-dns"
}

doh_restore_private_dns() {
    [ -f "$RUN_DIR/doh-prev-dns" ] || return 0
    mode=$(sed -n 's/^mode=//p' "$RUN_DIR/doh-prev-dns")
    host=$(sed -n 's/^host=//p' "$RUN_DIR/doh-prev-dns")
    if [ "$mode" = hostname ] && [ -n "$host" ] && [ "$host" != null ]; then
        settings put global private_dns_specifier "$host" >/dev/null 2>&1
        settings put global private_dns_mode hostname >/dev/null 2>&1
    else
        case "$mode" in ""|null) mode=automatic ;; esac
        settings put global private_dns_mode "$mode" >/dev/null 2>&1
    fi
    rm -f "$RUN_DIR/doh-prev-dns"
    mlog ctl INFO "Private DNS restored to ${mode}"
}

cmd_doh_start() {
    rm -f "$RUN_DIR/private-dns.cache"
    [ -x "$DOH_BIN" ] || die "dnscrypt-proxy is not installed: use Components -> dnscrypt-proxy"
    providers=$(doh_normalize "$(conf_get DOH_PROVIDERS)")
    doh_stop_proc
    mkrun
    want6=0
    doh_v6_loopback && want6=1
    if ! doh_start_proxy "$providers" "$want6"; then
        rm -f "$DOH_PID"
        if [ "$want6" = 1 ]; then
            mlog ctl WARN "dnscrypt-proxy did not start with IPv6 loopback; retrying with IPv4 only"
            doh_start_proxy "$providers" 0 || { rm -f "$DOH_PID"; tail -n 3 "$DOH_LOG" 2>/dev/null | while read -r l; do mlog ctl ERROR "dnscrypt-proxy: $l"; done; die "dnscrypt-proxy exited at startup; see the Logs tab (filter Control)"; }
        else
            tail -n 3 "$DOH_LOG" 2>/dev/null | while read -r l; do mlog ctl ERROR "dnscrypt-proxy: $l"; done
            die "dnscrypt-proxy exited at startup; see the Logs tab (filter Control)"
        fi
    fi
    if ! doh_wait_ready; then
        tail -n 5 "$DOH_LOG" 2>/dev/null | while read -r l; do mlog ctl ERROR "dnscrypt-proxy: $l"; done
        doh_stop_proc
        die "DoH proxy has no working upstream (TLS or network); DNS was not changed. See Logs → Control"
    fi
    if ! doh_rules_add; then
        doh_stop_proc
        doh_rules_remove
        die "could not redirect DNS on this kernel (nat/REDIRECT unavailable)"
    fi
    if [ "$(settings get global private_dns_mode 2>/dev/null)" != off ]; then
        doh_save_private_dns
        settings put global private_dns_mode off >/dev/null 2>&1
        mlog ctl INFO "Private DNS turned off so DNS goes through the DoH proxy"
    fi
    doh_publish_ips "$providers"
    nohup sh "$MODDIR/scripts/zapret-ctl.sh" doh-watch >/dev/null 2>&1 </dev/null &
    echo "$!" > "$DOH_WATCH_PID"
    cmd_set DOH_ENABLED 1 >/dev/null
    mlog ctl INFO "DoH proxy started (pid $pid, IPv6 $DOH_V6): $providers"
    ensure_safety
    echo "DoH proxy running on 127.0.0.1:$DOH_PORT"
}

doh_publish_ips() {
    mkrun
    : > "$RUN_DIR/doh-ips.txt"
    for id in $1; do
        awk -F'|' -v i="$id" '$1==i {print $6; exit}' "$DOH_STAMPS" 2>/dev/null >> "$RUN_DIR/doh-ips.txt"
    done
    if sh "$FW" status >/dev/null 2>&1; then
        sh "$FW" restart >/dev/null 2>&1
    fi
}

doh_forget_ips() {
    rm -f "$RUN_DIR/doh-ips.txt"
    if sh "$FW" status >/dev/null 2>&1; then
        sh "$FW" restart >/dev/null 2>&1
    fi
}

cmd_doh_stop() {
    rm -f "$RUN_DIR/private-dns.cache"
    doh_stop_proc
    doh_rules_remove
    doh_forget_ips
    doh_restore_private_dns
    cmd_set DOH_ENABLED 0 >/dev/null 2>&1
    mlog ctl INFO "DoH proxy stopped; DNS redirect removed"
    echo "DoH proxy stopped"
}

cmd_doh_watch() {
    mkrun
    echo "$$" > "$DOH_WATCH_PID"
    while :; do
        sleep 10
        if ! doh_running; then
            mlog ctl ERROR "DoH proxy stopped unexpectedly; DNS redirect removed so DNS keeps working"
            doh_rules_remove
            doh_forget_ips
            doh_restore_private_dns
            rm -f "$DOH_PID" "$DOH_WATCH_PID"
            cmd_set DOH_ENABLED 0 >/dev/null 2>&1
            exit 0
        fi
    done
}

cmd_doh_status() {
    echo "doh_running=$(doh_running && echo 1 || echo 0)"
    echo "doh_enabled=$(conf_get DOH_ENABLED)"
    echo "doh_auto_start=$(conf_get DOH_AUTO_START)"
    echo "doh_providers=$(doh_normalize "$(conf_get DOH_PROVIDERS)")"
    echo "doh_redirect=$(doh_redirect_active)"
    echo "doh_queries=$(doh_queries)"
    echo "doh_binary=$([ -x "$DOH_BIN" ] && echo yes || echo no)"
    echo "doh_version=$(cat "$RUN_DIR/dnscrypt.version" 2>/dev/null)"
    echo "doh_port=$DOH_PORT"
    echo "doh_ipv6=$(doh_v6_loopback && echo 1 || echo 0)"
    echo "doh_errors=$(tail -n 300 "$DOH_LOG" 2>/dev/null | grep -ci 'error\|timeout\|refused\|failed')"
}

dns_doh() {
    u=$(awk -F'|' -v i="$1" '$1==i {print $3; exit}' "$DOH_STAMPS" 2>/dev/null)
    [ -n "$u" ] || return 1
    echo "$u"
}

dns_dot() {
    d=$(awk -F'|' -v i="$1" '$1==i {print $4; exit}' "$DOH_STAMPS" 2>/dev/null)
    [ -n "$d" ] || return 1
    echo "$d"
}

dns_ids_all() {
    awk -F'|' '!/^#/ && NF>=5 {printf "%s ", $1}' "$DOH_STAMPS" 2>/dev/null
}

cmd_dns_providers() {
    awk -F'|' '!/^#/ && NF>=5 {print $1"|"$2"|"$3"|"$4}' "$DOH_STAMPS" 2>/dev/null
}
DNS_JOB_STATE="$RUN_DIR/dns-job.state"
DNS_JOB_OUT="$RUN_DIR/dns-job.out"
DNS_JOB_PID="$RUN_DIR/dns-job.pid"

dns_job_running() {
    [ "$(cat "$DNS_JOB_STATE" 2>/dev/null)" = running ] || return 1
    [ -f "$DNS_JOB_PID" ] && kill -0 "$(cat "$DNS_JOB_PID")" 2>/dev/null
}

dns_job_fail() {
    echo "error" > "$DNS_JOB_STATE"
    echo "$1" > "$DNS_JOB_OUT"
    mlog ctl ERROR "provider test: $1"
    return 1
}

dns_job_work() {
    mode="$1"
    ids=$(echo "$2" | tr ',' ' ')
    [ -n "$ids" ] || ids=$(dns_ids_all)
    echo "running" > "$DNS_JOB_STATE"
    [ -n "$ids" ] || dns_job_fail "no providers are listed in config/doh-providers.txt" || return 1
    ranked=$(dns_ranked "$ids")
    picks=""
    if [ "$mode" = best3 ]; then
        orgs=$(dns_org_map)
        answering=$(echo "$ranked" | awk -F'|' '$3>0 {print $1}')
        [ -n "$answering" ] || dns_job_fail "no provider answered; providers unchanged" || return 1
        used=""
        n=0
        for pass in 1 2; do
            for p in $answering; do
                [ "$n" -ge 3 ] && break
                case " $picks " in *" $p "*) continue ;; esac
                org=$(echo "$orgs" | awk -F'|' -v i="$p" '$1==i {print $2; exit}')
                if [ "$pass" = 1 ]; then
                    case " $used " in *" $org "*) continue ;; esac
                fi
                picks="$picks $p"
                used="$used $org"
                n=$((n + 1))
            done
        done
        picks=$(echo $picks)
        csv=$(echo "$picks" | tr ' ' ',')
        cmd_set DOH_PROVIDERS "$csv" >/dev/null || dns_job_fail "could not save the providers" || return 1
        mlog ctl INFO "Best 3 DoH providers saved: $csv"
    fi
    {
        echo "$ranked" | while IFS='|' read -r id avg ok tot st; do
            [ -n "$id" ] || continue
            pick=0
            case " $picks " in *" $id "*) pick=1 ;; esac
            echo "$id|$avg|$ok|$tot|$st|$pick"
        done
    } > "$DNS_JOB_OUT.tmp"
    mv -f "$DNS_JOB_OUT.tmp" "$DNS_JOB_OUT"
    echo "done" > "$DNS_JOB_STATE"
    if [ "$mode" = best3 ] && doh_running; then
        ( cmd_doh_stop >/dev/null 2>&1; cmd_doh_start >/dev/null 2>&1 ) || mlog ctl WARN "DoH proxy did not restart with the new providers"
    fi
    return 0
}

cmd_dns_job() {
    case "${2:-}" in
        start)
            if dns_job_running; then
                echo "a provider test is already running"
                return 1
            fi
            mode="${3:-test}"
            case "$mode" in test|best3) ;; *) die "mode must be test or best3" ;; esac
            mkrun
            rm -f "$DNS_JOB_OUT"
            echo "running" > "$DNS_JOB_STATE"
            nohup sh "$MODDIR/scripts/zapret-ctl.sh" dns-job run "$mode" "${4:-}" >/dev/null 2>&1 </dev/null &
            echo "$!" > "$DNS_JOB_PID"
            echo "started"
            ;;
        run) dns_job_work "${3:-test}" "${4:-}" ;;
        status)
            st=$(cat "$DNS_JOB_STATE" 2>/dev/null || echo idle)
            if [ "$st" = running ] && [ -f "$DNS_JOB_PID" ] && ! dns_job_running; then
                st=error
                echo "error" > "$DNS_JOB_STATE"
                echo "the provider test stopped unexpectedly" > "$DNS_JOB_OUT"
            fi
            echo "job_state=$st"
            [ -f "$DNS_JOB_OUT" ] && cat "$DNS_JOB_OUT"
            ;;
        *) die "usage: dns-job start test|best3 [ids] | status" ;;
    esac
    return 0
}

cmd_dns_status() {
    mode=$(settings get global private_dns_mode 2>/dev/null)
    host=$(settings get global private_dns_specifier 2>/dev/null)
    echo "private_dns_mode=${mode:-unset}"
    echo "private_dns_host=${host:-}"
    cmd_doh_status
}

DNS_SAMPLES=3
DNS_PROBE_Q="AAABAAABAAAAAAAAA3d3dwdleGFtcGxlA2NvbQAAAQAB"

dns_bench_one() {
    p="$1"
    out="$2"
    url=$(dns_doh "$p") || { echo "$p|0|0|$DNS_SAMPLES|failed" > "$out"; return 0; }
    ok=0
    sum=0
    i=0
    while [ "$i" -lt "$DNS_SAMPLES" ]; do
        i=$((i + 1))
        
        r=$(curl -s -o /dev/null -w '%{http_code} %{time_namelookup} %{time_connect}' \
            --connect-timeout 3 --max-time 5 "$url?dns=$DNS_PROBE_Q" 2>/dev/null)
        set -- $r
        code=${1:-0}
        dns_t=${2:-0}
        conn_t=${3:-0}
        case "$dns_t$conn_t" in *[!0-9.]*) continue ;; esac
        if [ "$code" = 200 ]; then
            ms=$(awk "BEGIN{d=$conn_t-$dns_t; if (d<0) d=0; printf \"%d\", d*1000}" 2>/dev/null)
            ok=$((ok + 1))
            sum=$((sum + ${ms:-0}))
        fi
    done
    avg=0
    [ "$ok" -gt 0 ] && avg=$((sum / ok))
    st=failed
    [ "$ok" -gt 0 ] && st=partial
    [ "$ok" -eq "$DNS_SAMPLES" ] && st=ok
    echo "$p|$avg|$ok|$DNS_SAMPLES|$st" > "$out"
}

dns_bench_all() {
    tmp="$RUN_DIR/dns-test"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    for p in $1; do
        case "$p" in ''|*[!A-Za-z0-9._-]*) continue ;; esac
        dns_bench_one "$p" "$tmp/$p" &
    done
    wait
    for f in "$tmp"/*; do [ -f "$f" ] && cat "$f"; done
    rm -rf "$tmp"
}

dns_ranked() {
    dns_bench_all "$1" | sort -t'|' -k3,3nr -k2,2n
}

dns_org_map() {
    awk -F'|' '!/^#/ && NF>=5 { split($2, a, " "); print $1 "|" a[1] }' "$DOH_STAMPS" 2>/dev/null
}



cmd_dns_apply() {
    rm -f "$RUN_DIR/private-dns.cache"
    name="${2:-}"
    if [ "$name" = off ]; then
        settings put global private_dns_mode off
        mlog ctl INFO "Private DNS turned off"
        echo "Private DNS off"
        return 0
    fi
    host=$(dns_dot "$name") || die "unknown provider: $name"
    if doh_running || [ "$(conf_get DOH_ENABLED)" = 1 ]; then cmd_doh_stop >/dev/null; fi
    settings put global private_dns_specifier "$host" || die "could not set the Private DNS host"
    settings put global private_dns_mode hostname || die "could not enable Private DNS"
    mlog ctl INFO "Private DNS set to $host ($name)"
    echo "Private DNS: $host"
}

cmd_dns_fastest() {
    best=""
    for p in $(dns_ranked "${2:-$(dns_ids_all)}" | awk -F'|' '$3>0 {print $1}'); do
        if dns_dot "$p" >/dev/null 2>&1; then best=$p; break; fi
    done
    [ -n "$best" ] || die "no provider with a Private DNS host answered; Private DNS unchanged"
    cmd_dns_apply x "$best"
}

cmd_backup_export() {
    dest="${2:-/storage/emulated/0/Download/zapret-ksu-backup-$(date +%Y%m%d-%H%M%S).tar.gz}"
    case "$dest" in
        /*) ;;
        *) die "destination must be a full path starting with /" ;;
    esac
    case "$dest" in *..*) die "destination may not contain .." ;; esac
    mkrun
    list=""
    for p in config/zapret.conf config/blockcheck-domains.txt config/blockcheck-lists.txt zapret1/lists zapret2/lists zapret2/presets; do
        [ -e "$MODDIR/$p" ] && list="$list $p"
    done
    [ -n "$list" ] || die "nothing to back up"
    tmp="$RUN_DIR/backup.tar.gz"
    (cd "$MODDIR" && bbx tar -czf "$tmp" $list) || die "could not create the archive"
    mkdir -p "$(dirname "$dest")" && mv -f "$tmp" "$dest" || die "cannot write $dest"
    mlog ctl INFO "backup exported to $dest"
    echo "backup written to $dest ($(wc -c < "$dest") bytes)"
}

cmd_backup_import() {
    src="${2:-}"
    case "$src" in
        /*) ;;
        *) die "give the full path of the backup, starting with /" ;;
    esac
    [ -f "$src" ] || die "no such file: $src"
    [ -L "$src" ] && die "symlinks are not allowed"
    entries=$(bbx tar -tzf "$src" 2>/dev/null) || die "not a valid backup archive"
    [ -n "$entries" ] || die "the backup is empty"
    if bbx tar -tvzf "$src" 2>/dev/null | grep -q '^[lh]'; then
        die "the backup contains links; refusing to restore it"
    fi
    bad=$(echo "$entries" | while IFS= read -r e; do
        case "$e" in
            /*|*..*) echo "$e" ;;
            config/*|zapret1/lists/*|zapret2/lists/*|zapret2/presets/*) ;;
            *) echo "$e" ;;
        esac
    done)
    [ -z "$bad" ] || die "backup contains unexpected paths: $(echo "$bad" | head -n 1)"
    tmpd="$RUN_DIR/restore"
    rm -rf "$tmpd"
    mkdir -p "$tmpd" || die "cannot create $tmpd"
    bbx tar -xzf "$src" -C "$tmpd" || { rm -rf "$tmpd"; die "extraction failed"; }
    if [ -f "$tmpd/config/zapret.conf" ] && ! sh -n "$tmpd/config/zapret.conf"; then
        rm -rf "$tmpd"
        die "the backup's zapret.conf has a syntax error; nothing restored"
    fi
    ts=$(date +%Y%m%d-%H%M%S)
    [ -f "$CONF" ] && cp -f "$CONF" "$CONF.bak-$ts"
    [ -d "$tmpd/config" ] && cp -f "$tmpd/config/"* "$MODDIR/config/" 2>/dev/null
    for d in zapret1/lists zapret2/lists zapret2/presets; do
        if [ -d "$tmpd/$d" ]; then
            mkdir -p "$MODDIR/$d" && cp -rf "$tmpd/$d/." "$MODDIR/$d/"
        fi
    done
    rm -rf "$tmpd"
    mlog ctl INFO "backup restored from $src (previous config kept as zapret.conf.bak-$ts)"
    echo "backup restored; previous config saved as zapret.conf.bak-$ts"
    echo "restart the firewall to apply the restored settings"
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

cmd_bc_report() {
    echo "=== zapret-ksu Blockcheck report ==="
    echo "module_version=$(sed -n 's/^version=//p' "$MODDIR/module.prop")"
    cmd_bc_status
    echo "--- results: name|score|max|http|tls12|tls13|quic|avg_ms|note|dom_ok|dom_total|dns ---"
    bc_results
    echo "--- blockcheck.log ---"
    if [ -f "$RUN_DIR/blockcheck.log" ]; then tail -n 400 "$RUN_DIR/blockcheck.log"; else echo "(no log yet)"; fi
}

bc_results() {
    [ -f "$BC_RESULTS" ] && cat "$BC_RESULTS"
    return 0
}

cmd_snapshot() {
    cmd_status
    echo "---UPDATE---"
    cmd_update_status
    echo "---BLOCKCHECK---"
    cmd_bc_status
    echo "---BCRESULTS---"
    bc_results
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
    apps-icons)    cmd_apps_icons "$@" ;;
    dns-providers) cmd_dns_providers ;;
    latest-versions) cmd_latest_versions ;;
    dns-status)    cmd_dns_status ;;
    dns-job)       cmd_dns_job "$@" ;;
    dns-apply)     cmd_dns_apply "$@" ;;
    dns-fastest)   cmd_dns_fastest "$@" ;;
    doh-start)     cmd_doh_start ;;
    doh-stop)      cmd_doh_stop ;;
    doh-status)    cmd_doh_status ;;
    doh-watch)     cmd_doh_watch ;;
    apps-list)     cmd_apps_list ;;
    backup-export) cmd_backup_export "$@" ;;
    backup-import) cmd_backup_import "$@" ;;
    ipset)         cmd_ipset "${2:-}" "${3:-}" ;;
    fakes)         cmd_fakes "${2:-}" "${3:-}" "${4:-}" ;;
    file)          cmd_file "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    bc-lists)      cmd_bc_lists "${2:-}" "${3:-}" ;;
    bc-available)  cmd_bc_available ;;
    update)        cmd_update "${2:-}" ;;
    blockcheck)    cmd_blockcheck "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    bc-report)     cmd_bc_report ;;
    network-clean) network_clean "${2:-manual}" ;;
    emergency)     cmd_emergency ;;
    safety-loop)   cmd_safety_loop ;;
    bc-results)    bc_results ;;
    log)           if [ "${2:-}" = clear ]; then cmd_log_clear; else cmd_log "${2:-}"; fi ;;
    snapshot)      cmd_snapshot "${2:-}" ;;
    *)             echo "usage: $0 {status|start|stop|restart|dump|set K V|strategies [E]|update N|log [N]|snapshot [N]}" >&2; exit 2 ;;
esac
