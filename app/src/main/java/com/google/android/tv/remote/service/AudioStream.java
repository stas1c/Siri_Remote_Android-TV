package com.google.android.tv.remote.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/**
 * Wire-compatible stand-in for the Assistant's own AudioStream parcelable (Google TV remote service API): the class
 * name is what the Assistant looks up to unparcel it, the field order is what its CREATOR reads. An activity intent may
 * not carry file descriptors, so the pipe goes "indirect": the parcel holds a binder the Assistant calls to get it.
 */
public class AudioStream implements Parcelable {
    static final String INDIRECT = "com.google.android.tv.remote.service.IndirectParcelFileDescriptor";
    final IBinder indirect;
    final int channels, bitsPerSample, sampleRate;

    public AudioStream(ParcelFileDescriptor fd, int channels, int bitsPerSample, int sampleRate) {
        this.indirect = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 1) return false;   // 1 = getAudioStream
                data.enforceInterface(INDIRECT);
                reply.writeNoException();
                reply.writeInt(1);
                fd.writeToParcel(reply, 0);
                return true;
            }
        };
        this.channels = channels; this.bitsPerSample = bitsPerSample; this.sampleRate = sampleRate;
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel p, int flags) {
        p.writeTypedObject(null, flags);   // no direct fd
        p.writeInt(channels);
        p.writeInt(bitsPerSample);
        p.writeInt(sampleRate);
        p.writeInt(0);
        p.writeStrongBinder(indirect);
        p.writeInt(0);                     // interaction model
    }

    public static final Creator<AudioStream> CREATOR = new Creator<>() {
        @Override public AudioStream createFromParcel(Parcel p) { throw new UnsupportedOperationException("write-only"); }
        @Override public AudioStream[] newArray(int n) { return new AudioStream[n]; }
    };
}
