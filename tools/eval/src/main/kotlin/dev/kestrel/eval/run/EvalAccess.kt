package dev.kestrel.eval.run

/**
 * One access of Kestrel's in the owner's kept set for the evaluation ([EvalPermissions.SET]; his decisions of 2026-11-24):
 * a runtime permission of Kestrel's manifest, or Kestrel's notification access, a special access that list_notifications
 * needs. [id] names it on the device, in `suite.json` and in messages: the permission's name, or the component of
 * Kestrel's notification listener.
 */
enum class EvalAccess(val id: String, val kind: Kind) {
    READ_CALENDAR("android.permission.READ_CALENDAR", Kind.RUNTIME_PERMISSION),
    READ_CONTACTS("android.permission.READ_CONTACTS", Kind.RUNTIME_PERMISSION),
    WRITE_CONTACTS("android.permission.WRITE_CONTACTS", Kind.RUNTIME_PERMISSION),
    READ_SMS("android.permission.READ_SMS", Kind.RUNTIME_PERMISSION),
    READ_CALL_LOG("android.permission.READ_CALL_LOG", Kind.RUNTIME_PERMISSION),
    READ_MEDIA_IMAGES("android.permission.READ_MEDIA_IMAGES", Kind.RUNTIME_PERMISSION),
    READ_MEDIA_VIDEO("android.permission.READ_MEDIA_VIDEO", Kind.RUNTIME_PERMISSION),
    NOTIFICATION_LISTENER("dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener", Kind.NOTIFICATION_LISTENER),
    ;

    /** How the tool gives an access and reads it back. */
    enum class Kind {
        /** `pm grant`, and `dumpsys package`'s runtime permissions. */
        RUNTIME_PERMISSION,

        /** `cmd notification allow_listener`, and the secure setting enabled_notification_listeners. */
        NOTIFICATION_LISTENER,
    }
}
