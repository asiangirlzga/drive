# Lite Driving School

A tiny 3D driving-lesson game for **Android TV / smart TV** (also runs on phones).
First-person view from inside the car, bonnet, dashboard, speedometer, fuel gauge and steering wheel.
Everything is drawn and synthesised in code: **no image files, no sound files, no game engine**.

- APK size: roughly 1 MB
- RAM: about 30–50 MB at runtime
- Min Android: 5.0 (API 21)

## Remote control

| Button | Action |
|---|---|
| UP (hold) | Accelerator |
| DOWN (hold) | Brake |
| LEFT / RIGHT | Steering |
| OK | Change gear when stopped (P, D, R, N) |
| 1 or RED | Left indicator |
| 3 or GREEN | Right indicator |
| 2 or YELLOW | Hazard lights |
| 4 or BLUE | Horn |
| BACK | Pause |

Phone touch: left/right sides of the upper screen steer, bottom-right is gas, bottom-left is brake,
centre taps change gear.

## Game modes

- **Driving Test**: one lap, start with 100 points, finish with 70 or more to pass.
  Points are lost for red lights, crashes, hitting walls, speeding, missing indicators and leaving the road.
- **Free Drive**: no score, just practise.
- Press OK in **P** (Park) to start, shift to **D** (Drive). Stop at the petrol pump on the right,
  shift to P and wait to refuel.
- Main menu lets you switch **Graphics LITE/BETTER** and **Sound ON/OFF**.

## Sound (all generated in code)

Engine note that rises with speed and throttle, indicator tick-tock, horn, gear thunk, crash,
penalty beep, menu click, pass and fail jingles.

## Build the APK on GitHub

1. Create a new GitHub repository and upload all files from this folder (keep the `.github` folder).
2. Open the **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the **LiteDrivingSchool-apk** artifact (a zip containing `app-release.apk`).
4. Copy the APK to the TV with a USB stick, or use `adb install app-release.apk`.
   On the TV, allow "Install unknown apps" for your file manager. Launch it from the TV home screen.

The release APK is signed with the standard Android debug key, so no keystore setup is needed.

## Build locally

Install JDK 17, the Android SDK (platform 34) and Gradle 8.7, then run `gradle assembleRelease`.
