# JNI: native method names and the progress callback are looked up by name from C.
-keepclasseswithmembernames class io.github.capstasher.gako.engine.Engine {
    native <methods>;
}
-keep interface io.github.capstasher.gako.engine.Engine$ProgressListener { *; }
-keep class * implements io.github.capstasher.gako.engine.Engine$ProgressListener {
    void onProgress(float);
}
