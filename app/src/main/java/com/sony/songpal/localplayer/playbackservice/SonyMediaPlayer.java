package com.sony.songpal.localplayer.playbackservice;

import android.content.*;
import android.database.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/** MediaPlayer contract used by both Walkman ports; audio is rendered by Sony's original engine. */
public class SonyMediaPlayer extends MediaPlayer {
 private static Context app;
 private static PhonePlayer engine;
 private static SonyMediaPlayer owner;
 private static final Object LOCK=new Object();
 private static final Uri SETTINGS=Uri.parse("content://local.extracted.equalizer.sony.settings/state");
 private final Handler handler=new Handler(Looper.myLooper()!=null?Looper.myLooper():Looper.getMainLooper());
 private final ExecutorService loader=Executors.newSingleThreadExecutor();
 private volatile int generation;
 private volatile boolean released, prepared, playing;
 private String source;
 private CodecSource codec;
 private int duration, position;
 private float left=1,right=1;
 private MediaPlayer next;
 private boolean observing;
 private void observe(){if(!observing)try{app.getContentResolver().registerContentObserver(SETTINGS,true,observer);observing=true;}catch(SecurityException ignored){}}
 private OnPreparedListener preparedListener;
 private OnCompletionListener completionListener;
 private OnErrorListener errorListener;
 private OnSeekCompleteListener seekListener;
 private final ContentObserver observer=new ContentObserver(handler){public void onChange(boolean self){applySettings();}};
 public static void initialize(Context context){app=context.getApplicationContext();}
 public SonyMediaPlayer(){super();if(app==null)throw new IllegalStateException("Sony player context not initialized");observe();}
 public void setDataSource(Context context,Uri uri)throws IOException{setDataSource(uri.toString());}
 public void setDataSource(Context context,Uri uri,Map<String,String> headers)throws IOException{if(headers!=null&&!headers.isEmpty())throw new IOException("Custom HTTP headers are unsupported");setDataSource(context,uri);}
 public void setDataSource(String value)throws IOException{if(released)throw new IllegalStateException("released");source=value;prepared=false;position=0;}
 public void setDataSource(String value,Map<String,String> headers)throws IOException{if(headers!=null&&!headers.isEmpty())throw new IOException("Custom HTTP headers are unsupported");setDataSource(value);}
 public void prepareAsync(){
  if(source==null||released)throw new IllegalStateException("No source");final int token=++generation;final String path=source;
  loader.execute(()->{
   if(token!=generation||released)return;
   CodecSource decoded=new CodecSource(app);boolean ok=decoded.setDataSource(path);
   if(token!=generation||released){decoded.close();return;}
   if(!handler.post(()->{if(token!=generation||released){decoded.close();return;}if(!ok){decoded.close();fail(MEDIA_ERROR_UNKNOWN,MEDIA_ERROR_UNSUPPORTED);return;}codec=decoded;duration=(int)Math.min(Integer.MAX_VALUE,decoded.getDuration()/1000);prepared=true;if(preparedListener!=null)preparedListener.onPrepared(this);}))decoded.close();
  });
 }
 public void prepare()throws IOException{if(source==null)throw new IOException("No source");codec=new CodecSource(app);if(!codec.setDataSource(source))throw new IOException("Cannot decode audio");duration=(int)Math.min(Integer.MAX_VALUE,codec.getDuration()/1000);prepared=true;}
 public void start(){
  if(!prepared||released)throw new IllegalStateException("Player is not prepared");
  synchronized(LOCK){
   // Z1 may start a preloaded next player again after its automatic transition.
   // MediaPlayer.start() is idempotent while playing; Sony nativePlay() seeks
   // again and can cancel the decoder dequeue on the active rendering thread.
   if(owner==this&&playing)return;
   try{
   observe();if(engine==null)engine=new PhonePlayer(app);
   if(owner!=this){if(owner!=null){owner.position=engine.position();owner.playing=false;}engine.stop();owner=this;
    CodecSource ready=codec;codec=null;final int token=generation;
    engine.open(source,ready,getAudioSessionId(),()->handler.post(()->complete(token)),error->handler.post(()->{if(token==generation&&!released)fail(MEDIA_ERROR_UNKNOWN,error);}));
    if(position>0)engine.seek(position);
   }
   applySettings();engine.volume(left,right);engine.play();playing=true;
  }catch(RuntimeException|LinkageError e){android.util.Log.e("SonyPlayer","Sony playback failed",e);if(engine!=null)engine.stop();owner=null;prepared=false;fail(MEDIA_ERROR_UNKNOWN,MEDIA_ERROR_UNSUPPORTED);}}
 }
 private void complete(int token){if(token!=generation||released)return; synchronized(LOCK){if(owner!=this)return;playing=false;position=duration;}MediaPlayer following=next;next=null;if(following!=null)following.start();if(completionListener!=null)completionListener.onCompletion(this);}
 private void fail(int what,int extra){playing=false;if(errorListener==null||!errorListener.onError(this,what,extra)){if(completionListener!=null)completionListener.onCompletion(this);}}
 public void pause(){synchronized(LOCK){if(owner==this&&engine!=null){engine.pause();position=engine.position();}playing=false;}}
 public void stop(){reset();}
 public void reset(){generation++;synchronized(LOCK){if(owner==this&&engine!=null){engine.stop();owner=null;}}if(codec!=null){codec.close();codec=null;}prepared=playing=false;source=null;position=duration=0;next=null;}
 public void release(){if(released)return;released=true;reset();if(observing)app.getContentResolver().unregisterContentObserver(observer);loader.shutdownNow();super.release();}
 public boolean isPlaying(){return playing;}
 public int getDuration(){return duration;}
 public int getCurrentPosition(){synchronized(LOCK){return owner==this&&engine!=null?Math.max(0,Math.min(duration,engine.position())):position;}}
 public void seekTo(int ms){position=Math.max(0,Math.min(duration,ms));synchronized(LOCK){if(owner==this&&engine!=null)engine.seek(position);}handler.post(()->{if(!released&&seekListener!=null)seekListener.onSeekComplete(this);});}
 public void setVolume(float l,float r){left=l;right=r;synchronized(LOCK){if(owner==this&&engine!=null)engine.volume(l,r);}}
 public void setNextMediaPlayer(MediaPlayer value){if(value==this)throw new IllegalArgumentException("same player");next=value;}
 public void setAudioStreamType(int stream){} // The Sony AudioTrack uses STREAM_MUSIC.
 public void setOnPreparedListener(OnPreparedListener l){preparedListener=l;}
 public void setOnCompletionListener(OnCompletionListener l){completionListener=l;}
 public void setOnErrorListener(OnErrorListener l){errorListener=l;}
 public void setOnSeekCompleteListener(OnSeekCompleteListener l){seekListener=l;}
 public void setOnInfoListener(OnInfoListener l){}
 public void setOnBufferingUpdateListener(OnBufferingUpdateListener l){}
 public boolean setLowPowerAudio(boolean value){return false;}
 public byte[] getNativeSpectrum(){synchronized(LOCK){return owner==this&&engine!=null?engine.getSpectrum():new byte[128];}}
 public AudioDeviceInfo getNativeOutputDevice(){synchronized(LOCK){return owner==this&&engine!=null?engine.getOutputDevice():null;}}
 public int getNativeSampleRate(){synchronized(LOCK){return owner==this&&engine!=null?engine.outputRate:48000;}}
 public long getNativeBytesWritten(){synchronized(LOCK){return owner==this&&engine!=null?engine.bytesWritten:0;}}
 private void applySettings(){synchronized(LOCK){if(owner!=this||engine==null)return;int[] bands=new int[6];boolean enabled=false;int vpt=4;
  try(Cursor cursor=app.getContentResolver().query(SETTINGS,null,null,null,null)){
   if(cursor!=null&&cursor.moveToFirst()){enabled=cursor.getInt(cursor.getColumnIndexOrThrow("enabled"))!=0;for(int i=0;i<6;i++)bands[i]=Math.max(-10,Math.min(10,cursor.getInt(cursor.getColumnIndexOrThrow("band"+i))));int mode=cursor.getInt(cursor.getColumnIndexOrThrow("vpt"));if(mode==0||mode==1||mode==3||mode==4)vpt=mode;}
  }catch(Exception e){android.util.Log.w("SonyPlayer","Equalizer settings unavailable: "+e.getClass().getSimpleName());}
  engine.eq(bands,enabled);engine.vpt(vpt);android.util.Log.i("SonyPlayer","Applied Sony EQ="+enabled+" bands="+java.util.Arrays.toString(bands)+" VPT="+vpt);
 }}
}
