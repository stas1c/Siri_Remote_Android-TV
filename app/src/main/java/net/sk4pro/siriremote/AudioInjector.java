package net.sk4pro.siriremote;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Plays the decoded voice into the Assistant's own recording through Android's audio-policy injection: while the
 * policy is registered, recordings with the VOICE_RECOGNITION preset read this track instead of the box's microphone
 * (on Amlogic builds an empty placeholder, which only Nanosic remotes feed). Some Assistant builds ignore the
 * AudioStream pipe and record instead, so this is what makes voice work there. The audio-policy classes are system
 * APIs missing from the public SDK, hence reflection; MODIFY_AUDIO_ROUTING comes from privapp-permissions.
 */
final class AudioInjector {
    static final String T = BridgeService.T;
    static final int RATE = 16000;
    static final long WAIT_RECORDER_MS = 3000;   // the Assistant opens its recorder ~0.6 s after launch
    static final int TAIL_MS = 500;              // silence after the last frame, so the end-pointer ends the query
    // the remote's decoded speech is hot (peaks at full scale, RMS up to ~18000): bring it down 12 dB to normal levels
    static final int GAIN_SHIFT = 2;

    final AudioManager am;
    final Class<?> policyClass;
    final Object policy;
    final AudioTrack track;

    private AudioInjector(AudioManager am, Class<?> policyClass, Object policy, AudioTrack track) {
        this.am = am; this.policyClass = policyClass; this.policy = policy; this.track = track;
    }

    /** Registers the injection; call before launching the Assistant so its recorder is routed to us. */
    static AudioInjector start(Context ctx) throws Exception {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilder = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        AudioAttributes.Builder ab = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class)
            .invoke(ab, MediaRecorder.AudioSource.VOICE_RECOGNITION);
        Object rb = ruleBuilder.getConstructor().newInstance();
        ruleBuilder.getMethod("setTargetMixRole", int.class).invoke(rb, ruleClass.getField("MIX_ROLE_INJECTOR").getInt(null));
        ruleBuilder.getMethod("addMixRule", int.class, Object.class)
            .invoke(rb, ruleClass.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null), ab.build());
        Object rule = ruleBuilder.getMethod("build").invoke(rb);

        Object mb = mixBuilder.getConstructor(ruleClass).newInstance(rule);
        mixBuilder.getMethod("setFormat", AudioFormat.class).invoke(mb, new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build());
        mixBuilder.getMethod("setRouteFlags", int.class).invoke(mb, mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
        Object mix = mixBuilder.getMethod("build").invoke(mb);

        Object pb = policyBuilder.getConstructor(Context.class).newInstance(ctx);
        policyBuilder.getMethod("addMix", mixClass).invoke(pb, mix);
        Object policy = policyBuilder.getMethod("build").invoke(pb);
        int res = (int) AudioManager.class.getMethod("registerAudioPolicy", policyClass).invoke(am, policy);
        if (res != 0) throw new IllegalStateException("registerAudioPolicy returned " + res);
        AudioTrack track;
        try {
            track = (AudioTrack) policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix);
            track.play();
        } catch (Throwable e) {
            AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(am, policy);
            throw e;
        }
        return new AudioInjector(am, policyClass, policy, track);
    }

    /** Plays the queue until an empty array arrives, then unregisters. Holds the backlog until the Assistant is
     *  recording (anything played earlier is dropped); blocking writes then pace it in real time. */
    void run(LinkedBlockingQueue<byte[]> q) {
        new Thread(() -> {
            long t0 = SystemClock.uptimeMillis(), waited = -1, bytes = 0;
            int peak = 0;
            try {
                long end = t0 + WAIT_RECORDER_MS;
                while (!recording() && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
                waited = SystemClock.uptimeMillis() - t0;
                for (byte[] b; (b = q.take()).length > 0; ) {
                    b = attenuate(b);
                    track.write(b, 0, b.length);
                    bytes += b.length;
                    for (int i = 0; i + 1 < b.length; i += 2) peak = Math.max(peak, Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8))));
                }
                byte[] tail = new byte[RATE * 2 * TAIL_MS / 1000];
                track.write(tail, 0, tail.length);
            } catch (Throwable e) {
                Log.w(T, "voice: injection stopped: " + e);
            } finally {
                stop();
                Log.i(T, "voice: injected " + bytes / 32 + " ms, peak " + peak + ", recorder "
                    + (waited < WAIT_RECORDER_MS ? "after " + waited + " ms" : "not seen"));
            }
        }, "voice-inject").start();
    }

    /** A scaled copy: the same array also feeds the pipe writer. */
    static byte[] attenuate(byte[] pcm) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)) >> GAIN_SHIFT;
            out[i] = (byte) s; out[i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    boolean recording() { return voiceRecording(am); }

    /** Someone (the Assistant, or an app's voice search via SpeechRecognizer) is recording for speech recognition. */
    static boolean voiceRecording(AudioManager am) {
        for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations())
            if (c.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_RECOGNITION) return true;
        return false;
    }

    void stop() {
        try { track.stop(); track.release(); } catch (Throwable ignored) { }
        try { AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(am, policy); }
        catch (Throwable e) { Log.w(T, "voice: unregister failed: " + e); }
    }
}
