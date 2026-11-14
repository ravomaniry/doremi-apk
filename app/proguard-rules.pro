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
# hide the original source getFileFromName name.
#-renamesourcefileattribute SourceFile

# Apache POI, OOXML schemas, and Aalto (StAX) are loaded by reflection.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.apache.poi.schemas.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class com.fasterxml.aalto.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class javax.xml.stream.** { *; }

-keeppackagenames org.apache.poi.ss.formula.function
-keep,allowoptimization class org.apache.commons.compress.archivers.zip.** { *; }

-dontwarn java.awt.**
-dontwarn javax.xml.stream.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.apache.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.batik.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn org.etsi.**
-dontwarn org.w3.**
-dontwarn org.slf4j.impl.**
-dontwarn com.microsoft.schemas.**
-dontwarn com.graphbuilder.**
-dontwarn net.sf.saxon.**
-dontwarn com.fasterxml.aalto.**