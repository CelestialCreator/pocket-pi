# Pocket Pi proguard rules.
# Compose / Material handle their own keep rules via the Compose plugin.
-keep class kotlinx.serialization.** { *; }
-keepclassmembers,allowshrinking class kotlinx.serialization.** { *; }
