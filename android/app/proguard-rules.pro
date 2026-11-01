# Services / receivers / activities / application are instantiated by the Android
# framework by name, so they must survive R8.
-keep class com.controlswitch.InputMirrorSupervisorService { *; }
-keep class com.controlswitch.InputMirrorBootReceiver { *; }
-keep class com.controlswitch.MainActivity { *; }
-keep class com.controlswitch.MainApplication { *; }
