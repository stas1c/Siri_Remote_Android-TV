# Magisk installer hook
[ "$API" -lt 33 ] && abort "! Android 13 or newer is required (this device: API $API)"
[ -x /system/bin/uinput ] || ui_print "! /system/bin/uinput missing: keys need this firmware's virtual-search input device"
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/service.sh 0 0 0755
ui_print "- Reboot, then hold Back + Volume Up on the Siri Remote next to the box"
ui_print "  (pairing scan runs for 5 min after boot while no remote is paired)"
