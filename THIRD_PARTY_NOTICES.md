# Third-party notices

Kestrel's release build includes the libraries below. Their licences are the Apache License 2.0, the MIT License, the Mozilla Public License 2.0, a BSD 2-clause licence, the Unlicense and the GNU General Public License, version 3 or later. All of these licences are compatible with Kestrel's licence, GPL-3.0-or-later. ONNX Runtime's own third-party notices list further licences, among them the BSD 3-clause licence. That file covers the whole ONNX Runtime project, which also builds for other platforms, so it also lists libraries that Kestrel does not contain. The exact library versions are in `app/build/reports/kestrel-audit/dependencies-release.txt` after `scripts\gradle.ps1 kestrelAudit` runs.

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
| sherpa-onnx 1.13.8, the Android library with ONNX Runtime linked statically (`com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime`) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |

The texts of the Apache License 2.0, the Mozilla Public License 2.0, the MIT License and hclust-cpp's BSD 2-clause licence are in the `licences` folder; the first two are copied from https://www.apache.org/licenses/LICENSE-2.0.txt and https://www.mozilla.org/media/MPL/2.0/index.txt. The same folder has ONNX Runtime's third-party notices, `licences/onnxruntime-ThirdPartyNotices.txt`, copied unchanged from https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/ThirdPartyNotices.txt. `LICENSE` holds the text of the GNU General Public License, version 3. Kestrel is licensed under version 3 or later, and espeak-ng's licence also requires this text. Kestrel's About page shows all of these texts, together with this file.

## The parts of sherpa-onnx's library

sherpa-onnx's build links these projects into its library `libsherpa-onnx-jni.so`, so Kestrel contains them too. Because the library contains espeak-ng, the library as a whole is under the GNU General Public License, version 3 or later, like Kestrel. The versions are those that sherpa-onnx 1.13.8's build files name; kaldifst's version is the one that kaldi-decoder 0.3.0's build files name.

| Component | Licence | Project |
|---|---|---|
| ONNX Runtime 1.28.2, Copyright (c) Microsoft Corporation; its own third-party notices are in `licences/onnxruntime-ThirdPartyNotices.txt` | MIT | https://github.com/microsoft/onnxruntime |
| espeak-ng, built from https://github.com/csukuangfj/espeak-ng at commit ed530aa | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
| piper-phonemize, built from https://github.com/csukuangfj/piper-phonemize at commit f3ff95a, Copyright (c) 2023 Michael Hansen | MIT | https://github.com/rhasspy/piper-phonemize |
| uni-algo, bundled in piper-phonemize | Unlicense | https://github.com/uni-algo/uni-algo |
| kaldi-native-fbank 1.22.3 | Apache-2.0 | https://github.com/csukuangfj/kaldi-native-fbank |
| kaldi-decoder 0.3.0 | Apache-2.0 | https://github.com/k2-fsa/kaldi-decoder |
| kaldifst 1.8.0 | Apache-2.0 | https://github.com/k2-fsa/kaldifst |
| simple-sentencepiece 0.7 | Apache-2.0 | https://github.com/pkufool/simple-sentencepiece |
| OpenFst, built from https://github.com/csukuangfj/openfst at v1.8.5-2026-07-09 | Apache-2.0 | https://www.openfst.org |
| Eigen 5.0.1 | MPL-2.0 | https://gitlab.com/libeigen/eigen |
| hclust-cpp, with fastcluster, Copyright 2011 Daniel Müllner and 2018 Christoph Dalitz | BSD-2-Clause | https://github.com/cdalitz/hclust-cpp |
| JSON for Modern C++ 3.12.0, Copyright (c) 2013-2025 Niels Lohmann | MIT | https://github.com/nlohmann/json |
