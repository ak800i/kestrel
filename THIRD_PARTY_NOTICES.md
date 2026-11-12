# Third-party notices

Kestrel's release build includes the libraries below, which use the Apache License 2.0. OkHttp also bundles a copy of the Public Suffix List, which uses the Mozilla Public License 2.0. Both licences are compatible with Kestrel's licence, GPL-3.0-or-later. The exact library versions are in `app/build/reports/kestrel-audit/dependencies-release.txt` after `scripts\gradle.ps1 kestrelAudit` runs.

| Component | Licence | Project |
|---|---|---|
| AndroidX, including Jetpack Compose, Room, Activity, Core, Lifecycle and Startup (`androidx.*`) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library (`org.jetbrains.kotlin`) | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines and kotlinx.serialization (`org.jetbrains.kotlinx`) | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization |
| JetBrains Java Annotations (`org.jetbrains:annotations`) | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| OkHttp (`com.squareup.okhttp3`) | Apache-2.0 | https://github.com/square/okhttp |
| Okio (`com.squareup.okio`) | Apache-2.0 | https://github.com/square/okio |
| Guava ListenableFuture (`com.google.guava:listenablefuture`) | Apache-2.0 | https://github.com/google/guava |
| JSpecify (`org.jspecify:jspecify`) | Apache-2.0 | https://github.com/jspecify/jspecify |
| Public Suffix List, bundled by OkHttp as `assets/PublicSuffixDatabase.list` | MPL-2.0 | https://publicsuffix.org (source: https://github.com/publicsuffix/list) |

The text of the Apache License 2.0 is at https://www.apache.org/licenses/LICENSE-2.0. The text of the Mozilla Public License 2.0 is at https://mozilla.org/MPL/2.0/.

Later sub-projects add speech models, voices, and code and data derived from OpenClaw and android_world. Their notices are added here when they arrive.
