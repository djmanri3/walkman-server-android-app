package com.sony.songpal.localplayer.playbackservice;
import android.app.Instrumentation;import android.os.*;import android.content.*;import android.database.*;import android.media.*;import android.net.Uri;import java.io.*;import java.util.*;import java.util.concurrent.*;import java.security.*;
public class VerifySony extends Instrumentation {
 public void onCreate(Bundle args){super.onCreate(args);start();}
 void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 SonyMediaPlayer actualPlayer(Context c)throws Exception{
  return new SonyMediaPlayer();
 }
 String render(Context c,File file,int bass,int mode)throws Exception{
  PhonePlayer p=new PhonePlayer(c);CountDownLatch done=new CountDownLatch(1);try{
   CodecSource input=new CodecSource(c);require(input.setDataSource(file.getPath()),"decode");p.open(file.getPath(),input,((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).generateAudioSessionId(),done::countDown,e->{android.util.Log.e("SonyVerify","Native error "+e);done.countDown();});p.eq(new int[]{bass,0,0,0,0,0},true);p.vpt(mode);p.pcmDigest=MessageDigest.getInstance("SHA-256");p.play();require(done.await(10,TimeUnit.SECONDS),"completion timeout");require(p.error==0&&p.bytesWritten>100000,"no valid PCM");byte[] digest=p.pcmDigest.digest();StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b));return out.toString();
  }finally{p.close();}
 }
 public void onStart(){Bundle result=new Bundle();try{
  Context c=getTargetContext();SonyMediaPlayer.initialize(c);File file=new File(c.getCacheDir(),"sony-verify.flac");try(InputStream in=getContext().getAssets().open("probe.flac");OutputStream out=new FileOutputStream(file)){byte[] data=new byte[8192];int n;while((n=in.read(data))>0)out.write(data,0,n);}
  String flat=render(c,file,0,4),bass=render(c,file,10,4),studio=render(c,file,0,0),club=render(c,file,0,1),hall=render(c,file,0,3),restore=render(c,file,0,4);
  Set<String> different=new HashSet<>(Arrays.asList(flat,bass,studio,club,hall));require(different.size()==5,"Sony modes produce identical PCM");require(flat.equals(restore),"off does not restore PCM");result.putString("pcm","PASS Clear Bass and all VPT modes differ; off restores exact baseline");
  CountDownLatch prepared=new CountDownLatch(1),complete=new CountDownLatch(1);SonyMediaPlayer[] player=new SonyMediaPlayer[1];
  runOnMainSync(()->{try{SonyMediaPlayer p=player[0]=actualPlayer(c);p.setOnPreparedListener(mp->{mp.start();prepared.countDown();});p.setOnCompletionListener(mp->complete.countDown());p.setDataSource("http://127.0.0.1:8767/tone.mp3");p.prepareAsync();}catch(Exception e){throw new RuntimeException(e);}});
  require(prepared.await(10,TimeUnit.SECONDS),"HTTP prepare");Thread.sleep(500);require(player[0].isPlaying()&&player[0].getCurrentPosition()>0,"HTTP playback");runOnMainSync(()->player[0].pause());int paused=player[0].getCurrentPosition();Thread.sleep(300);require(player[0].getCurrentPosition()==paused,"pause moves position");runOnMainSync(()->{player[0].seekTo(5000);player[0].start();});require(complete.await(8,TimeUnit.SECONDS),"seek/resume completion");runOnMainSync(()->player[0].release());result.putString("streaming","PASS HTTP MP3 prepare/play/pause/seek/resume/complete/release");
  try(Cursor cursor=c.getContentResolver().query(Uri.parse("content://local.extracted.equalizer.sony.settings/state"),null,null,null,null)){require(cursor!=null&&cursor.moveToFirst(),"settings provider");result.putString("settings","PASS "+c.getPackageName()+" can read Sony Equalizer controls");}
  SonyMediaPlayer[] pair=new SonyMediaPlayer[2];CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
  // Prepare the next decoder while current Sony playback continues; releasing the old shell must not stop the new owner.
  pair[0]=actualPlayer(c);pair[1]=actualPlayer(c);for(SonyMediaPlayer p:pair){p.setDataSource(file.getPath());p.prepare();}
  pair[0].setOnCompletionListener(p->first.countDown());pair[1].setOnCompletionListener(p->second.countDown());pair[0].setNextMediaPlayer(pair[1]);runOnMainSync(()->pair[0].start());
  require(first.await(7,TimeUnit.SECONDS),"first completion");require(pair[1].isPlaying(),"automatic next did not start");runOnMainSync(()->pair[0].release());require(pair[1].isPlaying(),"release previous stopped next");require(second.await(7,TimeUnit.SECONDS),"next completion");runOnMainSync(()->pair[1].release());result.putString("queue","PASS actual player factory, preloaded next, automatic transition, release previous");
  android.os.HandlerThread worker=new android.os.HandlerThread("Sony player callback test");worker.start();android.os.Handler workerHandler=new android.os.Handler(worker.getLooper());CountDownLatch workerDone=new CountDownLatch(1);Throwable[] workerError=new Throwable[1];
  workerHandler.post(()->{try{SonyMediaPlayer mp=actualPlayer(c);mp.setOnPreparedListener(v->{try{require(android.os.Looper.myLooper()==worker.getLooper(),"prepared delivered on wrong thread");v.start();}catch(Throwable e){workerError[0]=e;workerDone.countDown();}});mp.setOnCompletionListener(v->{try{require(android.os.Looper.myLooper()==worker.getLooper(),"completion delivered on wrong thread");v.release();}catch(Throwable e){workerError[0]=e;}workerDone.countDown();});mp.setDataSource(file.getPath());mp.prepareAsync();}catch(Throwable e){workerError[0]=e;workerDone.countDown();}});
  require(workerDone.await(8,TimeUnit.SECONDS),"worker callback timeout");worker.quitSafely();if(workerError[0]!=null)throw new AssertionError(workerError[0]);result.putString("threading","PASS callbacks preserve the player creation Looper used by the native web audio bridge");
  result.putString("result","PASS");finish(-1,result);
 }catch(Throwable error){android.util.Log.e("SonyVerify","FAIL",error);result.putString("failure",error.toString());finish(0,result);}}
}
