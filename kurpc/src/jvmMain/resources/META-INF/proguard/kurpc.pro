# libkurpc finds these by name over JNI (plan §5.2): the native methods, the fields of the config
# it reads, and the callback methods it calls on Kotlin objects. Renaming or removing any of
# them fails at run time, not at build time.
-keep class io.github.xchacha20_poly1305.kurpc.NativeBridge {
    native <methods>;
}
-keep class io.github.xchacha20_poly1305.kurpc.NativeChannelConfig {
    <fields>;
}
-keep interface io.github.xchacha20_poly1305.kurpc.CallFailureCallback { *; }
-keep interface io.github.xchacha20_poly1305.kurpc.UnaryCallback { *; }
-keep interface io.github.xchacha20_poly1305.kurpc.StreamCallback { *; }
-keep interface io.github.xchacha20_poly1305.kurpc.SendCallback { *; }
-keep interface io.github.xchacha20_poly1305.kurpc.NativeDialer { *; }
-keepclassmembers class * implements io.github.xchacha20_poly1305.kurpc.CallFailureCallback {
    public <methods>;
}
-keepclassmembers class * implements io.github.xchacha20_poly1305.kurpc.SendCallback {
    public <methods>;
}
-keepclassmembers class * implements io.github.xchacha20_poly1305.kurpc.NativeDialer {
    public <methods>;
}
