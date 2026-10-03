ï»¿# Rear-camera dashcam for Android

This native Android app records the rear camera in a foreground service. Start it while the app is visible; recording then continues while Maps or another navigation app is foreground. Minimum Android API is 31, which covers Android 12 and OxygenOS 12.

## Behavior

- Records H.264 video from the rear camera to short MP4 segments in app-private storage. MediaRecorder normally switches to the next output file without closing the camera. If a segment does not switch within 60 seconds, the app keeps the rear camera open and replaces its recording session, which may cause a short gap.
- Keeps roughly the preceding 60 seconds plus enough segment overlap for saving. Older temporary files are pruned; the rolling directory is cleaned when recording stops.
- Aims for 60 fps or half the rear camera's maximum reported normal Camera2 capture rate, whichever is lower. It chooses the nearest frame rate in a supported camera exposure range and a recording size up to 1280x720. Constrained high-speed modes are a separate Android recording path and are not used by this continuous foreground recorder. Actual frame rate depends on the phone.
- **Save last minute** in the activity or persistent notification finalizes the open segment immediately (waiting at most 1.5 seconds if it just began), remuxes the preceding minute, and publishes one MP4 in Movies/Dashcam/<event>. Replacing the recording session can leave a small recording gap.
- A negative longitudinal acceleration below -8 m/sÂ² triggers an automatic save with a 45-second cooldown. Automatic clips include the preceding minute and up to 10 seconds after the trigger; manual Save remains immediate. Aim the rear lens along the direction of travel; the screen faces into the cabin. Android’s negative Z axis then points forward, and a sharp forward-collision deceleration is positive Z in sensor readings. The app uses the linear-acceleration sensor when available and an accelerometer gravity filter otherwise.
- A live rear-camera preview appears while the activity is open. It shares the recording camera session and detaches when the activity closes. The activity warns when gravity indicates the phone is screen-up with its rear lens facing downward.
- **Stop** closes the camera and foreground service. The app does not record audio.

## Build

Open this folder in Android Studio with JDK 17 and Android SDK platform 35, or run gradlew.bat :app:assembleDebug with JAVA_HOME and ANDROID_HOME set. The project uses Android Gradle Plugin 8.7.3 and Gradle 8.9. The debug APK is app/build/outputs/apk/debug/app-debug.apk.

## Verification

Android build and lint pass. A compiled sensor trace check passes for threshold, gravity filtering, and cooldown. The OnePlus device test below covers Maps foreground recording, a saved near-minute MP4, actual frame rate, and cleanup after Stop. The acceleration threshold and axis still need calibration for the actual mount. A fallback rollover can create a short recording gap. The exporter joins available frames, so a saved clip may play for less than 60 seconds when the real-time window contains gaps; it can also start slightly before the cutoff at a keyframe.

On 29 September 2026, the connected OnePlus 7 GM1903 (Android 12, build GM1903_11_H.40) rejected simultaneous front and back cameras with "Too many cameras already open". This version records the rear camera only so it can run on that handset. The rear-camera build was then installed and tested on this phone: recording continued with Google Maps foreground, a manual save produced a playable H.264 MP4 at 1280x720 lasting 58.71 seconds with 882 decoded frames (about 15 fps), and Stop ended the foreground service and emptied the rolling directory. The physical jolt trigger still needs a controlled real-mount check.

The subsequent preview and quick-save build was tested on the same OnePlus 7: the preview displayed a lit scene, recording continued while Maps was foreground, and returning to Dashcam restored the preview. A separate manual Save appeared in about 3 seconds and produced a 56.7-second MP4. Moving the phone also triggered an automatic event save. Stop again ended the service and cleared the rolling directory. Preview attachment or detachment and quick Save may each introduce a brief camera-stream gap; a mounted driving test remains necessary for impact calibration.

Android's camera foreground-service rules require starting from a visible activity on Android 12 and later. See [Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

On 3 October 2026, a camera-retaining session change was installed on the same phone. Manual Save completed and rolling recording continued. The 60-second fallback also completed and recording continued: the previous segment held about 62.6 seconds of frames across a 63.4-second interval between segment starts, indicating roughly a 0.8-second gap. Stop cleared the rolling files. With the lens aimed at a dark scene, both the preview and recorded frame were nearly black, so this run did not recheck image orientation.

A further 3 October full-window test saved across the fallback boundary. The resulting MP4 decoded all 899 frames, lasted about 59.9 seconds at 15 fps, and remained playable through its final frame. Rolling recording continued after Save, and Stop cleared the buffer. The recorded scene was dark, so visual content and orientation were not assessed in this run.

The camera-retaining build was also left recording with Google Maps foreground through two file handoffs. Dashcam remained active when reopened, and Stop again cleared the rolling directory. The scene was too dark to recheck the visible preview image.

A stock OnePlus Camera preview check on 3 October showed the same nearly black rear-camera scene as Dashcam. No photo was taken. This supports the conclusion that the current dark view comes from the scene or lens setup; a lit-scene orientation check remains open.

The OnePlus 7 rear camera reports a 30 fps maximum in the normal Camera2 exposure ranges used by this app, yielding the 15 fps target measured in saved video. It also advertises constrained high-speed configurations, but those require Android's dedicated high-speed session and are outside the normal preview-plus-recorder path.

The crash-trigger direction was corrected to the rear-lens-forward axis on 3 October. The sensor trace now checks that a positive Android Z jolt triggers a save while a large Y-axis jolt does not. Build, lint, and the trace check pass; the updated APK was installed and a short recording smoke test passed. A controlled jolt in the actual vehicle mount is still needed to validate the threshold and mounting angle.

The OnePlus sensor service confirms Dashcam registers the linear-acceleration sensor at a 20 ms sampling period while recording, receives events, and unregisters on Stop. A recent resting accelerometer reading was about (0.16, 0.27, 9.82) m/s^2, consistent with the phone lying screen-up and its rear lens aimed downward. That orientation explains the current dark camera view, although a lit-scene image check is still pending.

The screen-up mounting hint was installed and verified on the OnePlus 7: with the phone resting rear-lens-down, Dashcam displayed the warning beside the preview while keeping Start, Save, and Stop accessible.

After the user aimed the connected phone at a lit scene, the rear preview visibly showed the tabletop and cable. A manually saved MP4 decoded 633 frames at about 15.1 fps, and a representative saved frame matched the preview's orientation and content. Moving the tethered phone then triggered an automatic crash event; its MP4 decoded 842 frames, about 57.1 seconds of lit rear-camera video. The duration is below 60 seconds because segment/session gaps are omitted. Dashcam was stopped afterward and its rolling directory was empty. An in-car mounting test is impractical with the USB tether, so impact threshold calibration under driving conditions remains unverified.

The first post-impact test captured a clip but exposed an OxygenOS MediaRecorder stop failure during a forced short-segment handoff, which ended recording. A subsequent longer run also hit the old 1.5 MB MediaRecorder file limit before a handoff and stopped. The installed follow-up uses an 8 MB segment limit, waits for a natural handoff after the 10-second impact tail, and restarts the recorder if the file limit is reached unexpectedly. Build and lint pass. On 3 October, a debug impact trigger after the rolling buffer had filled saved a 69.912-second rear-camera MP4 that decoded all 1,042 frames, including its final frame. The app was backgrounded for this trigger; rolling recording continued after the save. Stop ended the service and emptied the rolling directory. A real impact in the intended car mount remains untested.
