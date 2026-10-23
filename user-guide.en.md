# Alert-kun user guide

Alert-kun requires Android 8.0 (API 26) or later. See the [Japanese user guide](user-guide.md) for the complete screen-by-screen instructions, and the [Privacy Policy](PRIVACY.md) for data handling.

**A MyDNS.JP account is required to use FCM-based periodic monitoring.** Enter and save the account's MasterID and MasterPwd under “MyDNS.JP User Information” in Settings.

## Automatic device confirmation

Gateway registration completes only after MyDNS.JP authentication and automatic FCM confirmation by the app instance that initiated the request. No additional screen or approval is required. Confirmation messages do not start mail checks or alerts. Saving settings locally does not mean registration has completed.

FCM delivery and background execution may be delayed. A pending request lasts 30 minutes, with limited retries. After those retries, opening the app, changing settings or a token update provides the next opportunity. Pending or failed confirmation preserves an existing valid registration and backup checks. There is no fallback to the old registration protocol.

One MasterID can serve multiple devices. A new owner can enter their own MyDNS.JP credentials on a transferred device and complete automatic confirmation without the previous owner's approval or password. Successful registration, including re-registration, resets the sending reference; pending requests do not.

## Recommended mailbox and message format

Use a mailbox dedicated to Alert-kun or monitoring notifications. Sharing a personal or general business mailbox is not recommended, especially when server deletion is enabled. This is a recommendation, not a registration restriction.

Plain text (`text/plain`) is recommended for predictable notification rules. HTML is supported. When a multipart alternative contains both plain text and HTML, Alert-kun uses the plain text instead of combining both representations.

## Body search range

New messages are received and saved before their enabled rules are evaluated. The saved body and the input to body rules are limited to the first **16 KiB (16,384 UTF-8 bytes)** of decoded body text. This limit applies after MIME transfer decoding, character decoding, HTML text extraction, and joining body parts. Characters are never split. Separators between parts count toward the same per-message limit.

A search term appearing only after this prefix will not match. Attachments, including text attachments and attached messages, are not saved, displayed, or evaluated. Previously received messages are not downloaded or evaluated again when rules change. Recovery uses the body text already saved on the device.

## Message size and mail servers

Alert-kun assumes a standards-compliant mail server. POP3, POP3S, IMAP4, and IMAPS support raw messages up to **16 MiB (16,777,216 bytes)**. Exactly 16 MiB is allowed when its size is known. Messages exceeding the size or safe MIME structure limits are recorded as received but unsupported. They do not trigger rules or alarms and do not block later messages.

Unsupported messages show an explanatory message in the received-mail list and details. Other processing states, matched rule names and alarm flags are not displayed.

POP3 can transfer an entire permitted message, including attachments. Raw data is processed temporarily in private app storage and removed afterward; only the bounded body text is saved. Camera notifications with image attachments remain eligible when their total raw size is within 16 MiB.

IMAP and IMAPS receive raw messages in bounded portions. Attachment data can pass over the connection but is not saved, displayed, or evaluated. Receiving mail does not mark it as read.

## Server deletion

POP3, POP3S, IMAP4, and IMAPS share the same account setting: immediately, after 1 day, 1 week, 10 days, 15 days, 1 month, or never. The default is never. Deadlines start at Alert-kun's first local record, not the message's Date header. One month means a UTC calendar month.

Successfully processed messages and unsupported terminal messages are eligible. Deletion happens in a separate phase after the entire check completes, its selected alarm is handed off when necessary, and local processing is finalized. An interrupted check does not proceed to deletion.

IMAP deletion sets the deletion flag on eligible messages and performs ordinary EXPUNGE. Messages previously flagged for deletion by another mail client may also be removed. A dedicated mailbox is strongly recommended.

Device retention is a separate setting. Removing local content retains the minimum identity record needed to prevent downloading and alarming on the same mail again.

## Alarm permissions and screen-off playback

Allow notifications. Continuous alarm auto-start also requires the Alarms & reminders permission on supported Android versions. On Android 17 or later, Alarm-category alerts with 1, 3, or 5 cycles use the exact-alarm entry when both permissions are available, as a screen-off playback compatibility measure. Notification and Ringtone sound categories retain their existing entry.

Without exact-alarm permission, finite alerts retain the regular service entry, but background audio may be restricted. A continuous alarm that cannot auto-start, or an alert whose service cannot start, uses the existing explanatory notification and vibration fallback when available. The app does not change volume, Do Not Disturb or device settings, and does not guarantee playback under every OS restriction.

## Monitoring state notification

If a real mail check fails on cellular and the OS reports restrictions for that read attempt's network, the notification suggests checking the app's Unrestricted mobile data setting. It does not assert that the setting is off. The advice is absent for Wi-Fi, successful checks, authentication errors, unknown restriction state and Demo Mode, and disappears after recovery. It is independent of the silent-notification recommendation.

An incoming FCM check request shows “Checking mail”. The same notification is updated only after the whole mail check, including any retries, finishes. A successful check shows “No issues” or the selected matching alert. A failed check shows the current failure count and configured threshold until the threshold is reached, then the consecutive failure count. A later successful check resets the count to zero. The result timestamp is the completion time. Backup and manual checks use the same result display; skipped checks do not report success. Turning monitoring off removes this notification. Alert and consecutive-failure notifications remain separate.

## Once-daily Monitoring status notification

The separate Monitoring status notification is enabled by default and reports healthy monitoring and the number of selected alerts over the past 24 hours. Its first report waits for 24 hours after the first successful check; later reports occur on the first successful check at least 24 hours after the previous report. Failures during that period suppress the normal daily report. This notification uses a separate silent channel and is distinct from the per-check Monitoring state notification.

## Self-builds and the official FCM service

The Google Services Gradle plugin is always applied, so building the public source requires a local `app/google-services.json` for your own Firebase project. Do not commit that file. A token issued by your own project cannot receive checks from the official MyDNS.JP Gateway as-is: the token and sender must belong to the same Firebase project, and this client currently targets the official Gateway. Use the official distributed APK for official FCM monitoring. Testing your own project needs a matching sender and an appropriate client endpoint configuration. See the [Firebase error-code documentation](https://firebase.google.com/docs/cloud-messaging/error-codes).

## Private questions and data deletion requests

Use the [MyDNS.JP contact form](https://www.mydns.jp/contact-us/) for Alert-kun questions that should not be public, including Gateway registration-data deletion requests requiring identity verification. Turning monitoring off or uninstalling the app does not immediately delete Gateway records. Do not post account details, credentials or personal information in public GitHub Issues, and never send passwords or tokens in an inquiry. See the [Privacy Policy](PRIVACY.md) for data handling and the bilingual contact guidance.
