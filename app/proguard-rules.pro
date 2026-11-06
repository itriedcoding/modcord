# Rhino is loaded reflectively in a few places; keep it intact if minification is enabled.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keepattributes Signature,InnerClasses,EnclosingMethod
