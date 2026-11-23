# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Shizuku instantiates the shell UserService reflectively by class name.
# Keep the class name and every constructor through R8.
-keep class net.kuafuai.andee.shell.ShellService { public <init>(...); }
-keepclassmembers class net.kuafuai.andee.shell.ShellService { public <init>(...); }

# AIDL-generated Binder classes are looked up by name from the stub.
-keep class net.kuafuai.andee.shell.IShellService$Stub { *; }