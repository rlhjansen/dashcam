package com.example.dashcam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.hardware.*;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.*;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.net.Uri;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground owner of the rear camera. All mutable state runs on the camera HandlerThread. */
public final class RecordingService extends Service implements CameraRecorder.Listener, SensorEventListener {
 public static final String ACTION_START="com.example.dashcam.START";
 public static final String ACTION_SAVE="com.example.dashcam.SAVE";
 public static final String ACTION_STOP="com.example.dashcam.STOP";
 private static final String ACTION_TEST_IMPACT="com.example.dashcam.TEST_IMPACT";
 static final String PREFS="dashcam-state";
 static final String KEY_STATUS="status";
 private static final String CHANNEL="recording";
 private static final int NOTIFICATION=41;
 private static final long SEGMENT_MS=5_000;
 private static final long MAX_SEGMENT_MS=60_000;
 private static final long BUFFER_MS=60_000;
 private static final long AUTO_POST_MS=10_000;
 private static final long AUTO_HANDOFF_GRACE_MS=25_000;
 private HandlerThread thread;
 private Handler worker;
 private final Handler mainHandler=new Handler(Looper.getMainLooper());
 private Surface previewSurface;
 private final LocalBinder binder=new LocalBinder();
 public final class LocalBinder extends Binder {
  public void setPreviewSurface(Surface surface,Runnable complete){
   worker.post(()->{
    previewSurface=surface;
    Runnable done=complete==null?null:()->mainHandler.post(complete);
    if(back!=null)back.setPreviewSurface(surface,done);
    else if(done!=null)done.run();
   });
  }
 }

 private CameraRecorder back;
 private boolean active;
 private boolean saving;
 private boolean failed;
 private boolean closingRecorder;
 private int generation;
 private long closedThroughMs;
 private boolean stopRequested;
 private String recordingDescription;
 private final ArrayDeque<SaveRequest> saveRequests=new ArrayDeque<>();
 private final ArrayList<Clip> clips=new ArrayList<>();
 private final HashSet<File> pinned=new HashSet<>();
 private ExecutorService io;
 private SensorManager sensors;
 private PowerManager.WakeLock wakeLock;
 private Sensor linearAcceleration;
 private ImpactDetector impactDetector;
 private final Runnable rotate=new Runnable(){@Override public void run(){
  if(!active)return;
  if(back!=null&&back.segmentOlderThan(MAX_SEGMENT_MS))restartRecorder();
  prune();
  worker.postDelayed(this,SEGMENT_MS);
 }};

 private static final class Clip{
  final String role;final File file;final long start,end;
  Clip(String role,File file,long start,long end){this.role=role;this.file=file;this.start=start;this.end=end;}
 }
 private static final class SaveRequest{
  final long atMs,endMs;
  final boolean automatic;
  final String event;
  SaveRequest(boolean automatic,long atMs,long wallMs){
   this.atMs=atMs;
   this.automatic=automatic;
   this.endMs=atMs+(automatic?AUTO_POST_MS:0);
   this.event=(automatic?"crash-":"manual-")+wallMs;
  }
 }

 @Override public void onCreate(){
  super.onCreate();
  thread=new HandlerThread("dashcam-camera");thread.start();worker=new Handler(thread.getLooper());
  io=Executors.newSingleThreadExecutor();
  NotificationManager nm=getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Dashcam recording",NotificationManager.IMPORTANCE_LOW));
  nm.createNotificationChannel(new NotificationChannel("errors","Dashcam errors",NotificationManager.IMPORTANCE_DEFAULT));
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  String action=intent==null?ACTION_STOP:intent.getAction();
  if(ACTION_START.equals(action)){
   if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
   startForeground(NOTIFICATION,notification("Preparing rear camera"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
   writeStatus("Preparing rear camera");
   worker.post(this::begin);
  }else if(ACTION_SAVE.equals(action))worker.post(()->{if(active)save(false);else if(!stopRequested&&!saving)stopSelf();});
  else if(ACTION_STOP.equals(action))worker.post(this::stopRecording);
  else if(ACTION_TEST_IMPACT.equals(action)&&(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)
   worker.post(()->{if(active)save(true);});
  return START_NOT_STICKY;
 }
 private Notification notification(String status){
  Intent save=new Intent(this,RecordingService.class).setAction(ACTION_SAVE);
  Intent stop=new Intent(this,RecordingService.class).setAction(ACTION_STOP);
  return new Notification.Builder(this,CHANNEL)
   .setSmallIcon(android.R.drawable.presence_video_online)
   .setContentTitle("Dashcam")
   .setContentText(status)
   .setOngoing(true)
   .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_save,"Save last minute",
    PendingIntent.getService(this,1,save,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build())
   .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop",
    PendingIntent.getService(this,2,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build())
   .build();
 }
 private void writeStatus(String text){getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_STATUS,text).apply();}
 private void show(String text){
  writeStatus(text);
  getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text));
 }
 private void begin(){
  if(active||saving||stopRequested)return;
  try{
   CameraManager manager=getSystemService(CameraManager.class);
   String cameraId=rearCamera(manager);
   if(cameraId==null){fatal("No rear camera is available");return;}
   CameraCharacteristics characteristics=manager.getCameraCharacteristics(cameraId);
   FrameRatePlan fps=chooseFps(characteristics);
   Size size=chooseSize(characteristics);
   Integer orientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
   if(fps==null||size==null){fatal("No supported rear-camera recording format");return;}

   File buffer=new File(getFilesDir(),"rolling");
   if(!buffer.exists()&&!buffer.mkdirs())throw new IOException("Cannot create buffer directory");
   clips.clear();
   closedThroughMs=0;
   File[] stale=buffer.listFiles();
   if(stale!=null)for(File file:stale)if(file.isFile())file.delete();
   back=new CameraRecorder(this,manager,cameraId,"back",size,fps.range,fps.requested,
    orientation==null?0:orientation,worker,buffer,this);
   back.setPreviewSurface(previewSurface,null);
   recordingDescription="Recording rear camera, "+size.getWidth()+"x"+size.getHeight()+" @ requested "+fps.requested+" fps";
   active=true;failed=false;
   startRecorder();
   if(!active)return;
   worker.postDelayed(rotate,SEGMENT_MS);
   sensors=getSystemService(SensorManager.class);
   linearAcceleration=sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
   if(linearAcceleration==null)linearAcceleration=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
   impactDetector=new ImpactDetector();
   if(linearAcceleration!=null)sensors.registerListener(this,linearAcceleration,SensorManager.SENSOR_DELAY_GAME,worker);
  }catch(Exception e){fatal("Could not start: "+e.getMessage());}
 }
 private String rearCamera(CameraManager manager)throws CameraAccessException{
  for(String id:manager.getCameraIdList()){
   Integer facing=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
   if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK)return id;
  }
  return null;
 }
 private static final class FrameRatePlan {
  final Range<Integer> range;
  final int requested;
  FrameRatePlan(Range<Integer> range,int requested){this.range=range;this.requested=requested;}
 }
 private FrameRatePlan chooseFps(CameraCharacteristics camera){
  Range<Integer>[] ranges=camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
  if(ranges==null||ranges.length==0)return null;
  int maximum=0;
  for(Range<Integer> range:ranges)maximum=Math.max(maximum,range.getUpper());
  if(maximum<=0)return null;
  int target=Math.max(1,Math.min(60,maximum/2));
  FrameRatePlan best=null;
  int bestScore=Integer.MAX_VALUE;
  for(Range<Integer> range:ranges){
   int nearest=Math.max(range.getLower(),Math.min(target,range.getUpper()));
   int score=Math.abs(nearest-target)*1000+range.getUpper()-range.getLower();
   if(score<bestScore){bestScore=score;best=new FrameRatePlan(range,nearest);}
  }
  return best;
 }
 private Size chooseSize(CameraCharacteristics camera){
  android.hardware.camera2.params.StreamConfigurationMap map=
   camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
  if(map==null)return null;
  Size[] available=map.getOutputSizes(MediaRecorder.class);
  if(available==null)return null;
  Size best=null;
  long bestArea=0;
  for(Size size:available){
   long area=(long)size.getWidth()*size.getHeight();
   if(area<=1280L*720&&area>bestArea){best=size;bestArea=area;}
  }
  return best;
 }
 @Override public void onOpened(CameraRecorder camera){
  if(active&&camera==back)camera.configure();
 }
 @Override public void onStarted(CameraRecorder camera){
  if(!active||camera!=back||!camera.isRecording())return;
  if(wakeLock==null){
   PowerManager power=getSystemService(PowerManager.class);
   wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Dashcam:Recording");
   wakeLock.setReferenceCounted(false);
   wakeLock.acquire();
  }
  getSystemService(NotificationManager.class).cancel(42);
  show(recordingDescription);
 }
 @Override public void onCompleted(String role,File file,long start,long end){
  clips.add(new Clip(role,file,start,end));
  maybeExport();
  prune();
 }
 @Override public void onFailure(String role,String message){
  if(failed||!active)return;
  failed=true;
  fatal(role+" recording failed: "+message);
 }
 private void prune(){
  long cutoff=SystemClock.elapsedRealtime()-BUFFER_MS-MAX_SEGMENT_MS;
  Iterator<Clip> it=clips.iterator();
  while(it.hasNext()){
   Clip clip=it.next();
   if(clip.end>=cutoff||pinned.contains(clip.file))continue;
   boolean needed=false;
   for(SaveRequest request:saveRequests){
    long first=request.atMs-BUFFER_MS-MAX_SEGMENT_MS;
    long last=request.endMs+MAX_SEGMENT_MS+SEGMENT_MS;
    if(clip.end>=first&&clip.start<=last){needed=true;break;}
   }
   if(!needed){clip.file.delete();it.remove();}
  }
 }
 private void save(boolean automatic){
  if(!active)return;
  if(saveRequests.size()>=8){
   show("Save queue is full; try again shortly");
   return;
  }
  SaveRequest request=new SaveRequest(automatic,SystemClock.elapsedRealtime(),System.currentTimeMillis());
  if(automatic){
   saveRequests.addLast(request);
   show("Capturing 10 seconds after impact...");
   worker.postDelayed(()->finishSaveWindow(request),AUTO_POST_MS);
  }else{
   saveRequests.addFirst(request);
   show("Saving last minute...");
   finishSaveWindow(request);
  }
 }
 private void finishSaveWindow(SaveRequest request){
  if(!saveRequests.contains(request))return;
  long remaining=request.endMs-SystemClock.elapsedRealtime();
  if(active&&remaining>0){
   worker.postDelayed(()->finishSaveWindow(request),remaining);
   return;
  }
  if(!active||back==null||!back.isRecording()){
   maybeExport();
   return;
  }
  if(finishedThrough("back",request.endMs)){
   maybeExport();
   return;
  }
  long pastEnd=SystemClock.elapsedRealtime()-request.endMs;
  if(request.automatic&&pastEnd<AUTO_HANDOFF_GRACE_MS){
   worker.postDelayed(()->finishSaveWindow(request),Math.min(1_000,AUTO_HANDOFF_GRACE_MS-pastEnd));
   return;
  }
  long minAge=request.automatic?3_000:1_500;
  if(back.segmentOlderThan(minAge)&&!back.hasPendingHandoff())restartRecorder();
  else worker.postDelayed(()->finishSaveWindow(request),500);
 }
 private boolean finishedThrough(String role,long atMs){
  if("back".equals(role)&&closedThroughMs>=atMs)return true;
  for(Clip clip:clips)if(clip.role.equals(role)&&clip.end>=atMs)return true;
  return false;
 }
 private void maybeExport(){
  if(closingRecorder||saving||saveRequests.isEmpty())return;
  SaveRequest request=saveRequests.peekFirst();
  if(active&&!finishedThrough("back",request.endMs))return;
  saveRequests.removeFirst();
  saving=true;
  long cutoff=request.atMs-BUFFER_MS;
  ArrayList<ClipExporter.Source> selected=new ArrayList<>();
  ArrayList<File> reserved=new ArrayList<>();
  for(Clip clip:clips)if(clip.role.equals("back")&&clip.end>=cutoff&&clip.start<=request.endMs){
   selected.add(new ClipExporter.Source(clip.file,clip.start));
   pinned.add(clip.file);
   reserved.add(clip.file);
  }
  show("Saving last minute...");
  io.execute(()->{
   String message;
   File merged=new File(getCacheDir(),request.event+"-back.mp4");
   try{
    if(selected.isEmpty())message="No footage is available yet";
    else{
     ClipExporter.export(selected,cutoff,request.endMs,merged);
     publish(merged,request.event);
     message="Saved rear-camera video in Movies/Dashcam/"+request.event;
    }
   }catch(Exception e){message="Save failed: "+e.getMessage();}
   finally{merged.delete();}
   final String result=message;
   worker.post(()->{
    pinned.removeAll(reserved);
    saving=false;
    if(!failed)show(result);
    maybeExport();
    prune();
    if(stopRequested&&!saving&&saveRequests.isEmpty())finishStop();
   });
  });
 }
 private void publish(File video,String event)throws IOException{
  ContentValues values=new ContentValues();
  values.put(MediaStore.Video.Media.DISPLAY_NAME,video.getName());
  values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
  values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Dashcam/"+event);
  values.put(MediaStore.Video.Media.IS_PENDING,1);
  Uri uri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
  if(uri==null)throw new IOException("MediaStore insert failed");
  boolean complete=false;
  try(InputStream input=new FileInputStream(video);OutputStream output=getContentResolver().openOutputStream(uri)){
   if(output==null)throw new IOException("MediaStore output unavailable");
   byte[] bytes=new byte[65536];int read;
   while((read=input.read(bytes))!=-1)output.write(bytes,0,read);
   complete=true;
  }finally{
   if(complete){ContentValues done=new ContentValues();done.put(MediaStore.Video.Media.IS_PENDING,0);getContentResolver().update(uri,done,null,null);}
   else getContentResolver().delete(uri,null,null);
  }
 }
 private void fatal(String message){
  failed=true;
  writeStatus(message);
  Notification error=new Notification.Builder(this,"errors")
   .setSmallIcon(android.R.drawable.stat_notify_error)
   .setContentTitle("Dashcam recording stopped")
   .setContentText(message)
   .setStyle(new Notification.BigTextStyle().bigText(message))
   .setAutoCancel(true).build();
  getSystemService(NotificationManager.class).notify(42,error);
  stopRecording();
 }
 private void restartRecorder(){
  if(!active||back==null)return;
  closingRecorder=true;
  back.rotate();
  closedThroughMs=SystemClock.elapsedRealtime();
  closingRecorder=false;
  if(active)armStartTimeout();
  maybeExport();
 }
 private void startRecorder(){
  back.start();
  armStartTimeout();
 }
 private void armStartTimeout(){
  int currentGeneration=++generation;
  worker.postDelayed(()->{
   if(active&&currentGeneration==generation&&(back==null||!back.isRecording()))
    fatal("Rear camera did not begin recording within 15 seconds");
  },15_000);
 }
 private void stopRecording(){
  if(stopRequested&&!active)return;
  active=false;
  stopRequested=true;
  worker.removeCallbacks(rotate);
  if(sensors!=null)sensors.unregisterListener(this);
  closingRecorder=true;
  CameraRecorder camera=back;
  back=null;
  if(camera!=null)camera.close();
  closingRecorder=false;
  maybeExport();
  prune();
  if(saving||!saveRequests.isEmpty())show("Finishing saved video...");
  else{
   if(!failed)writeStatus("Stopped");
   finishStop();
  }
 }
 private void finishStop(){
  stopRequested=false;
  clips.clear();
  File rolling=new File(getFilesDir(),"rolling");
  File[] files=rolling.listFiles();
  if(files!=null)for(File file:files)if(file.isFile())file.delete();
  if(wakeLock!=null){wakeLock.release();wakeLock=null;}
  stopForeground(STOP_FOREGROUND_REMOVE);
  stopSelf();
 }
 @Override public void onSensorChanged(SensorEvent event){
  if(!active||impactDetector==null)return;
  // The rear lens points forward; Android's -Z axis follows the road.
  boolean jolt;
  if(event.sensor.getType()==Sensor.TYPE_LINEAR_ACCELERATION)
   jolt=impactDetector.onLinearAcceleration(event.values,event.timestamp);
  else if(event.sensor.getType()==Sensor.TYPE_ACCELEROMETER)
   jolt=impactDetector.onAccelerometer(event.values,event.timestamp);
  else return;
  if(jolt)save(true);
 }
 @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
 @Override public IBinder onBind(Intent intent){return binder;}
 @Override public void onDestroy(){
  if(io!=null)io.shutdown();
  if(wakeLock!=null){wakeLock.release();wakeLock=null;}
  if(worker!=null)worker.post(()->{active=false;if(back!=null)back.close();if(sensors!=null)sensors.unregisterListener(this);thread.quitSafely();});
  super.onDestroy();
 }
}
