#!/usr/bin/env python3
"""Exercise the production USB routing logic with simulated Android device callbacks.
This does not emulate USB hardware, AudioFlinger or the DAC driver.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java = Path(os.environ.get('JAVA_HOME', '/home/manri/jdk17/jdk-17.0.13+11')) / 'bin'
stubs = {
 'android/content/Context.java': '''package android.content;
 public class Context { public static final String AUDIO_SERVICE="audio"; public Object service;
 public Context getApplicationContext(){return this;} public Object getSystemService(String name){return service;} }''',
 'android/os/Looper.java': '''package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
 'android/os/Handler.java': '''package android.os; public class Handler { public Handler(Looper looper){} }''',
 'android/util/Log.java': '''package android.util; public class Log { public static int w(String tag,String text){return 0;} public static int w(String tag,String text,Throwable error){return 0;} }''',
 'android/media/AudioDeviceInfo.java': '''package android.media;
 public class AudioDeviceInfo {
 public static final int TYPE_USB_DEVICE=11,TYPE_USB_ACCESSORY=12,TYPE_USB_HEADSET=22;
 final int id,type;final boolean sink;
 public AudioDeviceInfo(int id,int type,boolean sink){this.id=id;this.type=type;this.sink=sink;}
 public boolean isSink(){return sink;} public int getType(){return type;} public int getId(){return id;}
 }''',
 'android/media/AudioDeviceCallback.java': '''package android.media; public class AudioDeviceCallback {
 public void onAudioDevicesAdded(AudioDeviceInfo[] devices){} public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){} }''',
 'android/media/AudioManager.java': '''package android.media;
 import android.os.Handler;
 public class AudioManager {
 public static final int GET_DEVICES_OUTPUTS=2;public AudioDeviceInfo[] devices={};
 public AudioDeviceCallback callback;public boolean registered,failRegistration;
 public AudioDeviceInfo[] getDevices(int flags){return devices;}
 public void registerAudioDeviceCallback(AudioDeviceCallback c,Handler h){if(failRegistration)throw new IllegalStateException();callback=c;registered=true;}
 public void unregisterAudioDeviceCallback(AudioDeviceCallback c){registered=false;}
 public void update(AudioDeviceInfo... outputs){devices=outputs;callback.onAudioDevicesAdded(outputs);callback.onAudioDevicesRemoved(new AudioDeviceInfo[0]);}
 }''',
 'android/media/AudioTrack.java': '''package android.media;
 public class AudioTrack {public AudioDeviceInfo preferred;public int calls;public boolean rejectUsb;
 public AudioDeviceInfo getPreferredDevice(){return preferred;}
 public boolean setPreferredDevice(AudioDeviceInfo device){calls++;if(device!=null&&rejectUsb)return false;preferred=device;return true;}
 }''',
 'UsbAudioRoutingTest.java': '''
 import android.content.Context;import android.media.*;import com.djmanri3.Walkman.UsbAudioRouting;
 public class UsbAudioRoutingTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
 Context context=new Context();AudioManager manager=new AudioManager();context.service=manager;AudioTrack track=new AudioTrack();
 AudioDeviceInfo usb=new AudioDeviceInfo(1,11,true),headset=new AudioDeviceInfo(2,22,true),accessory=new AudioDeviceInfo(3,12,true),mic=new AudioDeviceInfo(4,11,false),speaker=new AudioDeviceInfo(5,2,true);
 UsbAudioRouting route=new UsbAudioRouting(context,track);
 check(track.preferred==null,"No USB: preserve Android default");
 manager.update(mic,speaker);check(track.preferred==null,"Never select USB inputs or speaker as a DAC");
 manager.update(usb);check(track.preferred==usb,"Hotplug selects a USB DAC");
 int calls=track.calls;manager.update(headset,usb);check(track.preferred==usb&&track.calls==calls,"Keep the active DAC when another is connected");
 manager.update(headset);check(track.preferred==headset,"Fall back to another connected USB output");
 manager.update(accessory);check(track.preferred==accessory,"USB accessories are supported");
 manager.update(speaker);check(track.preferred==null,"Unplug restores system routing");
 track.rejectUsb=true;manager.update(usb);check(track.preferred==null,"Rejected or vanished DAC falls back safely");
 route.close();check(!manager.registered,"Close unregisters the device callback");
 calls=track.calls;track.rejectUsb=false;manager.update(headset);check(track.calls==calls,"Queued callbacks cannot access a closed track");route.close();
 AudioManager late=new AudioManager();late.devices=new AudioDeviceInfo[]{usb};context.service=late;
 AudioTrack next=new AudioTrack();UsbAudioRouting second=new UsbAudioRouting(context,next);check(next.preferred==usb,"DAC connected before playback is selected");second.close();
 late.failRegistration=true;AudioTrack fallback=new AudioTrack();UsbAudioRouting third=new UsbAudioRouting(context,fallback);check(fallback.preferred==usb,"Callback registration failure does not prevent playback");third.close();
 System.out.println("PASS: USB preconnection, hotplug, multiple DACs, unplug, rejected route and callback cleanup");
 }
 }'''
}
with tempfile.TemporaryDirectory(prefix='walkman-usb-tests-') as temporary:
    work = Path(temporary)
    for name, source in stubs.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    subprocess.run([str(java/'javac'), '-d', str(work/'classes'),
                    *[str(work/name) for name in stubs],
                    str(root/'app/src/main/java/com/djmanri3/Walkman/UsbAudioRouting.java')], check=True)
    subprocess.run([str(java/'java'), '-cp', str(work/'classes'), 'UsbAudioRoutingTest'], check=True, timeout=10)
