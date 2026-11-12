# Third-party notices

Circle to Share is licensed under the GNU General Public License v3.0 or
later (see [LICENSE](LICENSE)). It is built with the open-source components
below, which keep their own licenses.

## Apache License 2.0

Full text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)

| Component | Copyright |
|---|---|
| AndroidX (Jetpack) libraries | The Android Open Source Project |
| Material Components for Android | The Android Open Source Project |
| Material Icons and Material Symbols | Google LLC |
| ZXing (`com.google.zxing:core`) | ZXing authors |
| Kotlin standard library | JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines | JetBrains s.r.o. and contributors |
| Guava ListenableFuture | The Guava Authors |
| Error Prone annotations | The Error Prone Authors |
| JSpecify | The JSpecify Authors |
| JetBrains Java Annotations | JetBrains s.r.o. |

The Material Icons and Symbols are in `app/src/main/res/drawable` and
`app/src/play/res/drawable`; each file's header comment names its symbol and
says where it was changed (for example redrawn on a different grid).

## Google Play build only

The Google Play build (`-Pplay`) adds Google Play Billing for in-app tips.
The default F-Droid / GitHub (foss) build contains none of these. Play
Billing and Play services are not open source: they are used under the
Android Software Development Kit License.

| Component | Copyright | License |
|---|---|---|
| Google Play Billing Library | Google LLC | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| Google Play services (base, basement, tasks, location, places-placereport) | Google LLC | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| Google Data Transport | Google LLC | Apache License 2.0 |
| Firebase Encoders | Google LLC | Apache License 2.0 |
| javax.inject | The JSR-330 Expert Group | Apache License 2.0 |

Google's libraries also contain open-source code compiled into them
(Protocol Buffers, Dagger, JSR 305, zlib, ICU and others). Each AAR lists
it, with its license, in `third_party_licenses.json`/`.txt`. The Play build
gathers these into the app at build time (`GooglePlayNoticesTask` in
`app/build.gradle.kts`), and the licenses page shows them under "Inside
Google's libraries".

## Keeping this current

When dependencies change, compare against

```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew -Pplay :app:dependencies --configuration playReleaseRuntimeClasspath
```

(the notices inside Google's libraries follow by themselves) and update this
file together with `components` in
`app/src/main/java/eu/woshicado/circletoshare/LicensesActivity.kt` and
`storeComponents` in `app/src/{foss,play}/java/.../StoreComponents.kt`.
