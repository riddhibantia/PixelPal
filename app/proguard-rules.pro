# =============================================================================
# PixelPal — R8 rules for the minified release build
# (isMinifyEnabled = true, isShrinkResources = true)
#
# House rules:
#  * A `-keep` must be justified by a reflective/late-bound contract
#    (Hilt, Room, kotlinx.serialization, Firestore toObject, WorkManager,
#    assets/Lottie). If the framework documents its own consumer rules, rely
#    on those and keep nothing here.
#  * `-dontwarn` is only for optional dependencies that libraries reference
#    but we never load at runtime. Every entry below says why.
#  * Prefer narrow `-keepclassmembers` over `-keep class * { *; }`.
# =============================================================================

# Annotations drive most of the above (serialization descriptors, Room, Hilt,
# Firestore field names). Signature/InnerClasses keep stack traces and generic
# signatures readable after obfuscation.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# -----------------------------------------------------------------------------
# Hilt / Dagger — generated factories are looked up reflectively by
# generated aggregate classes, which are invoked from generated entry points.
# -----------------------------------------------------------------------------
-keep class dagger.hilt.android.internal.managers.ViewComponentManager { *; }
-keep class dagger.hilt.android.internal.lifecycle.HiltViewModelFactory { *; }
-keep class dagger.hilt.internal.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel *;
}
-keep,allowobfuscation @interface dagger.hilt.android.lifecycle.HiltViewModel
-keepnames @javax.inject.Singleton class *

# -----------------------------------------------------------------------------
# Room — the generated *_Impl is instantiated by name/reflection and the
# DAOs are resolved via generated code; entities are reconstructed from
# cursors by generated adapters (kept for the column-name contract).
# -----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keep class * extends androidx.room.RoomDatabase$Callback { *; }

# -----------------------------------------------------------------------------
# kotlinx.serialization — the plugin generates a synthetic `$serializer`
# companion for every @Serializable class; R8 must not strip it or rename the
# fields the generated descriptor references.
#   Used by: DialoguePack (res/raw dialogue_*.json), AgentEnvelope
#   (GenericHttpAgentConnector), WsAgentMessage (WebSocketAgentConnector),
#   PendingApproval (approval notifications), CrashRecord (crash log file).
# -----------------------------------------------------------------------------
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,allowobfuscation,allowshrinking class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Explicit names for the wire-format models: logs and future schema changes are
# far easier to reason about if class names survive. The connector envelopes are
# *private nested* classes, hence the `Outer$Inner` binary names.
-keep class com.pixelpal.app.data.remote.GenericHttpAgentConnector$AgentEnvelope { *; }
-keep class com.pixelpal.app.data.remote.WebSocketAgentConnector$WsAgentMessage { *; }
-keep class com.pixelpal.app.data.dialogue.DialogueLine { *; }
-keep class com.pixelpal.app.data.dialogue.DialoguePack { *; }
-keep class com.pixelpal.app.domain.model.PendingApproval { *; }
-keep class com.pixelpal.app.PixelPalApplication$CrashRecord { *; }
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# -----------------------------------------------------------------------------
# Firebase Firestore — data classes are populated with `toObject()` via
# reflection: the no-arg constructor and the exact field names must survive
# or every cloud read silently comes back with default values.
#   Cloud paths: users/{uid}/companion/primary, tasks|reminders|subtasks/{cloudId},
#   metrics/bond.
# -----------------------------------------------------------------------------
-keep class com.pixelpal.app.data.remote.firebase.** { *; }
-keepclassmembers class com.pixelpal.app.data.remote.firebase.** {
    <init>(...);
    <fields>;
}
-keepnames class com.google.firebase.** { *; }

# -----------------------------------------------------------------------------
# Firebase Auth + Credential Manager — class names are looked up for the
# Google Play Services task/result plumbing.
# -----------------------------------------------------------------------------
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**

# -----------------------------------------------------------------------------
# WorkManager — workers are instantiated by class name through the Hilt
# WorkerFactory / WorkerFactory fallback (reflection).
# -----------------------------------------------------------------------------
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * implements androidx.work.ListenableWorker { *; }
-keepclassmembers class * {
    @androidx.work.WorkerFactory *;
}
-keepnames class * extends androidx.work.ListenableWorker
-dontwarn androidx.work.impl.**

# -----------------------------------------------------------------------------
# Lottie — animations load `res/raw/pet_<species>_<state>.json`; Lottie's
# reflection model (classes referenced by FullyQualifiedName + asset ids) is
# not R8-visible. Shrinking these breaks the floating pet.
# -----------------------------------------------------------------------------
-keep class com.airbnb.lottie.** { *; }
-keep class com.airbnb.lottie.compose.** { *; }
-keep class com.airbnb.lottie.value.** { *; }

# -----------------------------------------------------------------------------
# DataStore Preferences — proto-lite serialization of the preference bag.
# -----------------------------------------------------------------------------
-keep class androidx.datastore.preferences.protobuf.** { *; }
-dontwarn androidx.datastore.**

# -----------------------------------------------------------------------------
# CameraX + ML Kit (QR pairing) — receiver adapters/barcode modules are
# discovered by class name by their own registries.
# -----------------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

# -----------------------------------------------------------------------------
# Google Generative AI SDK — json/gRPC-free REST; the SDK references optional
# javax annotations and protobuf bits that are never used on Android.
# -----------------------------------------------------------------------------
-dontwarn com.google.ai.client.generativeai.**
-dontwarn com.google.gson.**

# -----------------------------------------------------------------------------
# OkHttp — optional platform integrations.
# -----------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -----------------------------------------------------------------------------
# General
# -----------------------------------------------------------------------------
# Parcelable CREATOR is resolved by field name by the framework.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
# Seed / avatar names are hashed for a deterministic look; keep enum name() stable
# for persisted enum columns (Room stores enum names as strings).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
# Kotlin reflection is not used at runtime; the stdlib handles the defaults.
-dontwarn kotlin.reflect.jvm.internal.**
