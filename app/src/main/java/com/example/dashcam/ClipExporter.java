package com.example.dashcam;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/** Remuxes a time window into one video per camera without re-encoding. */
final class ClipExporter {
 static final class Source {
  final File file;
  final long startMs;
  Source(File file,long startMs){this.file=file;this.startMs=startMs;}
 }
 static void export(List<Source> sources,long cutoffMs,long endMs,File output)throws IOException {
  if(sources.isEmpty())throw new IOException("No clips");
  MediaMuxer muxer=null;
  boolean started=false,finished=false;
  long nextPts=0;
  try{
   muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
   int muxTrack=-1;
   ByteBuffer bytes=null;
   for(Source source:sources){
    if(source.startMs>endMs)continue;
    MediaExtractor extractor=new MediaExtractor();
    try{
     extractor.setDataSource(source.file.getAbsolutePath());
     int videoTrack=-1;
     for(int t=0;t<extractor.getTrackCount();t++){
      String mime=extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
      if(mime!=null&&mime.startsWith("video/")){videoTrack=t;break;}
     }
     if(videoTrack<0)continue;
     MediaFormat format=extractor.getTrackFormat(videoTrack);
     extractor.selectTrack(videoTrack);
     extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
     long origin=extractor.getSampleTime();
     if(origin<0)continue;
     long relativeStartUs=Math.max(0,cutoffMs-source.startMs)*1000L;
     long relativeEndUs=Math.max(0,endMs-source.startMs)*1000L;
     extractor.seekTo(origin+relativeStartUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
     long firstSample=extractor.getSampleTime();
     if(firstSample<0||firstSample-origin>relativeEndUs)continue;
     if(!started){
      muxTrack=muxer.addTrack(format);
      if(format.containsKey(MediaFormat.KEY_ROTATION))
       muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION));
      muxer.start();
      started=true;
     }
     int capacity=format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
      ?Math.max(4_194_304,format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)):4_194_304;
     if(bytes==null||bytes.capacity()<capacity)bytes=ByteBuffer.allocateDirect(capacity);
     MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
     long firstPts=-1,lastPts=-1;
     while(true){
      long pts=extractor.getSampleTime();
      if(pts<0||pts-origin>relativeEndUs)break;
      long required=extractor.getSampleSize();
      if(required>64_000_000L)throw new IOException("Video sample is too large");
      if(required>bytes.capacity())bytes=ByteBuffer.allocateDirect((int)required);
      bytes.clear();
      int size=extractor.readSampleData(bytes,0);
      if(size<0)break;
      if(size==0){extractor.advance();continue;}
      if(firstPts<0)firstPts=pts;
      long relative=pts-firstPts;
      int flags=(extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0
       ?MediaCodec.BUFFER_FLAG_KEY_FRAME:0;
      info.set(0,size,nextPts+relative,flags);
      muxer.writeSampleData(muxTrack,bytes,info);
      lastPts=relative;
      extractor.advance();
     }
     if(lastPts>=0){
      int rate=format.containsKey(MediaFormat.KEY_FRAME_RATE)?format.getInteger(MediaFormat.KEY_FRAME_RATE):30;
      nextPts+=lastPts+1_000_000L/Math.max(1,rate);
     }
    }finally{extractor.release();}
   }
   if(!started)throw new IOException("No video samples in the requested minute");
   muxer.stop();
   finished=true;
  }finally{
   if(muxer!=null)muxer.release();
   if(!finished)output.delete();
  }
 }
}
