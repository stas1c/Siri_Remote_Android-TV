#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do pm grant net.sk4pro.siriremote android.permission.$p 2>/dev/null; done
am start-foreground-service -n net.sk4pro.siriremote/.BridgeService >/dev/null 2>&1
