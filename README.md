# PROG2007 Lecture 10: Privacy, Permissions and Security 

Code for Lecture 10 of PROG2007 Mobile Programming. One Android app, three tabs, one tab per live demo. All the app code is in a single file, `MainActivity.kt`, so you can read it top to bottom.

| Tab | Demo | What it shows |
|---|---|---|
| 1 Permissions | Allow, deny, deny again, Settings | Ask for location only when needed, and keep the rest of the app working when the answer is no |
| 2 Storage | Plain vs encrypted | The same fake token saved in plain text and encrypted with Tink and a Keystore key |
| 3 My data | Export, delete, retention | Export my data as JSON, delete my data, and an automatic cleanup with WorkManager |

All names, addresses and tokens in the app are fake. This is a teaching demo, not a production app.

## What you need

- Android Studio (current stable version)
- An emulator with a **Google Play** system image (the location library needs Google Play services), or a real phone
- A project with **minSdk 24 or higher**

## Part 1. Create the project

1. In Android Studio choose **New Project** and then **Empty Activity** (Jetpack Compose).
2. Name: `Lect10PermissionSecurity`
3. Package name: `com.example.lect10permissionsecurity`
4. Minimum SDK: 24 or higher.
5. Finish and wait for the first Gradle sync.

If you use a different package name, change the first line of `MainActivity.kt` and replace `com.example.lect10permissionsecurity` in the commands below.

## Part 2. Add the dependencies (Gradle)

### 2.1 `gradle/libs.versions.toml`

Add this line under `[libraries]`:

```toml
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
```

### 2.2 Root `build.gradle.kts`

Add the KSP line inside `plugins { }`. Keep the lines your template already has.

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
```

### 2.3 `app/build.gradle.kts`

Add the KSP plugin in `plugins { }`. Do not add `kotlin-android`; newer Android Gradle plugin versions have Kotlin built in.

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}
```

Add these inside `dependencies { }`, below the lines that are already there:

```kotlin
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room (Demo 3 data)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // WorkManager (Demo 3 retention cleanup)
    implementation("androidx.work:work-runtime:2.12.0")

    // Location (Demo 1)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // DataStore + Tink (Demo 2 encrypted token)
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.crypto.tink:tink-android:1.18.0")
```

Click **Sync Now**. If Gradle cannot find a version, use the newest version of that library from [Google's Maven repository](https://maven.google.com) or [Maven Central](https://central.sonatype.com).

## Part 3. Manifest

Replace `app/src/main/AndroidManifest.xml`. If your theme has a different name, keep your own `android:theme` line.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Foreground location only. No background location. -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Lect10PermissionSecurity">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Demo 3: share the exported JSON file safely -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

## Part 4. XML resources

Right-click `res`, choose **New > Android Resource Directory**, set the type to `xml`, and create these four files in `res/xml/`.

### `data_extraction_rules.xml` (Android 12 and up)

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="route.db" />
        <exclude domain="database" path="route.db-wal" />
        <exclude domain="database" path="route.db-shm" />
        <exclude domain="sharedpref" path="plain.xml" />
        <exclude domain="sharedpref" path="tink_prefs.xml" />
        <exclude domain="file" path="datastore/" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" path="route.db" />
        <exclude domain="database" path="route.db-wal" />
        <exclude domain="database" path="route.db-shm" />
        <exclude domain="sharedpref" path="plain.xml" />
        <exclude domain="sharedpref" path="tink_prefs.xml" />
        <exclude domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

### `backup_rules.xml` (Android 11 and lower)

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="database" path="route.db" />
    <exclude domain="database" path="route.db-wal" />
    <exclude domain="database" path="route.db-shm" />
    <exclude domain="sharedpref" path="plain.xml" />
    <exclude domain="sharedpref" path="tink_prefs.xml" />
    <exclude domain="file" path="datastore/" />
</full-backup-content>
```

### `network_security_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- No plain http, ever -->
    <base-config cleartextTrafficPermitted="false" />
    <!-- Debug builds only: lets you inspect traffic with a proxy you installed -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

### `file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="exports" path="exports/" />
</paths>
```

## Part 5. Add the code

Open `MainActivity.kt` in this repository and copy all of it. In Android Studio, open `app/src/main/java/com/example/lect10permissionsecurity/MainActivity.kt`, delete everything in it, and paste the copied code.

## Part 6. Run it

1. Start an emulator with a **Google Play** image and press the green **Run** button.
2. **Uninstall the app first** if you want a clean start. This resets the permissions and the stored data.
3. The app opens on a start screen. Tap **Start demo**.

## Demo 1: Permissions (tab 1)

Logcat filter: `tag:RouteService`

1. Nothing is asked at launch. Stay on tab 1.
2. Tap **Start tracking**. The system dialog appears. Choose **Approximate** and **Don't allow**. The app shows a denied message, but the delivery notes still work.
3. Tap **Start tracking** again and choose **Don't allow** again.
4. Tap a third time: no dialog appears. Android has stopped asking. The request log says "no dialog: Android answered at once".
5. Go to **Settings > Apps > this app > Permissions > Location** and choose **Allow only while using the app**. Return to the app (Android may restart it).
6. Tap **Start tracking**. It records. On the emulator, set a location or play a route in **Extended controls (three dots) > Location**, or no fix arrives.
7. Tap Attach photo to pick an image with the system photo picker. No permission dialog appears, because the app only receives the photo you choose.

Optional: repeat with **Only this time** and leave the app. When you come back, tracking has stopped.

## Demo 2: Plain vs encrypted (tab 2)

1. Type a fake token (for example `demo-token-1234`) and tap **Save plain**.
2. Read the file in a terminal: `adb shell run-as com.example.lect10permissionsecurity cat shared_prefs/plain.xml`. The token is on screen. The first grey card in the app shows the same file.
3. Tap **Save encrypted** with the same token.
4. Read the encrypted file: `adb shell run-as com.example.lect10permissionsecurity cat files/datastore/secure.preferences_pb`, or use the second card. It is an unreadable block of letters and numbers.
5. Tap **Show saved token**. The app decrypts it and shows the token.
6. Optional: tap **Tamper**, then **Show saved token**. Decryption fails, because AES-GCM detects the change.

`run-as` works only on debug builds. A real attacker needs a rooted phone, a backup copy or a forensic tool. Whichever way they get the file, they get what is in it.

## Demo 3: My data (tab 3)

1. Tap **Add sample data** (fake: 20 route points and 4 notes, half of them 40 days old).
2. Tap **Export my data**. The app shows the JSON in a card, and the share sheet opens. The upload token is not in the export.
3. Keep **30** days and tap **Run cleanup now**. The old half disappears (route points 20 to 10, notes 4 to 2) and the status line says `Cleanup SUCCEEDED`. Optional: switch on the daily auto-cleanup.
4. Tap **Delete my data** and confirm. The counters show 0, and on tab 2 the stored token is gone.
5. Optional: in Android Studio open **App Inspection** (search with Shift twice), then **Database Inspector** and select `route.db`. The tables `route_points` and `notes` show the rows before the delete and are empty after it. **Background Task Inspector** shows the cleanup as a WorkManager job.

## Troubleshooting

| Problem | What to do |
|---|---|
| `adb` is not recognized (Windows) | Use the full path, for example `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices`, or add the SDK `platform-tools` folder to PATH |
| `run-as` says the package is not debuggable | Run the app as a debug build from Android Studio |
| `cat` says "No such file or directory" | Tap Save plain or Save encrypted first |
| No location fix arrives | Set a location in the emulator's Extended controls |
| Database Inspector or App Inspection is missing | Run the app from Android Studio, then search for "App Inspection" (Shift twice) |
| `Call requires API level 26` for `java.time` | This version uses `SimpleDateFormat`, so it works on API 24 and up |
| Gradle cannot find a library version | Use the newest version of that library |

## What is deliberately left out

- The Maps SDK and API keys. The route is shown as text. Never commit a key to Git; keep it in `local.properties`.
- The rationale dialog and the **Open Settings** button. They are part of the in-class exercise.
- Background location. The app only uses location while it is in use.

## Notes

- The app logs coordinates with `Log.d` so you can follow the route in Logcat. Fine in a lecture, remove it in a release build.
- The "no dialog" label in the request log is a timing check (an answer in under 400 ms), meant as a teaching aid. It is not an official API.
- Privacy and GDPR points in the lecture are general information, not legal advice. Always check the current rules.

## Course

PROG2007 Mobile Programming, Week 43, Lecture 10: Privacy, Permissions and Security.
