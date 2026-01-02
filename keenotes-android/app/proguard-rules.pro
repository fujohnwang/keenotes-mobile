# Add project specific ProGuard rules here.

# Keep BouncyCastle classes for crypto
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Room entities
-keep class cn.keevol.keenotes.data.entity.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
