package com.sony.songpal.localplayer.playbackservice; public interface MediaCodecSourceInterface {public int getBitsPerSample();
public int getChannels();
public long getCurrentPosition();
public long getDuration();
public int getReleaseKey();
public int getSamplingRate();
public void pause();
public int readData();
public void releaseByteBuffer();
public java.nio.ByteBuffer requestByteBuffer();
public void seekTo(long a0);
public boolean setDataSource(java.lang.String a0);
public void start();
public void stop();}