# Add project specific ProGuard rules here.

# BouncyCastle lightweight crypto APIs are referenced directly from CryptoService;
# do not keep the whole provider package so R8 can remove unused algorithms.
-dontwarn org.bouncycastle.**

# Room entities are accessed by generated code, not by app reflection.

# Suppress optional OkHttp/Okio platform warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
