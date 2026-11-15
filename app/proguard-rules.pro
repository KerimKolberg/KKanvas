# Settings and collages travel to the render service (and wait there across a restart) as
# Serializable objects, which are read back by class and field name.
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
