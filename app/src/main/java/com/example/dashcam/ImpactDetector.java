package com.example.dashcam;

/** Detects strong deceleration when the rear lens points along travel. */
final class ImpactDetector {
 private static final float THRESHOLD_MPS2=-8.0f;
 private static final long COOLDOWN_NS=45_000_000_000L;
 private long lastTriggerNs=Long.MIN_VALUE;
 private float gravityForward;
 private boolean gravityReady;

 boolean onLinearAcceleration(float[] values,long timestampNs){
  if(values==null||values.length<3)return false;
  return triggered(-values[2],timestampNs);
 }
 boolean onAccelerometer(float[] values,long timestampNs){
  if(values==null||values.length<3||!Float.isFinite(values[2]))return false;
  float forward=-values[2];
  if(!gravityReady){gravityForward=forward;gravityReady=true;return false;}
  float longitudinal=forward-gravityForward;
  gravityForward=0.95f*gravityForward+0.05f*forward;
  return triggered(longitudinal,timestampNs);
 }
 private boolean triggered(float longitudinal,long timestampNs){
  if(!Float.isFinite(longitudinal)||longitudinal>=THRESHOLD_MPS2)return false;
  if(lastTriggerNs!=Long.MIN_VALUE&&timestampNs-lastTriggerNs<COOLDOWN_NS)return false;
  lastTriggerNs=timestampNs;
  return true;
 }
}
