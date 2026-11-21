# Keep data models
-keep class com.aura.soundboard.models.** { *; }

# Keep SQLite and Android View constructors
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Remove verbose debug logs in production release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
