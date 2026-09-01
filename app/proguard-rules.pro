# Project-specific ProGuard / R8 rules.
#
# The app uses no reflection or serialization libraries, so the defaults from
# proguard-android-optimize.txt plus the consumer rules shipped with AndroidX,
# Compose and kotlinx-coroutines are sufficient. Add keeps here if a future
# dependency needs them.

# Keep source file names + line numbers for readable crash stack traces,
# hiding the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
