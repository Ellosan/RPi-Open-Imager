# kotlinx.serialization keeps generated serializers referenced only reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.openimager.core.** {
    *** Companion;
}
-keepclasseswithmembers class dev.openimager.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Apache Commons Compress optionally links compressors we do not ship.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**
