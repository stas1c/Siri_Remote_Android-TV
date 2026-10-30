package net.sk4pro.siriremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Apple Siri Remote (silver, 2nd/3rd gen) -> Android keys.
 * The remote publishes an empty HID report map, so Android's own HID host can't use it. After a "magic" write it
 * sends a 16-bit button bitmask as notifications (protocol: github.com/Yanndroid/SiriRemote-Linux, gen-3 branch).
 * Keys come from a virtual "Siri Remote" input device registered through Android's uinput tool in a root shell.
 *
 * Pair:    am start-foreground-service -n net.sk4pro.siriremote/.BridgeService --ez pair true, then hold Back + Volume Up
 *          (also runs for 5 min after boot while no remote is paired)
 * Other:   --es unpair <address>, --ez scan true (log nearby devices)
 * Log:     logcat -s SiriBridge
 */
public class BridgeService extends Service {
    static final String T = "SiriBridge";
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int MAGIC_HANDLE = 0x4e;    // write F0 00 here to start input reports (0x4d in the Linux write-up: Android numbers one higher)
    static final int BUTTON_HANDLE = 0x3a;   // 16-bit little-endian button bitmask (0x39 there)
    static final int AUDIO_HANDLE = 0x36;    // Opus microphone frames, only while Siri is held
    static final int SIRI = 32;
    static final int KEY_MENU = 139;         // Siri tap -> MENU; Siri held past VOICE_HOLD_MS -> voice search
    static final int VOICE_HOLD_MS = 400;
    static final UUID APPLE_SERVICE = UUID.fromString("8341f2b4-c013-4f04-8197-c4cdb42e26dc");   // only Apple remotes have it
    static final android.os.ParcelUuid HID = android.os.ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");
    // bitmask -> Linux key code (Generic.kl turns these into Android keys); 1 = repeats while held,
    // 2 = held exactly as long as the button (Android's own long-press, like a regular remote)
    static final int[][] KEYS = {
        {1, 172, 0},     // TV button   -> KEY_HOMEPAGE (HOME)
        {2, 115, 1},     // Volume +    -> KEY_VOLUMEUP
        {4, 114, 1},     // Volume -    -> KEY_VOLUMEDOWN
        {8, 353, 2},     // pad click   -> KEY_SELECT (DPAD_CENTER)
        {16, 116, 0},    // Power       -> KEY_POWER
        {32, 583, 0},    // Siri        -> KEY_ASSISTANT
        {64, 158, 0},    // Back        -> KEY_BACK
        {128, 113, 0},   // Mute        -> KEY_MUTE
        {256, 164, 0},   // Play/Pause  -> KEY_PLAYPAUSE
        {512, 103, 1},   // ring up     -> KEY_UP
        {1024, 106, 1},  // ring right  -> KEY_RIGHT
        {2048, 108, 1},  // ring down   -> KEY_DOWN
        {4096, 105, 1},  // ring left   -> KEY_LEFT
    };
    // The remote reports a release ~0.5 s after a quick tap, long enough for Android's key repeat (0.4 s) to fire,
    // so every press is sent as a whole tap at once; only a key still held past HOLD_MS is held down in Android.
    static final int HOLD_MS = 600;

    final Handler h = new Handler(Looper.getMainLooper());
    final ArrayDeque<Runnable> ops = new ArrayDeque<>();   // Android GATT runs one operation at a time
    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    SharedPreferences prefs;
    int lastButtons;
    Voice voice;
    boolean isRemote;   // set only after the GATT table proves it is an Apple remote: nothing else gets to press keys
    Process injector;
    OutputStream injectOut;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Siri Remote bridge", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "bridge").setContentTitle("Siri Remote bridge")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        voice = new Voice(this);
        registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getStringExtra("mac") != null)
            prefs.edit().putString("mac", intent.getStringExtra("mac").toUpperCase()).apply();
        if (intent != null && intent.getStringExtra("unpair") != null) { unpair(adapter.getRemoteDevice(intent.getStringExtra("unpair"))); return START_STICKY; }
        if (intent != null && intent.getBooleanExtra("scan", false)) { scan(); return START_STICKY; }
        if (intent != null && intent.getBooleanExtra("pair", false)) { prefs.edit().remove("mac").apply(); pair(); return START_STICKY; }
        String mac = prefs.getString("mac", null);
        if (mac == null) { pair(); return START_STICKY; }
        connect(adapter.getRemoteDevice(mac));
        return START_STICKY;
    }

    // Logs every device close to the box (strong signal), once per address, for 3 min: put the remote in pairing
    // mode (hold Back + Volume Up) next to the box while it runs.
    void scan() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                if (r.getRssi() < -65 || !seen.add(r.getDevice().getAddress())) return;
                Log.i(T, "scan: " + r.getDevice().getAddress() + " rssi=" + r.getRssi() + " name=" + r.getScanRecord().getDeviceName()
                    + " adv=" + hex(r.getScanRecord().getBytes()).replaceAll("(00)+$", ""));
            }
        };
        adapter.getBluetoothLeScanner().startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        h.postDelayed(() -> { adapter.getBluetoothLeScanner().stopScan(cb); Log.i(T, "scan: done"); }, 180_000);
        Log.i(T, "scan: started");
    }

    static void unpair(BluetoothDevice d) {
        try { Log.i(T, "unpair " + d.getAddress() + " -> " + BluetoothDevice.class.getMethod("removeBond").invoke(d)); }
        catch (Exception e) { Log.w(T, "unpair failed: " + e); }
    }

    ScanCallback pairer;
    java.util.ArrayDeque<BluetoothDevice> probes;   // bonded LE devices still to check for Apple's remote service

    // A remote bonded earlier (e.g. app data reset) is reconnected by Android itself and stops advertising, so the
    // pairing scan can't see it: look at the bonded devices too. Non-Apple ones are disconnected again in setup().
    void probeBonded() {
        probes = new java.util.ArrayDeque<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) if (d.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) probes.add(d);
        probeNext();
    }

    void probeNext() {
        if (gatt != null) { gatt.close(); gatt = null; }
        BluetoothDevice d = probes == null ? null : probes.poll();
        if (d == null) { probes = null; return; }
        Log.i(T, "checking bonded " + d.getAddress() + " (" + d.getName() + ")");
        gatt = d.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    // A Siri Remote in pairing mode (hold Back + Volume Up) advertises the HID service plus Apple manufacturer data
    // from a fresh random address each time, so match that signature close to the box and bond what the scan returns.
    void pair() {
        if (pairer != null) return;
        pairer = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                if (pairer == null || r.getRssi() < -70 || r.getScanRecord() == null) return;
                Log.i(T, "pairing with " + r.getDevice().getAddress() + " rssi=" + r.getRssi());
                adapter.getBluetoothLeScanner().stopScan(this); pairer = null;
                pairing = r.getDevice().getAddress();
                if (r.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {   // bonded before (app data was reset)
                    prefs.edit().putString("mac", pairing).apply(); pairing = null; connect(r.getDevice());
                } else r.getDevice().createBond();
            }
        };
        adapter.getBluetoothLeScanner().startScan(java.util.List.of(new android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(HID).setManufacturerData(0x004C, new byte[0]).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), pairer);
        Log.i(T, "no remote yet: hold Back + Volume Up on the Siri Remote next to the box (scanning for 5 min)");
        probeBonded();
        // a scan that never ends competes with Wi-Fi for the shared radio; restart with --ez pair true
        ScanCallback mine = pairer;
        h.postDelayed(() -> { if (pairer == mine) { adapter.getBluetoothLeScanner().stopScan(mine); pairer = null; Log.i(T, "pairing scan stopped"); } }, 300_000);
    }

    String pairing;

    void connect(BluetoothDevice d) {
        if (gatt != null) { gatt.close(); gatt = null; }
        Log.i(T, "connect " + d.getAddress() + " bond=" + d.getBondState());
        if (d.getBondState() == BluetoothDevice.BOND_NONE) { prefs.edit().remove("mac").apply(); pair(); return; }   // bond lost: pair again
        // autoConnect: the remote sleeps when idle and reconnects on the next press
        gatt = d.connectGatt(this, true, cb, BluetoothDevice.TRANSPORT_LE);
    }

    final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            int s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (d == null || !(d.getAddress().equals(pairing) || d.getAddress().equals(prefs.getString("mac", "")))) return;
            Log.i(T, "bond state " + s + " " + d.getAddress());
            if (s == BluetoothDevice.BOND_BONDED) { prefs.edit().putString("mac", d.getAddress()).apply(); pairing = null; connect(d); }
            if (s == BluetoothDevice.BOND_NONE && i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING)
                h.postDelayed(() -> { if (prefs.getString("mac", null) == null) pair(); }, 2000);
        }
    };

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            Log.i(T, "connection state=" + state + " status=" + status);
            h.post(() -> {
                ops.clear();
                if (state == BluetoothGatt.STATE_CONNECTED) { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); g.discoverServices(); }
                else { release(); if (probes != null) probeNext(); }   // a probe that didn't connect: try the next
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> setup(g));
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { Log.i(T, "mtu " + mtu); next(); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(T, "cccd " + h(d.getCharacteristic()) + " status=" + status); next();
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.i(T, "write " + h(c) + " status=" + status); next();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (c.getInstanceId() == BUTTON_HANDLE) Log.d(T, "buttons " + hex(v));   // 0x3e touch and 0x36 microphone streams are too chatty to log
            if (isRemote && c.getInstanceId() == AUDIO_HANDLE) voice.packet(v);
            else if (isRemote && c.getInstanceId() != BUTTON_HANDLE) Log.d(T, "notify " + h(c) + " " + hex(v));
            if (isRemote && c.getInstanceId() == BUTTON_HANDLE && v.length >= 2) {
                int b = (v[0] & 0xff) | (v[1] & 0xff) << 8;
                h.post(() -> buttons(b));
            }
        }
    };

    void setup(BluetoothGatt g) {
        isRemote = g.getService(APPLE_SERVICE) != null;
        if (!isRemote) {
            Log.i(T, g.getDevice().getAddress() + " is not an Apple remote");
            g.disconnect();
            if (probes != null) h.post(this::probeNext);
            return;
        }
        if (prefs.getString("mac", null) == null) {   // found among the bonded devices
            prefs.edit().putString("mac", g.getDevice().getAddress()).apply();
            probes = null;
            if (pairer != null) { adapter.getBluetoothLeScanner().stopScan(pairer); pairer = null; }
            Log.i(T, "remote found among bonded devices: " + g.getDevice().getAddress());
        }
        try { ensureInjector(); } catch (Exception e) { Log.w(T, "injector: " + e); }
        BluetoothGattCharacteristic magic = null;
        for (BluetoothGattService s : g.getServices()) {
            Log.i(T, "service " + s.getUuid());
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                Log.i(T, "  char " + h(c) + " " + c.getUuid() + " props=0x" + Integer.toHexString(c.getProperties()));
                if (c.getInstanceId() == MAGIC_HANDLE) magic = c;
                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                if (d == null) continue;
                // buttons + mic only: the bonded remote remembers subscriptions, and the touch stream (every 15 ms while
                // a thumb rests on the pad) queues the button reports behind it, so switch every other stream off
                boolean on = c.getInstanceId() == BUTTON_HANDLE || c.getInstanceId() == AUDIO_HANDLE
                    || c.getService().getUuid().equals(APPLE_SERVICE);   // Apple's control channel: the mic seems to need it
                g.setCharacteristicNotification(c, on);
                ops.add(() -> g.writeDescriptor(d, on ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
            }
        }
        if (magic == null) Log.w(T, "no characteristic at the magic handle: not the expected remote layout");
        else { BluetoothGattCharacteristic m = magic;
            ops.add(() -> g.writeCharacteristic(m, new byte[]{(byte) 0xF0, 0x00}, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)); }
        ops.addFirst(() -> g.requestMtu(247));
        next();
    }

    void next() {
        h.post(() -> {
            Runnable r = ops.poll();
            if (r != null) r.run();
        });
    }

    int held, presses;   // keys held down in Android; press counter so a stale hold timer can't fire on a later press

    void buttons(int b) {
        int pressed = b & ~lastButtons, released = lastButtons & ~b;
        lastButtons = b;
        ByteBuffer buf = buffer();
        for (int[] k : KEYS) {
            int bit = k[0], code = k[1];
            if ((pressed & bit) != 0 && bit == SIRI) {   // tap or hold: decided on release or after VOICE_HOLD_MS
                voice.arm();
                int press = ++presses;
                h.postDelayed(() -> {
                    if (press != presses || (lastButtons & SIRI) == 0) return;
                    held |= SIRI;   // a hold: the Assistant listens to the remote's mic (fallback: plain Assist key)
                    voice.start(() -> { ByteBuffer x = buffer(); key(x, code, 1); key(x, code, 0); h.post(() -> inject(x)); });
                }, VOICE_HOLD_MS);
            } else if ((released & bit) != 0 && bit == SIRI) {
                if ((held & SIRI) != 0) held &= ~SIRI;
                else { voice.disarm(); key(buf, KEY_MENU, 1); key(buf, KEY_MENU, 0); }   // a tap
            } else if ((pressed & bit) != 0 && k[2] == 2) {
                key(buf, code, 1); held |= bit;   // released below when the button comes up
            } else if ((pressed & bit) != 0) {
                key(buf, code, 1); key(buf, code, 0);
                int press = ++presses;
                if (k[2] == 1) h.postDelayed(() -> {
                    if (press != presses || (lastButtons & bit) == 0) return;
                    held |= bit; ByteBuffer x = buffer(); key(x, code, 1); inject(x);   // Android repeats it from here
                }, HOLD_MS);
            }
            if ((released & bit) != 0 && (held & bit) != 0) { held &= ~bit; key(buf, code, 0); }
        }
        if (buf.position() > 0) inject(buf);
    }

    static ByteBuffer buffer() { return ByteBuffer.allocate(32 * (KEYS.length + 1) * 3).order(ByteOrder.LITTLE_ENDIAN); }

    // Never send a release for a key that isn't down: through uinput it makes the next press of that key vanish.
    static void key(ByteBuffer b, int code, int value) { event(b, 1, code, value); event(b, 0, 0, 0); }

    void release() {   // never leave a key held when the link drops (and a dropped Siri hold is not a Menu tap)
        if ((lastButtons & SIRI) != 0) { lastButtons &= ~SIRI; held &= ~SIRI; voice.disarm(); }
        if (lastButtons != 0) buttons(0);
    }

    // struct input_event as seen by the writing process: 32-bit userspace (this box) uses 32-bit timeval
    static void event(ByteBuffer b, int type, int code, int value) {
        if (Build.SUPPORTED_64_BIT_ABIS.length > 0) b.putLong(0).putLong(0); else b.putInt(0).putInt(0);
        b.putShort((short) type).putShort((short) code).putInt(value);
    }

    // Android's uinput tool (AOSP, Android 10+) registers our own "Siri Remote" input device; without it, fall back to
    // writing raw events into this box's existing virtual-search device
    static final boolean UINPUT = new java.io.File("/system/bin/uinput").exists();
    static final int EVENT_SIZE = Build.SUPPORTED_64_BIT_ABIS.length > 0 ? 24 : 16;

    void inject(ByteBuffer buf) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureInjector();
                if (UINPUT) injectOut.write(json(buf).getBytes());
                else injectOut.write(buf.array(), 0, buf.position());
                injectOut.flush();
                return;
            } catch (Exception e) {
                Log.w(T, "inject failed: " + e);
                injector = null;
            }
        }
    }

    void ensureInjector() throws java.io.IOException {
        if (injector != null && injector.isAlive()) return;
        injector = new ProcessBuilder("su", "-c", UINPUT ? "exec uinput -"
            // one write() per batch of whole events into the virtual-search evdev node
            : "for e in /sys/class/input/event*; do [ \"$(cat $e/device/name)\" = virtual-search ] && exec cat > /dev/input/${e##*/}; done")
            .redirectErrorStream(true).start();
        injectOut = injector.getOutputStream();
        // drain the root shell's output into the log: unread, it fills the pipe and the injector stalls
        java.io.InputStream out = injector.getInputStream();
        new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(out))) {
                for (String l; (l = r.readLine()) != null; ) Log.i(T, "injector: " + l);
            } catch (Exception ignored) { }
            Log.w(T, "injector exited");
        }, "injector-log").start();
        if (!UINPUT) return;
        StringBuilder keys = new StringBuilder();
        for (int[] k : KEYS) keys.append(k[1]).append(',');
        keys.append(KEY_MENU);
        injectOut.write(("{\"id\":1,\"command\":\"register\",\"name\":\"Siri Remote\",\"vid\":1452,\"pid\":789,"
            // numeric UI_SET_EVBIT (100) / UI_SET_KEYBIT (101): Android 14's uinput does not take the string labels
            + "\"bus\":\"bluetooth\",\"configuration\":[{\"type\":100,\"data\":[1]},"
            + "{\"type\":101,\"data\":[" + keys + "]}]}\n").getBytes());
        injectOut.flush();
        Log.i(T, "input device \"Siri Remote\" registered via uinput");
    }

    // the raw events in buf as one uinput "inject" command
    static String json(ByteBuffer buf) {
        ByteBuffer b = ByteBuffer.wrap(buf.array(), 0, buf.position()).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder s = new StringBuilder("{\"id\":1,\"command\":\"inject\",\"events\":[");
        for (int off = 0; off + EVENT_SIZE <= buf.position(); off += EVENT_SIZE) {
            if (off > 0) s.append(',');
            s.append(b.getShort(off + EVENT_SIZE - 8)).append(',').append(b.getShort(off + EVENT_SIZE - 6)).append(',').append(b.getInt(off + EVENT_SIZE - 4));
        }
        return s.append("]}\n").toString();
    }

    static String h(BluetoothGattCharacteristic c) { return "0x" + Integer.toHexString(c.getInstanceId()); }

    static String hex(byte[] v) {
        StringBuilder s = new StringBuilder();
        for (byte x : v) s.append(String.format("%02x", x));
        return s.toString();
    }
}
