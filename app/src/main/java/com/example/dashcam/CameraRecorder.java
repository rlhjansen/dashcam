package com.example.dashcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.File;
import java.util.ArrayList;

/** One Camera2 capture session with MediaRecorder file handoffs for the rolling buffer. Handler confined. */
final class CameraRecorder {
 interface Listener {
  void onOpened(CameraRecorder camera);
  void onStarted(CameraRecorder camera);
  void onCompleted(String role, File file, long startMs, long endMs);
  void onFailure(String role, String message);
 }
 final String role;
 private final Context context;
 private final CameraManager manager;
 private final String id;
 private final Size size;
 private final Range<Integer> fps;
 private final int requestedFps;
 private final int orientationHint;
 private final Handler handler;
 private final File directory;
 private final Listener listener;
 private CameraDevice device;
 private CameraCaptureSession session;
 private Surface previewSurface;
 private int sessionGeneration;
 private boolean configuring;
 private final ArrayList<Runnable> afterConfiguration=new ArrayList<>();
 private MediaRecorder recorder;
 private File current,next;
 private long startedMs,sequence;
 private boolean recording,closed;

 CameraRecorder(Context context,CameraManager manager,String id,String role,Size size,Range<Integer> fps,int requestedFps,int orientationHint,Handler handler,File directory,Listener listener){
  this.context=context;this.manager=manager;this.id=id;this.role=role;this.size=size;this.fps=fps;this.requestedFps=requestedFps;this.orientationHint=orientationHint;this.handler=handler;this.directory=directory;this.listener=listener;
 }
 private File newFile(){return new File(directory,role+"-"+System.currentTimeMillis()+"-"+(sequence++)+".mp4");}
 void start(){closed=false;startSession();}
 private void startSession(){
  if(closed)return;
  current=newFile();
  try{
   MediaRecorder mr=new MediaRecorder(context);
   recorder=mr;
   int bitrate=Math.min(12_000_000,Math.max(2_000_000,size.getWidth()*size.getHeight()*requestedFps/5));
   mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
   mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
   mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
   mr.setVideoSize(size.getWidth(),size.getHeight());
   mr.setVideoFrameRate(requestedFps);
   mr.setVideoEncodingBitRate(bitrate);
   mr.setOrientationHint(orientationHint);
   mr.setMaxFileSize(8_000_000L);
   mr.setOnInfoListener((source,what,extra)->onInfo(source,what));
   mr.setOnErrorListener((source,what,extra)->{if(source==recorder)fail("MediaRecorder error "+what+"/"+extra);});
   mr.setOutputFile(current);
   mr.prepare();
   if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
   if(device!=null){listener.onOpened(this);return;}
   manager.openCamera(id,new CameraDevice.StateCallback(){
    @Override public void onOpened(CameraDevice camera){if(closed||mr!=recorder){camera.close();return;}device=camera;listener.onOpened(CameraRecorder.this);}
    @Override public void onDisconnected(CameraDevice camera){camera.close();if((camera==device||mr==recorder)&&!closed)fail("camera disconnected");}
    @Override public void onError(CameraDevice camera,int error){camera.close();if((camera==device||mr==recorder)&&!closed)fail(error==CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE?"camera limit reached; this phone may allow only one camera at a time":"camera error "+error);}
   },handler);
  }catch(Exception e){fail(e.toString());}
 }
 boolean isOpen(){return device!=null&&!closed;}
 void setPreviewSurface(Surface surface,Runnable complete){
  if(complete!=null)afterConfiguration.add(complete);
  if(previewSurface==surface){
   if(!configuring)finishCallbacks();
   return;
  }
  previewSurface=surface;
  if(device!=null&&recorder!=null)configure();
  else finishCallbacks();
 }
 private void finishCallbacks(){
  configuring=false;
  ArrayList<Runnable> callbacks=new ArrayList<>(afterConfiguration);
  afterConfiguration.clear();
  for(Runnable callback:callbacks)callback.run();
 }
 void configure(){
  MediaRecorder mr=recorder;
  CameraDevice camera=device;
  if(closed||mr==null||camera==null){finishCallbacks();return;}
  configuring=true;
  int configuredGeneration=++sessionGeneration;
  Surface video=mr.getSurface();
  Surface preview=previewSurface!=null&&previewSurface.isValid()?previewSurface:null;
  if(session!=null){
   try{session.stopRepeating();}catch(Exception ignored){}
   session.close();session=null;
  }
  ArrayList<Surface> outputs=new ArrayList<>();
  outputs.add(video);
  if(preview!=null)outputs.add(preview);
  try{
   camera.createCaptureSession(outputs,new CameraCaptureSession.StateCallback(){
    @Override public void onConfigured(CameraCaptureSession configured){
     if(closed||mr!=recorder||configuredGeneration!=sessionGeneration){
      configured.close();
      return;
     }
     session=configured;
     try{
      CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      request.addTarget(video);
      if(preview!=null)request.addTarget(preview);
      request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);
      configured.setRepeatingRequest(request.build(),null,handler);
      if(!recording){
       mr.start();
       recording=true;
       startedMs=SystemClock.elapsedRealtime();
       listener.onStarted(CameraRecorder.this);
      }
      finishCallbacks();
     }catch(Exception e){
      finishCallbacks();
      fail(e.toString());
     }
    }
    @Override public void onConfigureFailed(CameraCaptureSession configured){
     configured.close();
     if(closed||mr!=recorder||configuredGeneration!=sessionGeneration)return;
     if(preview!=null){previewSurface=null;configure();}
     else{
      finishCallbacks();
      fail("capture session configuration failed");
     }
    }
   },handler);
  }catch(Exception e){
   if(preview!=null){previewSurface=null;configure();}
   else{
    finishCallbacks();
    fail(e.toString());
   }
  }
 }
 private void onInfo(MediaRecorder source,int what){
  if(source!=recorder||!recording)return;
  if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING){
   if(next!=null)return;
   try{
    next=newFile();
    source.setNextOutputFile(next);
   }catch(Exception e){if(next!=null)next.delete();next=null;fail("cannot continue rolling recording: "+e);}
  }else if(what==MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED){
   if(next==null){fail("recorder switched to an unknown output file");return;}
   File completed=current;
   long start=startedMs,now=SystemClock.elapsedRealtime();
   current=next;next=null;startedMs=now;
   listener.onCompleted(role,completed,start,now);
  }else if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED){
   android.util.Log.w("Dashcam",role+" reached file size limit before handoff; restarting recorder");
   rotate();
  }
 }
 boolean isRecording(){return recording;}
 boolean segmentOlderThan(long ageMs){return recording&&SystemClock.elapsedRealtime()-startedMs>=ageMs;}
 boolean hasPendingHandoff(){return next!=null;}
 void rotate(){if(closed)return;finishSession(true,false);startSession();}
 void close(){closed=true;finishSession(false,true);}
 private void finishSession(boolean keepCamera,boolean reportStopFailure){
  sessionGeneration++;
  if(session!=null){try{session.stopRepeating();}catch(Exception ignored){}session.close();session=null;}
  finishCallbacks();
  CameraDevice opened=keepCamera?null:device;
  if(!keepCamera)device=null;
  MediaRecorder finished=recorder;recorder=null;
  boolean wasRecording=recording;recording=false;
  File file=current,pending=next;
  current=null;next=null;
  if(finished==null){if(opened!=null)opened.close();return;}
  String error=null;
  long now=SystemClock.elapsedRealtime();
  try{
   if(wasRecording){
    finished.stop();
    if(pending!=null&&pending.exists()&&pending.length()>0){
     long duration=durationMs(pending);
     long pendingStart=Math.max(startedMs,now-(duration>0?duration:1_000));
     listener.onCompleted(role,file,startedMs,pendingStart);
     listener.onCompleted(role,pending,pendingStart,now);
    }else{
     listener.onCompleted(role,file,startedMs,now);
     if(pending!=null)pending.delete();
    }
   }else{
    if(file!=null)file.delete();
    if(pending!=null)pending.delete();
   }
  }catch(RuntimeException e){
   if(file!=null)file.delete();
   if(pending!=null)pending.delete();
   error="incomplete video segment: "+e.getMessage();
  }finally{
   try{finished.reset();}catch(RuntimeException ignored){}
   try{finished.release();}catch(RuntimeException ignored){}
   if(opened!=null)opened.close();
  }
  if(error!=null){
   if(!reportStopFailure)android.util.Log.w("Dashcam",role+" segment discarded during handoff: "+error);
   else listener.onFailure(role,error);
  }
 }
 private long durationMs(File file){
  MediaMetadataRetriever metadata=new MediaMetadataRetriever();
  try{
   metadata.setDataSource(file.getAbsolutePath());
   String value=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
   return value==null?0:Long.parseLong(value);
  }catch(Exception ignored){return 0;}
  finally{try{metadata.release();}catch(Exception ignored){}}
 }
 private void fail(String reason){closed=true;finishSession(false,false);listener.onFailure(role,reason);}
}
