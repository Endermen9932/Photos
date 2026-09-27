# ONNX Runtime uses JNI and reflection on its Java API.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# kotlinx.serialization (navigation routes)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.lumen.photos.** {
    *** Companion;
}
-keepclasseswithmembers class app.lumen.photos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ExifCopier enumerates all TAG_* constants via reflection.
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}
