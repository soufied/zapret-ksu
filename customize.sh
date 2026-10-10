#!/system/bin/sh

ui_print "- Zapret DPI bypass installer"

if [ "${KSU:-false}" = "true" ]; then
    ROOT_MANAGER="KernelSU"
elif [ "${APATCH:-false}" = "true" ]; then
    ROOT_MANAGER="APatch"
else
    ROOT_MANAGER="Magisk"
fi
ui_print "- Root manager: $ROOT_MANAGER"

if [ "$ARCH" != "arm64" ]; then
    abort "! Unsupported architecture: $ARCH (arm64 required)"
fi

OLD=/data/adb/modules/zapret-ksu
[ -f "$OLD/config/zapret.conf" ] || OLD=/data/adb/modules/zapret
DEF="$MODPATH/config/zapret.conf"
if [ -f "$OLD/config/zapret.conf" ]; then
    ui_print "- Keeping existing configuration"
    cp -f "$DEF" "$TMPDIR/zapret.conf.default"
    cp -f "$OLD/config/zapret.conf" "$DEF"
    while IFS= read -r line; do
        case "$line" in
            [A-Z]*=*)
                key=${line%%=*}
                grep -q "^$key=" "$DEF" || echo "$line" >> "$DEF"
                ;;
        esac
    done < "$TMPDIR/zapret.conf.default"
fi
if [ -d "$OLD/config" ]; then
    for d in lists fakes; do
        [ -d "$OLD/config/$d" ] && cp -rf "$OLD/config/$d" "$MODPATH/config/"
    done
fi

if [ -d "$OLD" ]; then
    ui_print "- Keeping downloaded engines, presets and strategies"
    for d in bin zapret1 zapret2; do
        if [ -d "$OLD/$d" ]; then
            mkdir -p "$MODPATH/$d"
            cp -rf "$OLD/$d/." "$MODPATH/$d/" || ui_print "! could not copy $d"
        fi
    done
    mkdir -p "$MODPATH/run"
    for f in nfqws1.version nfqws2.version presets.version; do
        [ -f "$OLD/run/$f" ] && cp -f "$OLD/run/$f" "$MODPATH/run/$f"
    done
    [ -f "$OLD/config/blockcheck-lists.txt" ] && cp -f "$OLD/config/blockcheck-lists.txt" "$MODPATH/config/"
    [ -f "$OLD/zapret.log" ] && cp -f "$OLD/zapret.log" "$MODPATH/zapret.log"
fi

ui_print "- Setting permissions"
chmod 0755 "$MODPATH/scripts/firewall.sh" "$MODPATH/service.sh" \
           "$MODPATH/uninstall.sh" "$MODPATH/customize.sh"
for b in "$MODPATH"/bin/*; do
    [ -f "$b" ] && chmod 0755 "$b"
done
chmod 0644 "$MODPATH/module.prop" "$MODPATH/config/zapret.conf"

chcon -R u:object_r:system_file:s0 "$MODPATH/bin" 2>/dev/null || true

ui_print "- Done. Reboot to start the service."
