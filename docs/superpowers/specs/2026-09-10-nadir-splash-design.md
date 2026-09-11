# Nadir capture step and cinematic splash

Date: 2026-09-10

## Goal

1. After the usual sphere rings (including existing downward bands), add one dedicated **feet / nadir** shot.
2. Show a dark cinematic **splash** on launch: a glowing globe spins once, then “Photo Sphere” fades in.

## Capture

- **Sphere** scope only. Horizon-ring mode is unchanged.
- Existing adaptive rings stay as they are (horizon, up, down, pole-covering caps).
- After the last down ring, append **one extra band** with a single target at **−85°** elevation (`SphereTargetPlan.NADIR_ELEVATION_DEGREES`).
- −85° rather than −90° so the rotation-vector yaw does not gimbal-lock while the user aims at the ground.
- The nadir target’s yaw is the same start bearing the rest of the plan used.
- When the active target is the nadir, the HUD hint is **“Point the camera at your feet.”** While the shutter is dwelling, keep **“Hold still…”**.
- Welcome overlay adds a fifth step: after the rings, tilt the camera down for the ground.
- Stitcher is unchanged: it already places frames from measured pose.

## Splash

- Dedicated `SplashActivity` is the launcher. Dark `windowBackground` matching the app so there is no white flash.
- Custom `SplashGlobeView` draws a glowing wireframe globe (latitude/longitude, accent glow). One spin, then the title fades in. About **2.2 s** total, then `MainActivity`.
- Do not replay on configuration change (`savedInstanceState != null` skips ahead).
- `noHistory` so Back from capture does not return to the splash.
- Java 8 + `View` / `ValueAnimator` only. No new libraries. SDK 29.

## Tests

- Sphere FOV plans end with a single nadir ring at −85°.
- Ring FOV plans do not include a nadir.
- Existing first-ring-equals-horizon-ring behaviour still holds.
