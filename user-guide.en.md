# Alert-kun: receiving mail and server deletion

Alert-kun requires Android 8.0 (API 26) or later. See the [Japanese user guide](user-guide.md) for the complete screen-by-screen instructions, and the [Privacy Policy](PRIVACY.md) for data handling.

**A MyDNS.JP account is required to use FCM-based periodic monitoring.** Enter and save the account's MasterID and MasterPwd under “MyDNS.JP User Information” in Settings.

## Recommended mailbox and message format

Use a mailbox dedicated to Alert-kun or monitoring notifications. Sharing a personal or general business mailbox is not recommended, especially when server deletion is enabled. This is a recommendation, not a registration restriction.

Plain text (`text/plain`) is recommended for predictable notification rules. HTML is supported. When a multipart alternative contains both plain text and HTML, Alert-kun uses the plain text instead of combining both representations.

## Body search range

New messages are received and saved before their enabled rules are evaluated. The saved body and the input to body rules are limited to the first **16 KiB (16,384 UTF-8 bytes)** of decoded body text. This limit applies after MIME transfer decoding, character decoding, HTML text extraction, and joining body parts. Characters are never split. Separators between parts count toward the same per-message limit.

A search term appearing only after this prefix will not match. Attachments, including text attachments and attached messages, are not saved, displayed, or evaluated. Previously received messages are not downloaded or evaluated again when rules change. Recovery uses the body text already saved on the device.

## Message size and mail servers

Alert-kun assumes a standards-compliant mail server. POP3, POP3S, IMAP4, and IMAPS support raw messages up to **16 MiB (16,777,216 bytes)**. Exactly 16 MiB is allowed when its size is known. Messages exceeding the size or safe MIME structure limits are recorded as received but unsupported. They do not trigger rules or alarms and do not block later messages.

POP3 can transfer an entire permitted message, including attachments. Raw data is processed temporarily in private app storage and removed afterward; only the bounded body text is saved. Camera notifications with image attachments remain eligible when their total raw size is within 16 MiB.

IMAP and IMAPS receive raw messages in bounded portions. Attachment data can pass over the connection but is not saved, displayed, or evaluated. Receiving mail does not mark it as read.

## Server deletion

POP3, POP3S, IMAP4, and IMAPS share the same account setting: immediately, after 1 day, 1 week, 10 days, 15 days, 1 month, or never. The default is never. Deadlines start at Alert-kun's first local record, not the message's Date header. One month means a UTC calendar month.

Successfully processed messages and unsupported terminal messages are eligible. Deletion happens in a separate phase after the entire check completes, its selected alarm is handed off when necessary, and local processing is finalized. An interrupted check does not proceed to deletion.

IMAP deletion sets the deletion flag on eligible messages and performs ordinary EXPUNGE. Messages previously flagged for deletion by another mail client may also be removed. A dedicated mailbox is strongly recommended.

Device retention is a separate setting. Removing local content retains the minimum identity record needed to prevent downloading and alarming on the same mail again.

## Monitoring status notification

An incoming FCM check request shows “Checking mail”. The same notification is updated only after the whole mail check, including any retries, finishes. A successful check shows “No issues” or the selected matching alert. A failed check shows the current failure count and configured threshold until the threshold is reached, then the consecutive failure count. A later successful check resets the count to zero. The result timestamp is the completion time. Backup and manual checks use the same result display; skipped checks do not report success. Turning monitoring off removes this notification. Alert and consecutive-failure notifications remain separate.
