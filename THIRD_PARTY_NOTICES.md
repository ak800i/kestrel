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
| Apache Commons Compress (`org.apache.commons:commons-compress`), which unpacks sherpa-onnx's `.tar.bz2` model archives during the model download, with the libraries that it uses, Apache Commons IO (`commons-io:commons-io`), Apache Commons Lang (`org.apache.commons:commons-lang3`) and Apache Commons Codec (`commons-codec:commons-codec`). Copyright The Apache Software Foundation; their NOTICE files say: "This product includes software developed at The Apache Software Foundation (https://www.apache.org/)." | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| Guava ListenableFuture (`com.google.guava:listenablefuture`) | Apache-2.0 | https://github.com/google/guava |
| JSpecify (`org.jspecify:jspecify`) | Apache-2.0 | https://github.com/jspecify/jspecify |
| Public Suffix List, bundled by OkHttp as `assets/PublicSuffixDatabase.list` | MPL-2.0 | https://publicsuffix.org (source: https://github.com/publicsuffix/list) |
| sherpa-onnx 1.13.8, the Android library with ONNX Runtime linked statically (`com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime`) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime 1.28.0, the Android library that runs the OCR models (`com.microsoft.onnxruntime:onnxruntime-android`), Copyright (c) Microsoft Corporation; its own third-party notices are in `licences/onnxruntime-ThirdPartyNotices.txt`. Kestrel's app contains its native libraries, as those of sherpa-onnx, only for arm64-v8a and x86_64 | MIT | https://github.com/microsoft/onnxruntime |

The texts of the Apache License 2.0, the Mozilla Public License 2.0, the MIT License and hclust-cpp's BSD 2-clause licence are in the `licences` folder; the first two are copied from https://www.apache.org/licenses/LICENSE-2.0.txt and https://www.mozilla.org/media/MPL/2.0/index.txt. The same folder has ONNX Runtime's third-party notices, `licences/onnxruntime-ThirdPartyNotices.txt`, copied unchanged from https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/ThirdPartyNotices.txt. `LICENSE` holds the text of the GNU General Public License, version 3. Kestrel is licensed under version 3 or later, and espeak-ng's licence also requires this text. Kestrel's About page shows all of these texts, together with this file.

## OpenClaw

Kestrel's provider catalog, `catalog/providers.json`, which the app contains, is generated from the provider manifests of OpenClaw at the commit in `catalog/openclaw.lock`. The tests of `:tools:catalog-sync` contain excerpts of those manifests, and parts of Kestrel's provider code port OpenClaw's code, as the comments in that code say. `catalog/overrides.json`, which the app also contains, holds model rows, prices, regions and addresses that were copied from OpenClaw's provider code at that commit. OpenClaw is under the MIT License, whose text and OpenClaw's copyright line are in `licences/MIT.txt`.

| Component | Licence | Project |
|---|---|---|
| OpenClaw, Copyright (c) 2026 OpenClaw Foundation: the provider data in `catalog/providers.json`, the data in `catalog/overrides.json` that is copied from OpenClaw's code, the manifest excerpts in `tools/catalog-sync`'s tests, and the provider code that Kestrel ports | MIT | https://github.com/openclaw/openclaw |

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

## OCR models

Kestrel's app does not contain its OCR models either. `scripts\push-models.ps1` downloads them from ModelScope, Hugging Face and GitHub, at the addresses in `models/manifest.json`, with the SHA-256 hash of every file, and copies them to the phone. They are PaddlePaddle's PP-OCRv5 mobile models from the PaddleOCR project (https://github.com/PaddlePaddle/PaddleOCR), converted to ONNX by others, as the table says.

| Model | Licence | Project |
|---|---|---|
| PP-OCRv5's mobile text detector (`ocr-ppocrv5-det`), in RapidOCR v3.10.0's ONNX conversion | Apache-2.0 | https://www.modelscope.cn/models/RapidAI/RapidOCR |
| PP-OCRv5's mobile Latin recogniser (`ocr-ppocrv5-latin`), in iText Research's ONNX conversion at commit bb953c8fd9ebf04f344eb09c9c94bb367c39033c, with PaddleOCR's `ppocrv5_latin_dict.txt` at commit 8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5 | Apache-2.0 | https://huggingface.co/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer |
| PP-OCRv5's mobile Cyrillic recogniser (`ocr-ppocrv5-cyrillic`), in RapidOCR v3.10.0's ONNX conversion, with PaddleOCR's `ppocrv5_cyrillic_dict.txt` at commit 8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5 | Apache-2.0 | https://www.modelscope.cn/models/RapidAI/RapidOCR |

## Speech models

Kestrel's app does not contain its speech models. `scripts\push-models.ps1` downloads them from sherpa-onnx's releases on GitHub and from Hugging Face, at the addresses in `models/manifest.json`, and copies them to the phone. The manifest lists every file with its SHA-256 hash. The table below names the projects that created the models, and their licences.

| Model | Licence | Project |
|---|---|---|
| Silero VAD, Copyright (c) 2020-present Silero Team | MIT | https://github.com/snakers4/silero-vad |
| sherpa-onnx's English keyword model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, trained on GigaSpeech XL | Apache-2.0 | https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html |
| Parakeet-TDT-0.6B-v3, int8 | CC-BY-4.0 | https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 |
| Piper's voice `en_US-ljspeech-medium`, trained on the LJ Speech data, which is in the public domain | MIT | https://huggingface.co/rhasspy/piper-voices |
| espeak-ng's data for English, from the voice's sherpa-onnx package | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
| Kokoro v1.0 (Kokoro-82M) with the voice `af_heart`, an optional voice, in sherpa-onnx's package `kokoro-multi-lang-v1_0` | Apache-2.0 | https://huggingface.co/hexgrad/Kokoro-82M |

Parakeet-TDT-0.6B-v3 was created by NVIDIA (https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) and is licensed under the Creative Commons Attribution 4.0 International licence (https://creativecommons.org/licenses/by/4.0/). The sherpa-onnx project converted it to ONNX and quantised it to int8 (https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8); Kestrel uses that version unchanged.

The Kokoro voice is optional: `scripts\push-models.ps1` copies it only with `-Kokoro`, from the sherpa-onnx project's copy of the package (https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0). With it, the script copies the same model with its weights quantised to int8 from the sherpa-onnx project's int8 package (https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0), under the same Apache License 2.0; only a measure on the phone loads it. hexgrad created Kokoro-82M and licenses its weights and all its English voices under the Apache License 2.0. Kestrel uses only the voice `af_heart`. taylorchu's kokoro-onnx project converted the model to ONNX (https://github.com/taylorchu/kokoro-onnx/releases/tag/v0.2.0), and the sherpa-onnx project packaged it. The package's `voices.bin` also holds other voices; among them, the French voice `ff_siwis` and the Japanese voices were trained with data under Creative Commons Attribution licences (SIWIS, Koniwa), and Kestrel does not use them. Kokoro's model card says that it was trained only on permissive or non-copyrighted audio, including synthetic audio from closed speech models, and it does not name the data of each voice. Kestrel copies neither the package's lexicons nor its own copy of espeak-ng's data: the voice gets its phonemes from the espeak-ng data above.
