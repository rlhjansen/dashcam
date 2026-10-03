package com.example.dashcam;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;
import android.view.View;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity implements SensorEventListener {
 private TextView status;
 private TextView mountHint;
 private SensorManager sensorManager;
 private Sensor gravitySensor;
 private SharedPreferences statePrefs;
 private TextureView previewView;
 private Surface previewSurface;
 private RecordingService.LocalBinder recorderBinder;
 private boolean started,bound;
 private final SharedPreferences.OnSharedPreferenceChangeListener stateListener=(prefs,key)->{
  if(RecordingService.KEY_STATUS.equals(key)&&status!=null)
   runOnUiThread(()->status.setText(prefs.getString(RecordingService.KEY_STATUS,"")));
 };
 private final ServiceConnection connection=new ServiceConnection(){
  @Override public void onServiceConnected(ComponentName name,IBinder service){
   recorderBinder=(RecordingService.LocalBinder)service;
   if(started)attachPreview();
  }
  @Override public void onServiceDisconnected(ComponentName name){recorderBinder=null;}
 };
 private final TextureView.SurfaceTextureListener previewListener=new TextureView.SurfaceTextureListener(){
  @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
   texture.setDefaultBufferSize(1280,720);
   previewSurface=new Surface(texture);
   attachPreview();
  }
  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){}
  @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
   Surface old=previewSurface;
   previewSurface=null;
   Runnable release=()->{if(old!=null)old.release();texture.release();};
   if(recorderBinder!=null)recorderBinder.setPreviewSurface(null,release);
   else release.run();
   return false;
  }
 };
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  LinearLayout layout=new LinearLayout(this);
  layout.setOrientation(LinearLayout.VERTICAL);
  layout.setPadding(40,40,40,40);
  statePrefs=getSharedPreferences(RecordingService.PREFS,MODE_PRIVATE);
  status=new TextView(this);
  status.setText(statePrefs.getString(RecordingService.KEY_STATUS,
   "Start rear-camera recording here; it continues while Maps is open."));
  TextView previewLabel=new TextView(this);
  previewLabel.setText("Live rear-camera preview");
  previewView=new TextureView(this);
  previewView.setSurfaceTextureListener(previewListener);
  mountHint=new TextView(this);
  mountHint.setText("Rear lens appears to face down. Point it toward the road.");
  mountHint.setTextColor(0xffb00020);
  mountHint.setVisibility(View.GONE);
  sensorManager=getSystemService(SensorManager.class);
  if(sensorManager!=null){
   gravitySensor=sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
   if(gravitySensor==null)gravitySensor=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
  }
  Button start=new Button(this);start.setText("Start rear recording");start.setOnClickListener(v->start());
  Button save=new Button(this);save.setText("Save last minute");save.setOnClickListener(v->send(RecordingService.ACTION_SAVE));
  Button stop=new Button(this);stop.setText("Stop recording");stop.setOnClickListener(v->send(RecordingService.ACTION_STOP));
  int previewHeight=getResources().getDisplayMetrics().widthPixels*9/16;
  layout.addView(status);
  layout.addView(previewLabel);
  layout.addView(previewView,new LinearLayout.LayoutParams(-1,previewHeight));
  layout.addView(mountHint);
  layout.addView(start);
  layout.addView(save);
  layout.addView(stop);
  setContentView(layout);
 }
 private void attachPreview(){
  if(started&&recorderBinder!=null&&previewSurface!=null&&previewSurface.isValid())
   recorderBinder.setPreviewSurface(previewSurface,null);
 }
 @Override protected void onStart(){
  super.onStart();
  started=true;
  if(!bound)bound=bindService(new Intent(this,RecordingService.class),connection,BIND_AUTO_CREATE);
  else attachPreview();
 }
 @Override protected void onStop(){
  started=false;
  if(bound){
   if(recorderBinder!=null)recorderBinder.setPreviewSurface(null,()->{
    if(!started&&bound){unbindService(connection);bound=false;recorderBinder=null;}
    else if(started)attachPreview();
   });
   else{unbindService(connection);bound=false;}
  }
  super.onStop();
 }
 @Override protected void onResume(){
  super.onResume();
  statePrefs.registerOnSharedPreferenceChangeListener(stateListener);
  status.setText(statePrefs.getString(RecordingService.KEY_STATUS,"Ready to record"));
  if(sensorManager!=null&&gravitySensor!=null)
   sensorManager.registerListener(this,gravitySensor,SensorManager.SENSOR_DELAY_NORMAL);
 }
 @Override protected void onPause(){
  statePrefs.unregisterOnSharedPreferenceChangeListener(stateListener);
  if(sensorManager!=null)sensorManager.unregisterListener(this);
  super.onPause();
 }
 @Override protected void onDestroy(){
  if(bound){unbindService(connection);bound=false;recorderBinder=null;}
  super.onDestroy();
 }
 @Override public void onSensorChanged(SensorEvent event){
  if(mountHint==null||event.values.length<3||!Float.isFinite(event.values[2]))return;
  mountHint.setVisibility(event.values[2]>7.5f?View.VISIBLE:View.GONE);
 }
 @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
 private void start(){
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(new String[]{Manifest.permission.CAMERA},1);return;
  }
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);return;
  }
  startForegroundService(new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_START));
  status.setText("Starting; check the persistent notification.");
 }
 private void send(String action){startService(new Intent(this,RecordingService.class).setAction(action));}
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
  super.onRequestPermissionsResult(code,permissions,grants);
  if(code==2){
   startForegroundService(new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_START));
   status.setText("Starting; check the persistent notification.");
  }else if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)start();
  else status.setText("Camera permission is required.");
 }
}
