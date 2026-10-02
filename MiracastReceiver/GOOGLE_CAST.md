# Google Cast Connect setup

MiracastReceiver includes optional **official Google Cast Connect** support for Android TV / Google TV.

## Important limitation

Cast Connect does **not** turn an arbitrary Android box into a certified Chromecast device. The TV/device must already provide the Google Cast / Google Play services receiver infrastructure. Google Cast also requires a registered Cast application ID.

For unpublished/sideloaded development builds, Google requires the Android TV device to be registered in the Google Cast SDK Developer Console. A published Play Store app can use the normal production association.

## 1. Create/register the Cast receiver

In the Google Cast SDK Developer Console:

1. Create or select a Cast receiver application.
2. Enable Android TV / Cast Connect support.
3. Associate package name:

   `com.weekd.miracastreceiver`

4. Note the Cast App ID.
5. For sideload testing, register the TV's **Cast software serial number** as a developer device and reboot the TV after registration.

## 2. Build with the Cast App ID

Either pass a Gradle property:

```bash
./gradlew :app:assembleDebug -PgoogleCastAppId=YOUR_CAST_APP_ID
```

or export an environment variable:

```bash
export GOOGLE_CAST_APP_ID=YOUR_CAST_APP_ID
./gradlew :app:assembleDebug
```

If neither value is configured, Google Cast Connect is intentionally disabled at runtime and the rest of the receiver remains unchanged.

## 3. Sender compatibility

For a sender application you control, configure the same receiver application ID and enable Android TV receiver compatibility (`androidReceiverCompatible=true`).

Cast LOAD requests with HTTP/HTTPS media URLs are routed to MiracastReceiver's existing Media3 URL player. Play, pause, stop and seek commands are bridged through the app's MediaSession.

## What this does not provide

This integration does not bypass Google's device authentication/certification or make a non-Cast-certified ROM advertise itself as a genuine Chromecast. Implementing `_googlecast._tcp` discovery alone is intentionally not used because official sender SDKs can authenticate the receiver device and reject an unauthenticated clone.
