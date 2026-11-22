# قواعد R8/ProGuard لتطبيق نبض الذكر

# Gson يستخدم انعكاس JSON على نماذج البيانات
-keep class com.nabdaldhikr.app.data.model.** { *; }

# TasbihDhikr يُسلسَل ويُعاد بناؤه عبر Gson
-keep class com.nabdaldhikr.app.viewmodel.TasbihDhikr { *; }

# WorkManager يكتشف العمال عبر الانعكاس
-keep class com.nabdaldhikr.app.reminder.** { *; }

# Updater Data Models
-keep class com.nabdaldhikr.app.updater.** { *; }

# Nabd AI Data Models (Gson) & MediaPipe GenAI (Gemma 3 270M)
-keep class com.nabdaldhikr.app.ai.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keepattributes *Annotation*