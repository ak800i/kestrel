# sherpa-onnx's native code reads the fields of its configuration classes and creates its result classes through JNI,
# by their names, so a shrinker must keep them. Kestrel's release build does not shrink code today.
-keep class com.k2fsa.sherpa.onnx.** { *; }
