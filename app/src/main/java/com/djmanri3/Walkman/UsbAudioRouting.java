package com.djmanri3.Walkman;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Prefers an Android USB audio sink for one track and follows USB hotplug. */
public final class UsbAudioRouting implements AutoCloseable {
    private final AudioManager manager;
    private final AudioTrack track;
    private boolean closed;
    private boolean registered;
    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refresh(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refresh(); }
    };

    public UsbAudioRouting(Context context, AudioTrack track) {
        this.track = track;
        manager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        try {
            manager.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
            registered = true;
        } catch (RuntimeException e) {
            Log.w("UsbAudio", "USB hotplug notifications unavailable", e);
        }
        refresh();
    }

    public static boolean isUsbOutput(AudioDeviceInfo device) {
        if (device == null || !device.isSink()) return false;
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                || type == AudioDeviceInfo.TYPE_USB_HEADSET;
    }

    private synchronized void refresh() {
        if (closed || manager == null) return;
        try {
            AudioDeviceInfo current = track.getPreferredDevice();
            AudioDeviceInfo selected = null;
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!isUsbOutput(device)) continue;
                if (selected == null) selected = device;
                // Keep the same DAC if a second USB audio output appears.
                if (current != null && current.getId() == device.getId()) {
                    selected = device;
                    break;
                }
            }
            if (selected == null && current == null) return;
            if (selected != null && current != null && selected.getId() == current.getId()) return;
            if (!track.setPreferredDevice(selected)) {
                // A device can disappear between enumeration and routing.
                track.setPreferredDevice(null);
                Log.w("UsbAudio", "USB output unavailable; restored Android audio routing");
            }
        } catch (RuntimeException e) {
            Log.w("UsbAudio", "Could not update the USB audio route", e);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (registered) {
            manager.unregisterAudioDeviceCallback(callback);
            registered = false;
        }
    }
}
