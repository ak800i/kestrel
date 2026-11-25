package dev.kestrel.app

/**
 * The app groups of Kestrel's notifications whose buttons the owner needs: the goal service's, the held goal's that Kestrel
 * posts without the service, and listening's (design 10.6 of sub-project 7; plan 7E, Task 8). Each is a group of its own
 * without a summary, so that Android's automatic grouping does not put them in one collapsed group, in which each row
 * shows one line and no buttons. What holds on which API level, in AOSP:
 * - 33 to 35: `GroupHelper.onNotificationPosted` groups only a notification for which `StatusBarNotification.isAppGroup()`
 *   is false, and it is true for a notification with a group. SystemUI's `ShadeListBuilder.pruneIncompleteGroups` shows the
 *   children of a group without a summary as rows of their own. So none of these three is grouped automatically.
 * - 36 and later, with force grouping (`notification_force_grouping`, on in kestrel-api37):
 *   `GroupHelper.onNotificationPostedWithDelay` treats a group's child without a summary as ungrouped again, and groups
 *   such notifications of one section from `config_autoGroupAtCount` (2) of them. `NotificationSectioner
 *   .isNotificationGroupable` leaves out a colorized foreground service's notification, so the goal service's notification
 *   is never grouped. Listening's notification and the held goal's posted one can still be grouped together, or with
 *   another of Kestrel's notifications in the same section.
 * kestrel-api37 checks 37 (`GoalServiceTest`); the A71's run (plan 7E, Task 9) is where 33 is checked.
 */
internal object NotificationGroups {
    /** The group of the notification with [id], alone in it. */
    fun alone(id: Int): String = "dev.kestrel.notification.$id"
}
