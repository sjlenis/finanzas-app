# SQLCipher usa JNI: sus clases nativas no deben renombrarse.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
