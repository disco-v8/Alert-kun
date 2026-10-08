package direct.kabu.alertkun.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import direct.kabu.alertkun.model.MailBackgroundColor
import androidx.room.Index
import androidx.room.PrimaryKey
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import direct.kabu.alertkun.model.ServerDeletePolicy

@Entity(tableName = "accounts")
data class MailAccountEntity(@PrimaryKey val id: String, val name: String, val protocol: MailProtocol, val host: String, val port: Int, val username: String, val credentialKey: String, val deleteAfterReceive: Boolean, val trustedCertificateId: String?, val enabled: Boolean, @ColumnInfo(defaultValue = "'NEVER'") val serverDeletePolicy: ServerDeletePolicy = ServerDeletePolicy.NEVER, @ColumnInfo(defaultValue = "'STRICT'") val tlsValidationMode: direct.kabu.alertkun.model.TlsValidationMode = direct.kabu.alertkun.model.TlsValidationMode.STRICT, val certificateFingerprint: String? = null)

@Entity(tableName = "rules")
data class AlertRuleEntity(@PrimaryKey val id: String, val name: String, val searchText: String, val target: RuleTarget, val priority: Int, val soundUri: String?, val vibrationPattern: VibrationPattern, val alarmMode: AlarmMode, val enabled: Boolean, @ColumnInfo(defaultValue = "'DEFAULT'") val backgroundColor: MailBackgroundColor = MailBackgroundColor.DEFAULT, @ColumnInfo(defaultValue = "'ALARM'") val soundType: AlarmSoundType = AlarmSoundType.ALARM)

@Entity(tableName = "received_mails", indices = [Index(value = ["accountId", "remoteMessageId"], unique = true)])
data class ReceivedMailEntity(@PrimaryKey val id: String, val accountId: String, val remoteMessageId: String, val receivedAtEpochMillis: Long, val from: String?, val headersJson: String, val subject: String?, val body: String?, val bodyFetched: Boolean, val processingState: MailProcessingState, val alertTriggered: Boolean, val matchedRuleId: String?, @ColumnInfo(defaultValue = "'DEFAULT'") val backgroundColor: MailBackgroundColor = MailBackgroundColor.DEFAULT)
