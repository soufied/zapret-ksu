#!/system/bin/sh
MODDIR="${0%/*}/.."
. "$MODDIR/scripts/common.sh"

TMP="$RUN_DIR/fetch"
STATE="$RUN_DIR/update.state"
Z1_DIR="$MODDIR/zapret1"
Z2_DIR="$MODDIR/zapret2"
TARGET="${1:-}"
STEP=""
DONE=""
STARTED="$(date +%s)"
ERRORS=0

ZAPRET1_API="https://api.github.com/repos/bol-van/zapret/releases/latest"
STRAT_API="https://api.github.com/repos/Flowseal/zapret-discord-youtube/commits/main"
STRAT_REPO_URL="https://github.com/Flowseal/zapret-discord-youtube/archive"
ZAPRET2_API="https://api.github.com/repos/bol-van/zapret2/releases/latest"
DNSCRYPT_API="https://api.github.com/repos/DNSCRYPT/dnscrypt-proxy/releases/latest"
TGWS_API="https://api.github.com/repos/valnesfjord/tg-ws-proxy-rs/releases/latest"
ZAPRET2_STRAT_URL="https://git.zapret.moe/zapretdiscordyoutube/zapret2-youtube-discord/archive/main.tar.gz"

write_state() {
    mkrun
    {
        echo "state=$1"
        echo "target=$TARGET"
        echo "step=$2"
        echo "message=$3"
        echo "file=$4"
        echo "done=$DONE"
        echo "started=$STARTED"
        echo "updated=$(date +%s)"
    } > "$STATE.tmp" && mv -f "$STATE.tmp" "$STATE"
}

step() {
    STEP="$1"
    write_state running "$1" "$2" ""
    mlog fetch INFO "$1: $2"
}

step_done() {
    DONE="$DONE $1"
    DONE="${DONE# }"
    mlog fetch INFO "$1: done"
}

fail() {
    ERRORS=$((ERRORS + 1))
    write_state error "$1" "$2" ""
    mlog fetch ERROR "$1: $2"
    return 1
}

mirror_urls() {
    case "$1" in
        https://git.zapret.moe/*)
            rest="${1#https://git.zapret.moe/}"
            echo "$1"
            if [ "$MIRRORS_ENABLED" = 1 ]; then
                for m in $ZAPRET_MIRRORS; do echo "$m/$rest"; done
            fi
            ;;
        https://github.com/*|https://api.github.com/*|https://raw.githubusercontent.com/*)
            echo "$1"
            if [ "$MIRRORS_ENABLED" = 1 ]; then
                for m in $GH_MIRRORS; do echo "$m$1"; done
            fi
            ;;
        *) echo "$1" ;;
    esac
}

dl_one() {
    if command -v curl >/dev/null 2>&1; then
        capath=""
        [ -d /system/etc/security/cacerts ] && capath="--capath /system/etc/security/cacerts"
        curl -fsSL --connect-timeout 6 --max-time 240 --speed-limit 1024 --speed-time 20 $capath -o "$2" "$1"
    elif [ -x "$BB" ]; then
        mlog fetch WARN "$STEP: curl not installed; using BusyBox wget WITHOUT certificate checks"
        "$BB" wget -q -T 8 -O "$2" "$1"
    else
        return 99
    fi
}

dl() {
    write_state running "$STEP" "downloading $(basename "$2")" "$2"
    if ! command -v curl >/dev/null 2>&1 && [ ! -x "$BB" ]; then
        fail "$STEP" "no downloader (curl or busybox wget needed)"
        return 1
    fi
    last_rc=1
    for url in $(mirror_urls "$1"); do
        mlog fetch INFO "$STEP: GET $url"
        dl_one "$url" "$2"
        last_rc=$?
        if [ "$last_rc" -eq 0 ]; then
            [ "$url" != "$1" ] && mlog fetch INFO "$STEP: used mirror $url"
            size=$(wc -c < "$2" 2>/dev/null)
            mlog fetch INFO "$STEP: saved $(basename "$2") ($size bytes)"
            return 0
        fi
        mlog fetch WARN "$STEP: failed (exit $last_rc): $url"
    done
    fail "$STEP" "download failed on all mirrors (last exit $last_rc): $1"
    return 1
}

extract_tgz() {
    mkdir -p "$2" && bbx tar -xzf "$1" -C "$2"
}

is_arm64_elf() {
    magic=$(bbx od -An -t x1 -N 5 "$1" 2>/dev/null | tr -d ' ')
    machine=$(bbx od -An -t x1 -j 18 -N 2 "$1" 2>/dev/null | tr -d ' ')
    [ "$magic" = 7f454c4602 ] && [ "$machine" = b700 ]
}

install_bin() {
    if ! is_arm64_elf "$1"; then
        fail "$STEP" "$2 is not an arm64 ELF binary; not installed"
        return 1
    fi
    mkdir -p "$MODDIR/bin" || return 1
    cp -f "$1" "$MODDIR/bin/$2.new" && chmod 0755 "$MODDIR/bin/$2.new" &&
        mv -f "$MODDIR/bin/$2.new" "$MODDIR/bin/$2" || { fail "$STEP" "cannot install $2"; return 1; }
    mlog fetch INFO "$STEP: installed bin/$2"
}

release_tag() {
    grep -o '"tag_name": *"[^"]*"' "$1" | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/'
}

asset_url() {
    grep -o "\"browser_download_url\": *\"[^\"]*/$2\"" "$1" | head -n 1 | sed 's/.*"\(https:[^"]*\)"$/\1/'
}

fetch_nfqws1() {
    step nfqws1 "reading the latest zapret release"
    dl "$ZAPRET1_API" "$TMP/release1.json" || return 1
    tag=$(release_tag "$TMP/release1.json")
    [ -n "$tag" ] || { fail nfqws1 "no tag_name in release metadata"; return 1; }
    name="zapret-$tag.tar.gz"
    url=$(asset_url "$TMP/release1.json" "$name")
    [ -n "$url" ] || { fail nfqws1 "release $tag has no asset named $name"; return 1; }
    mlog fetch INFO "nfqws1: latest release is $tag"
    dl "$url" "$TMP/z1.tar.gz" || return 1
    verify_archive "$TMP/z1.tar.gz" "$name" "$TMP/release1.json" || return 1
    extract_tgz "$TMP/z1.tar.gz" "$TMP/z1" || { fail nfqws1 "cannot extract archive"; return 1; }
    src=$(bbx find "$TMP/z1" -path '*android-arm64/nfqws' -type f | head -n 1)
    if [ -z "$src" ]; then
        fail nfqws1 "no android-arm64/nfqws in the $tag archive"
        bbx find "$TMP/z1" -name 'nfqws' -type f | head -n 10 | while read -r f; do mlog fetch INFO "nfqws1: found $f"; done
        return 1
    fi
    install_bin "$src" nfqws || return 1
    mkrun && echo "$tag" > "$RUN_DIR/nfqws1.version"
    step_done nfqws1
}

asset_digest() {
    grep -A 40 "\"name\": *\"$2\"" "$1" | grep -o '"digest": *"sha256:[0-9a-fA-F]\{64\}"' | head -n 1 | grep -o '[0-9a-fA-F]\{64\}' | tr 'A-F' 'a-f'
}

verify_archive() {
    actual=$(bbx sha256sum "$1" | cut -c1-64)
    json="${3:-$TMP/release.json}"
    digest=$(asset_digest "$json" "$2")
    if [ -n "$digest" ]; then
        if [ "$digest" = "$actual" ]; then
            mlog fetch INFO "$STEP: $2 matches the GitHub asset digest"
            return 0
        fi
        fail "$STEP" "$2 does not match its GitHub digest (expected $digest, got $actual)"
        return 1
    fi
    if [ -f "$TMP/sha256sum.txt" ]; then
        expected=$(grep -F "$2" "$TMP/sha256sum.txt" | grep -o '[0-9a-fA-F]\{64\}' | head -n 1 | tr 'A-F' 'a-f')
        if [ -n "$expected" ]; then
            if [ "$expected" = "$actual" ]; then
                mlog fetch INFO "$STEP: $2 matches sha256sum.txt"
                return 0
            fi
            fail "$STEP" "$2 does not match sha256sum.txt (expected $expected, got $actual)"
            return 1
        fi
        mlog fetch WARN "$STEP: sha256sum.txt has no line naming $2; lines mentioning tar.gz or zip:"
        grep -F -e 'tar.gz' -e 'zip' "$TMP/sha256sum.txt" | head -n 6 | while read -r l; do mlog fetch WARN "$STEP:   $l"; done
    fi
    fail "$STEP" "no checksum available for $2; refusing to install an unverified archive"
    return 1
}

extract_any() {
    mkdir -p "$2" || return 1
    case "$1" in
        *.zip) bbx unzip -q -o "$1" -d "$2" ;;
        *)     bbx tar -xzf "$1" -C "$2" ;;
    esac
}

fetch_nfqws2() {
    step nfqws2 "reading latest zapret2 release"
    dl "$ZAPRET2_API" "$TMP/release.json" || return 1
    tag=$(release_tag "$TMP/release.json")
    [ -n "$tag" ] || { fail nfqws2 "no tag_name in release metadata"; return 1; }
    mlog fetch INFO "nfqws2: latest release is $tag"

    sums_url=$(asset_url "$TMP/release.json" "sha256sum.txt")
    if [ -n "$sums_url" ]; then
        dl "$sums_url" "$TMP/sha256sum.txt" || mlog fetch WARN "nfqws2: could not fetch sha256sum.txt; relying on the API digest"
    fi

    found=""
    for ext in tar.gz zip; do
        name="zapret2-$tag.$ext"
        url=$(asset_url "$TMP/release.json" "$name")
        [ -n "$url" ] || { mlog fetch INFO "nfqws2: release has no $name"; continue; }
        step nfqws2 "downloading $name"
        dl "$url" "$TMP/$name" || return 1
        verify_archive "$TMP/$name" "$name" || return 1
        step nfqws2 "extracting $name"
        extract_any "$TMP/$name" "$TMP/z2-$ext" || { fail nfqws2 "cannot extract $name"; return 1; }
        src=$(bbx find "$TMP/z2-$ext" -path '*android-arm64/nfqws2' -type f | head -n 1)
        if [ -n "$src" ]; then
            install_bin "$src" nfqws2 || return 1
            found=1
            break
        fi
        mlog fetch INFO "nfqws2: $name has no android-arm64/nfqws2; trying the next archive"
        bbx find "$TMP/z2-$ext" -name 'nfqws2*' -type f | head -n 10 | while read -r f; do mlog fetch INFO "nfqws2:   contains $f"; done
    done
    [ -n "$found" ] || { fail nfqws2 "no archive for $tag contains android-arm64/nfqws2"; return 1; }
    mkrun && echo "$tag" > "$RUN_DIR/nfqws2.version"
    step_done nfqws2
}

fetch_presets() {
    step presets "downloading zapret2 presets"
    dl "$ZAPRET2_STRAT_URL" "$TMP/presets.tar.gz" || return 1
    extract_tgz "$TMP/presets.tar.gz" "$TMP/p" || { fail presets "cannot extract archive"; return 1; }
    presets_dir=$(bbx find "$TMP/p" -type d -name presets | head -n 1)
    [ -n "$presets_dir" ] || { fail presets "no presets directory in archive"; return 1; }
    root=$(dirname "$presets_dir")
    mkdir -p "$Z2_DIR" || { fail presets "cannot create zapret2 directory"; return 1; }
    rm -rf "$Z2_DIR/presets" && cp -r "$root/presets" "$Z2_DIR/" || { fail presets "cannot install presets"; return 1; }
    mlog fetch INFO "presets: installed $(ls "$Z2_DIR/presets" | wc -l) files"
    for d in lua lists bin; do
        [ -d "$root/$d" ] || continue
        mkdir -p "$Z2_DIR/$d" && cp -r "$root/$d/." "$Z2_DIR/$d/" || { fail presets "cannot install $d"; return 1; }
        mlog fetch INFO "presets: installed $d/"
    done
    mkrun && date '+%Y-%m-%d' > "$RUN_DIR/presets.version"
    step_done presets
}

fetch_strategies() {
    step strategies "reading the latest strategy commit"
    dl "$STRAT_API" "$TMP/strat.json" || return 1
    sha=$(grep -o '"sha": *"[0-9a-f]\{40\}"' "$TMP/strat.json" | head -n 1 | grep -o '[0-9a-f]\{40\}')
    [ -n "$sha" ] || { fail strategies "no commit id in the repository metadata"; return 1; }
    short=$(echo "$sha" | cut -c1-7)
    mlog fetch INFO "strategies: latest commit on main is $short"
    dl "$STRAT_REPO_URL/$sha.zip" "$TMP/strategies.zip" || return 1
    mkdir -p "$TMP/s" && bbx unzip -q -o "$TMP/strategies.zip" -d "$TMP/s" || { fail strategies "cannot extract strategies"; return 1; }
    bat=$(bbx find "$TMP/s" -maxdepth 3 -name '*.bat' | head -n 1)
    [ -n "$bat" ] || { fail strategies "no .bat strategies in archive"; return 1; }
    root=$(dirname "$bat")
    mkdir -p "$Z1_DIR" && cp -r "$root/." "$Z1_DIR/" || { fail strategies "cannot install strategies"; return 1; }
    mkrun && echo "$short" > "$RUN_DIR/strategies.version"
    mlog fetch INFO "strategies: installed $(ls "$Z1_DIR"/*.bat 2>/dev/null | wc -l) .bat files"
    step_done strategies
}

persist_save() {
    P=/data/adb/zapret-ksu-data
    mkdir -p "$P/bin" "$P/run" 2>/dev/null || return 0
    for d in bin zapret1 zapret2; do
        [ -d "$MODDIR/$d" ] && cp -rf "$MODDIR/$d/." "$P/$d/" 2>/dev/null
    done
    for f in "$RUN_DIR"/*.version; do
        [ -f "$f" ] && cp -f "$f" "$P/run/" 2>/dev/null
    done
    mlog fetch INFO "downloads saved to $P (kept across module updates)"
}

mkrun
mkdir -p "$TMP" || { echo "cannot create $TMP" >&2; exit 1; }

fetch_dnscrypt() {
    step dnscrypt "reading the latest dnscrypt-proxy release"
    dl "$DNSCRYPT_API" "$TMP/release-dc.json" || return 1
    tag=$(release_tag "$TMP/release-dc.json")
    [ -n "$tag" ] || { fail dnscrypt "no tag_name in release metadata"; return 1; }
    name="dnscrypt-proxy-linux_arm64-$tag.tar.gz"
    url=$(asset_url "$TMP/release-dc.json" "$name")
    [ -n "$url" ] || { fail dnscrypt "release $tag has no asset named $name"; return 1; }
    mlog fetch INFO "dnscrypt: latest release is $tag"
    dl "$url" "$TMP/dc.tar.gz" || return 1
    verify_archive "$TMP/dc.tar.gz" "$name" "$TMP/release-dc.json" || return 1
    extract_tgz "$TMP/dc.tar.gz" "$TMP/dc" || { fail dnscrypt "cannot extract archive"; return 1; }
    src=$(bbx find "$TMP/dc" -name dnscrypt-proxy -type f | head -n 1)
    [ -n "$src" ] || { fail dnscrypt "no dnscrypt-proxy binary in the $tag archive"; return 1; }
    install_bin "$src" dnscrypt-proxy || return 1
    mkrun && echo "$tag" > "$RUN_DIR/dnscrypt.version"
    step_done dnscrypt
}

fetch_tgws() {
    step tgws "reading the latest tg-ws-proxy release"
    dl "$TGWS_API" "$TMP/release-tg.json" || return 1
    tag=$(release_tag "$TMP/release-tg.json")
    [ -n "$tag" ] || { fail tgws "no tag_name in release metadata"; return 1; }
    name="tg-ws-proxy-aarch64-unknown-linux-musl.tar.gz"
    url=$(asset_url "$TMP/release-tg.json" "$name")
    [ -n "$url" ] || { fail tgws "release $tag has no asset named $name"; return 1; }
    mlog fetch INFO "tgws: latest release is $tag"
    dl "$url" "$TMP/tg.tar.gz" || return 1
    verify_archive "$TMP/tg.tar.gz" "$name" "$TMP/release-tg.json" || return 1
    extract_tgz "$TMP/tg.tar.gz" "$TMP/tg" || { fail tgws "cannot extract archive"; return 1; }
    src=$(bbx find "$TMP/tg" -name tg-ws-proxy -type f | head -n 1)
    [ -n "$src" ] || { fail tgws "no tg-ws-proxy binary in the $tag archive"; return 1; }
    install_bin "$src" tg-ws-proxy || return 1
    mkrun && echo "$tag" > "$RUN_DIR/tgws.version"
    step_done tgws
}

run() {
    "$1" || mlog fetch WARN "$1 did not complete"
}

case "$TARGET" in
    nfqws1)     run fetch_nfqws1 ;;
    nfqws2)     run fetch_nfqws2 ;;
    presets)    run fetch_presets ;;
    strategies) run fetch_strategies ;;
    dnscrypt)   run fetch_dnscrypt ;;
    tgws)       run fetch_tgws ;;
    all)
        run fetch_nfqws1
        run fetch_nfqws2
        run fetch_presets
        run fetch_strategies
        run fetch_dnscrypt
        run fetch_tgws
        ;;
    *)
        echo "usage: $0 {nfqws1|nfqws2|presets|strategies|dnscrypt|tgws|all}" >&2
        exit 2
        ;;
esac

rm -rf "$TMP"
if [ "$ERRORS" -eq 0 ]; then
    persist_save
    write_state ok "$TARGET" "update complete" ""
    mlog fetch INFO "update $TARGET complete in $(( $(date +%s) - STARTED )) s"
    exit 0
fi
write_state error "$TARGET" "$ERRORS step(s) failed; see zapret.log" ""
mlog fetch ERROR "update $TARGET finished with $ERRORS error(s)"
exit 1
