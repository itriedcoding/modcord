# QuickJS is bound through JNI: native code resolves Java classes and methods by name.
# The keep pattern must match the real Java package, com.quickjs. Guessing it from the
# Maven coordinates (io.github.taoweiji.quickjs) produces a rule that matches nothing and
# silently lets R8 rename the JNI entry points, breaking every plugin call at runtime.
-keep class com.quickjs.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# apksig is used only through its public API. Its optional dependencies are absent.
-dontwarn com.android.apksig.**
-dontwarn org.bouncycastle.**
-dontwarn javax.annotation.**

-keepattributes Signature,InnerClasses,EnclosingMethod
