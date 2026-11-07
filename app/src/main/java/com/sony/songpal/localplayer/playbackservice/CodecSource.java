package com.sony.songpal.localplayer.playbackservice;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.util.Collections;

/** Bounded streaming decoder. Sony consumes interleaved PCM16 through its original JNI contract. */
final class CodecSource implements MediaCodecSourceInterface {
 private static int nextKey=1;
 private final int key;
 private final Context context;
 private MediaExtractor extractor;
 private MediaCodec decoder;
 private ByteBuffer buffer, output;
 private int outputIndex=-1, rate, channels;
 private long duration, position;
 private boolean inputEnd, outputEnd;
 private volatile boolean closed;
 private String path;
 private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
 CodecSource(Context context){this.context=context;synchronized(CodecSource.class){key=nextKey++;}}
 public boolean setDataSource(String value){
  if(decoder!=null)return true; // A prepared stream is bound to its original URI; JNI receives a local input token.
  close();
  try{
   extractor=new MediaExtractor();Uri uri=Uri.parse(value);
   if("content".equals(uri.getScheme())||"android.resource".equals(uri.getScheme()))extractor.setDataSource(context,uri,Collections.emptyMap());
   else if("file".equals(uri.getScheme()))extractor.setDataSource(uri.getPath());
   else extractor.setDataSource(value,Collections.emptyMap());
   for(int i=0;i<extractor.getTrackCount();i++){
    MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
    if(mime==null||!mime.startsWith("audio/"))continue;
    extractor.selectTrack(i);rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
    // Do not request float PCM: the original Sony input contract is signed PCM16.
    format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
    decoder=MediaCodec.createDecoderByType(mime);decoder.configure(format,null,null,0);decoder.start();path=value;closed=false;return true;
   }
   throw new IllegalArgumentException("No audio track");
  }catch(Exception e){android.util.Log.e("SonyPlayer","Cannot decode source",e);close();return false;}
 }
 public int getBitsPerSample(){return 16;} public int getChannels(){return channels;} public int getSamplingRate(){return rate;}
 public long getDuration(){return duration;} public long getCurrentPosition(){return position;} public int getReleaseKey(){return key;}
 public ByteBuffer requestByteBuffer(){return buffer=ByteBuffer.allocateDirect(0x3400);}public void releaseByteBuffer(){buffer=null;}
 public int readData(){
  if(closed||buffer==null||decoder==null)return -1;
  MediaCodec activeDecoder=decoder;
  buffer.clear();
  try{
   while(buffer.hasRemaining()){
    if(output!=null&&output.hasRemaining()){
     int count=Math.min(buffer.remaining(),output.remaining());int limit=output.limit();output.limit(output.position()+count);buffer.put(output);output.limit(limit);
     if(!output.hasRemaining()){activeDecoder.releaseOutputBuffer(outputIndex,false);output=null;outputIndex=-1;}
     continue;
    }
    if(outputEnd)break;
    if(!inputEnd){int index=activeDecoder.dequeueInputBuffer(1000);if(index>=0){ByteBuffer in=activeDecoder.getInputBuffer(index);in.clear();int count=extractor.readSampleData(in,0);if(count<0){activeDecoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}else{activeDecoder.queueInputBuffer(index,0,count,extractor.getSampleTime(),0);extractor.advance();}}}
    int index=activeDecoder.dequeueOutputBuffer(info,10000);
    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=activeDecoder.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)&&f.getInteger(MediaFormat.KEY_PCM_ENCODING)!=AudioFormat.ENCODING_PCM_16BIT)throw new IllegalStateException("PCM16 required");}
    else if(index>=0){outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;position=info.presentationTimeUs;
     if(info.size==0){activeDecoder.releaseOutputBuffer(index,false);continue;}
     outputIndex=index;output=activeDecoder.getOutputBuffer(index);output.position(info.offset);output.limit(info.offset+info.size);
    }
   }
   return buffer.position()==0?-1:buffer.position();
  }catch(RuntimeException e){
   // MediaCodec cancels a blocking dequeue when the previous track is stopped.
   // That cancellation is part of a normal track transition, not a player failure.
   if(closed||decoder!=activeDecoder){output=null;outputIndex=-1;return -1;}
   android.util.Log.e("SonyPlayer","Decoder read failed",e);throw e;
  }
 }
 public void seekTo(long us){
  if(decoder==null)return;
  output=null;outputIndex=-1;decoder.flush();extractor.seekTo(Math.max(0,us),MediaExtractor.SEEK_TO_CLOSEST_SYNC);inputEnd=outputEnd=false;position=us;
 }
 public void start(){} public void pause(){} public void stop(){}
 void close(){closed=true;output=null;outputIndex=-1;if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}try{decoder.release();}catch(Exception ignored){}decoder=null;}if(extractor!=null){extractor.release();extractor=null;}path=null;}
}
