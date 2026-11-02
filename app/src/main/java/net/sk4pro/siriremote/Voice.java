package net.sk4pro.siriremote;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.tv.remote.service.AudioStream;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Siri button -> Google Assistant voice search, the way Google's own TV remote service does it: start the Assistant
 * with an AudioStream (pipe, 16 kHz mono 16-bit) in its session args and write the remote's decoded microphone audio
 * into the pipe; closing the pipe ends the query. Assistant builds that ignore the pipe and record instead get the
 * same audio through AudioInjector. The remote streams one Opus frame per notification:
 * [4-byte header][length][Opus frame].
 */
class Voice {
    static final String T = BridgeService.T;
    static final long IDLE_MS = 700;        // the remote stops sending when Siri is released: end the query
    static final long NO_AUDIO_MS = 8000;   // Siri pressed but no audio ever arrived
    // every session starts with a near full-scale click from the mic switching on; the Assistant's end-pointer takes it
    // for speech and the silence after it for the end of the sentence, so drop the first frames
    static final int SKIP_FRAMES = 8;   // 8 x 20 ms

    final Context ctx;
    final HandlerThread thread = new HandlerThread("voice");
    final Handler h;
    MediaCodec opus;
    LinkedBlockingQueue<byte[]> queue;   // decoded 16 kHz PCM for the pipe writer thread
    LinkedBlockingQueue<byte[]> injectQueue;   // the same PCM for AudioInjector; null if injection is unavailable
    ParcelFileDescriptor writeEnd, readEnd;
    int frames;
    int decoderRate;   // the decoder's real output rate, from its output-format event
    java.util.ArrayList<byte[]> early;   // audio sent while it's not yet known whether Siri is a tap or a hold
    boolean gotAudio;

    Voice(Context c) { ctx = c; thread.start(); h = new Handler(thread.getLooper()); }

    /** Siri went down: keep the remote's audio until it's known whether this is a hold (voice) or a tap. */
    void arm() { h.post(() -> early = new java.util.ArrayList<>()); }

    /** Siri was only tapped: forget the audio. */
    void disarm() { h.post(() -> early = null); }

    /** Siri held: open the Assistant listening to our pipe (or, if an app's own voice search is already listening, feed
     *  that), starting with the audio kept since the press; failed runs if that could not start. */
    void start(Runnable failed) {
        h.post(() -> {
            java.util.List<byte[]> kept = early; early = null;
            if (!open()) { failed.run(); return; }
            if (kept != null) for (byte[] v : kept) feed(v);
        });
    }

    /** One notification from the remote's audio characteristic. */
    void packet(byte[] v) {
        h.post(() -> {
            if (early != null && early.size() < 100) { early.add(v); return; }   // up to 2 s before the hold is decided
            feed(v);
        });
    }

    void feed(byte[] v) {
        {
            if (opus == null || v.length < 6 || v[4] == 0 || 5 + (v[4] & 0xff) > v.length) return;
            if (!gotAudio) { gotAudio = true; Log.i(T, "voice: audio streaming"); }
            h.removeCallbacks(idle);
            h.postDelayed(idle, IDLE_MS);
            if (++frames <= SKIP_FRAMES) return;
            decode(v, 5, v[4] & 0xff);
        }
    }

    final Runnable idle = this::close;

    boolean open() {
        close();
        // an app's voice search (e.g. Nuvio's mic button, Android's SpeechRecognizer) is already recording the box's
        // placeholder mic: feed that instead of opening the Assistant. Checked before our own policy exists.
        boolean appListening = AudioInjector.voiceRecording(ctx.getSystemService(android.media.AudioManager.class));
        try {
            try {   // before the launch, so the Assistant's recorder is routed to the injection when it opens
                AudioInjector inj = AudioInjector.start(ctx);
                injectQueue = new LinkedBlockingQueue<>();
                inj.run(injectQueue);
            } catch (Throwable e) {
                Log.w(T, "voice: injection unavailable: " + (e.getCause() != null ? e.getCause() : e));
                appListening = false;   // nothing else could reach the app: fall back to the Assistant
            }
            if (appListening) Log.i(T, "voice: feeding the app that is listening");
            else {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                Bundle args = new Bundle();
                args.putParcelable("AUDIO_STREAM_KEY", new AudioStream(pipe[0], 1, 16, 16000));
                // ACTION_ASSIST with the session args, as Google's remote service does (SearchManager.launchAssist is
                // a no-op on this TV build); KEY_EVENT=KEYCODE_ASSIST selects the Assistant's "remote mic" path
                ctx.startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("android.intent.extra.KEY_EVENT", 219).putExtra("search_type", 1)
                    .putExtra("android.intent.extra.TIME", SystemClock.uptimeMillis()).putExtra("SHOW_SESSION_ARGS", args));
                Log.i(T, "voice: Assistant launched");
                readEnd = pipe[0];   // handed out through the stream's binder; closed when the query ends
                writeEnd = pipe[1];
                queue = new LinkedBlockingQueue<>();
                startWriter(queue, writeEnd);
            }
            opus = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1);
            f.setByteBuffer("csd-0", opusHead());
            f.setByteBuffer("csd-1", le64(0));                 // codec delay (ns)
            f.setByteBuffer("csd-2", le64(80_000_000L));       // seek pre-roll (ns)
            opus.configure(f, null, null, 0);
            opus.start();
            frames = 0; gotAudio = false; decoderRate = 48000;
            h.postDelayed(idle, NO_AUDIO_MS);
            return true;
        } catch (Throwable e) {
            Log.w(T, "voice: start failed: " + e);
            close();
            return false;
        }
    }

    // Blocking pipe writes run here, so a reader that never reads can't stall the voice thread; close() shuts the
    // pipe, which unblocks it.
    static void startWriter(LinkedBlockingQueue<byte[]> q, ParcelFileDescriptor fd) {
        new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                for (byte[] b; (b = q.take()).length > 0; ) out.write(b);
            } catch (Throwable e) {
                Log.i(T, "voice: pipe closed (" + e.getClass().getSimpleName() + ")");
            }
        }, "voice-pipe").start();
    }

    void decode(byte[] v, int off, int len) {
        try {
            int in = opus.dequeueInputBuffer(10_000);
            if (in >= 0) {
                opus.getInputBuffer(in).put(v, off, len);
                opus.queueInputBuffer(in, 0, len, SystemClock.uptimeMillis() * 1000, 0);
            }
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            for (int out; (out = opus.dequeueOutputBuffer(bi, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER; ) {
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decoderRate = opus.getOutputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    continue;
                }
                if (out < 0) continue;
                ByteBuffer pcm = opus.getOutputBuffer(out).order(ByteOrder.LITTLE_ENDIAN);
                byte[] pcm16 = to16k(pcm, bi.size, Math.max(1, decoderRate / 16000));
                if (queue != null) queue.add(pcm16);   // no pipe when feeding an app's own voice search
                if (injectQueue != null) injectQueue.add(pcm16);
                opus.releaseOutputBuffer(out, false);
            }
        } catch (Throwable e) {
            Log.w(T, "voice: decode failed: " + e);
        }
    }

    // The Assistant wants 16 kHz; Android's Opus decoder reports its output rate (48 kHz on the boxes seen so far) in
    // the output-format event. factor = decoder rate / 16000: average each group of samples (crude low-pass), keep one
    static byte[] to16k(ByteBuffer pcm, int bytes, int factor) {
        int n = bytes / 2 / factor;
        ByteBuffer out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            int sum = 0;
            for (int k = 0; k < factor; k++) sum += pcm.getShort();
            out.putShort((short) (sum / factor));
        }
        return out.array();
    }

    void close() {
        h.removeCallbacks(idle);
        if (opus != null) Log.i(T, "voice: query ended" + (gotAudio ? "" : " (no audio arrived)"));
        try { if (opus != null) { opus.stop(); opus.release(); } } catch (Throwable ignored) { }
        if (queue != null) queue.add(new byte[0]);
        if (injectQueue != null) injectQueue.add(new byte[0]);   // the injector drains, adds a silent tail, unregisters
        injectQueue = null;
        // the writer closes the pipe after draining; if the Assistant isn't reading, force it shut shortly after
        ParcelFileDescriptor w = writeEnd, r = readEnd;
        if (w != null) h.postDelayed(() -> { try { w.close(); r.close(); } catch (Throwable ignored) { } }, 3000);
        opus = null; queue = null; writeEnd = null; readEnd = null;
    }

    static ByteBuffer opusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes()).put((byte) 1).put((byte) 1).putShort((short) 0).putInt(16000).putShort((short) 0).put((byte) 0);
        b.flip();
        return b;
    }

    static ByteBuffer le64(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v);
        b.flip();
        return b;
    }
}
