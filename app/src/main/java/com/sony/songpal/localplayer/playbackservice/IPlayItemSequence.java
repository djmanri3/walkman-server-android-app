package com.sony.songpal.localplayer.playbackservice; public interface IPlayItemSequence {public int getDuration();
public int getEndTime();
public int getFadeInEndTime();
public int getFadeInStartTime();
public int getFadeOutEndTime();
public int getFadeOutStartTime();
public int getFormat();
public int getId();
public com.sony.songpal.localplayer.playbackservice.PlayItemInfo getInfo();
public int getLast();
public float getNormalSpeed();
public java.lang.String getPath();
public int getSamplesPerSec();
public int getSpeedChangeTime();
public float getStartSpeed();
public int getStartTime();}