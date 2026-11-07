package com.sony.songpal.localplayer.playbackservice;
import android.content.Context;
import android.content.res.AssetManager;
import android.media.*;
import java.nio.*;
import java.io.*;

/** JNI adapter for the unmodified Music Center 7.6.0 native engine. */
public class PhonePlayer {
 public interface FailureListener{void accept(int error);}
 static { System.loadLibrary("spmedia"); }
 private final java.util.Map<Integer,CodecSource> sources=new java.util.concurrent.ConcurrentHashMap<>(); private AudioTrack track; private ByteBuffer buffer; private boolean initialized;
 private int vptMode=4; private final Context context; private CodecSource preparedSource; private Runnable completion; private FailureListener failure;
 public final PcmSpectrum spectrum=new PcmSpectrum(); public int outputRate=48000,outputChannels=2;
 public byte[] getSpectrum(){return spectrum.get();}
 public volatile java.security.MessageDigest pcmDigest;
 public volatile long bytesWritten; public volatile double energy; public volatile int error;
 public interface JniPhonePlayerListener {
  void onCompletion(); void onCrossfadePrepared(int n); void onError(int n);
  IPlayItemSequence onFetchNext(int n); void onMoveToNext(); void releaseByteBuffer();
  void releaseMediaCodecInterface(int n); ByteBuffer requestByteBuffer(int n);
  MediaCodecSourceInterface requestMediaCodecInterface(); void write(int n);
 }
 private final JniPhonePlayerListener listener=new JniPhonePlayerListener(){
  public void onCompletion(){if(completion!=null)completion.run();} public void onCrossfadePrepared(int n){} public void onError(int n){error=n;if(failure!=null)failure.accept(n);android.util.Log.e("ExtractedEQ","native error "+n);}
  public IPlayItemSequence onFetchNext(int n){return null;} public void onMoveToNext(){}
  public void releaseByteBuffer(){buffer=null;} public void releaseMediaCodecInterface(int n){CodecSource s=sources.remove(n);if(s!=null)s.close();}
  public MediaCodecSourceInterface requestMediaCodecInterface(){CodecSource s=preparedSource;preparedSource=null;if(s==null)s=new CodecSource(context);sources.put(s.getReleaseKey(),s);return s;}
  public ByteBuffer requestByteBuffer(int n){buffer=ByteBuffer.allocateDirect(n);return buffer;}
  public void write(int n){
   if(track==null||buffer==null)return;
   spectrum.update(buffer,n,outputChannels);
   ByteBuffer pcm=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);pcm.position(0);pcm.limit(n);
   double e=0;while(pcm.remaining()>=2){double x=pcm.getShort()/32768.0;e+=x*x;}
   java.security.MessageDigest digest=pcmDigest;if(digest!=null){ByteBuffer copy=buffer.duplicate();copy.position(0);copy.limit(n);digest.update(copy);}
   bytesWritten+=n;energy+=e;
   buffer.position(0);buffer.limit(n);
   while(buffer.hasRemaining()){int result=track.write(buffer,buffer.remaining(),AudioTrack.WRITE_BLOCKING);if(result<=0)break;}
  }
 };
 public PhonePlayer(Context c){context=c;check(nativeInit(c.getAssets()),"init");initialized=true;check(nativeRegisterListener(listener),"listener");nativeSetMaxSamplingRate(48000);nativeSetAudioFormat(16);nativeSetOutputBufferSize(4096);nativeSetSourceDirect(0);nativeSetSoundEffect(1);check(nativeSetVptMode(vptMode),"VPT");}
 private void check(int r,String op){if(r!=0)throw new IllegalStateException(op+": "+r);}
 public void open(String path,CodecSource prepared,int session,Runnable done,FailureListener failed){stop();preparedSource=prepared;if(preparedSource==null){preparedSource=new CodecSource(context);if(!preparedSource.setDataSource(path))throw new IllegalStateException("Cannot prepare stream");}completion=done;failure=failed;error=0;nativeSetMaxSamplingRate(48000);nativeSetAudioFormat(16);check(nativeSetVptMode(vptMode),"VPT");File token=new File(context.getFilesDir(),"sony-codec-input.wma");try{if(!token.exists())try(FileOutputStream out=new FileOutputStream(token)){out.write(0);}}catch(IOException e){throw new IllegalStateException(e);}/* Format 3 selects Sony's MediaCodec fallback. Its file guard needs a local token; the bound source retains the real URI. */check(nativeSetDataSource(token.getPath(),3),"codec source");int rate=nativeGetSamplingRate();if(rate<=0)throw new IllegalStateException("Invalid sample rate: "+rate);outputRate=rate;outputChannels=nativeGetChannels();int mask=outputChannels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;int size=Math.max(16384,AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT));track=new AudioTrack(AudioManager.STREAM_MUSIC,rate,mask,AudioFormat.ENCODING_PCM_16BIT,size,AudioTrack.MODE_STREAM,session);if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("AudioTrack");android.util.Log.i("SonyPlayer","Original Sony DSP active rate="+rate+" session="+session);}
 public int position(){return nativeGetCurrentPosition();} public int duration(){return nativeGetDuration();}
 public void seek(int ms){check(nativeSeekTo(ms),"seek");if(track!=null)track.flush();}
 public void volume(float left,float right){if(track!=null)track.setStereoVolume(left,right);}
 private native int nativeGetCurrentPosition();private native int nativeGetDuration();private native int nativeSeekTo(int ms);
 public void play(){if(track==null)return;track.play();check(nativePlay(),"play");}
 public void pause(){nativePause();if(track!=null)track.pause();}
 public void stop(){if(!initialized)return;nativeReset();if(track!=null){track.pause();track.flush();track.release();track=null;}}
 public void eq(int[] bands,boolean enabled){check(nativeSetEqParam(enabled?bands:new int[6]),"EQ");check(nativeSetSoundEffect(1),"effect");}
 public void vpt(int mode){if(mode!=0&&mode!=1&&mode!=3&&mode!=4)throw new IllegalArgumentException("Modo VPT inválido");check(nativeSetVptMode(mode),"VPT");vptMode=mode;}
 public void close(){if(initialized){stop();nativeUnregisterListener();nativeExit();initialized=false;}}
 private native int nativeSetVptMode(int mode);
 private native int nativeInit(AssetManager a);private native int nativeExit();private native int nativeRegisterListener(JniPhonePlayerListener l);private native int nativeUnregisterListener();
 private native int nativeSetDataSource(String p,int format);private native int nativeGetSamplingRate();private native int nativeGetChannels();private native int nativeSetAudioFormat(int n);private native int nativeSetMaxSamplingRate(int n);private native int nativeSetOutputBufferSize(int n);private native int nativeSetSourceDirect(int n);private native int nativeSetSoundEffect(int n);private native int nativeSetEqParam(int[] b);private native int nativePlay();private native int nativePause();private native int nativeReset();
}
