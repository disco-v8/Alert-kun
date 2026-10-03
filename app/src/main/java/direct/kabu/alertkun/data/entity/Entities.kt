package direct.kabu.alertkun.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern

@Entity(tableName = "accounts")
data class MailAccountEntity(@PrimaryKey val id: String, val name: String, val protocol: MailProtocol, val host: String, val port: Int, val username: String, val credentialKey: String, val deleteAfterReceive: Boolean, val trustedCertificateId: String?, val enabled: Boolean)

@Entity(tableName = "rules")
data class AlertRuleEntity(@PrimaryKey val id: String, val name: String, val searchText: String, val target: RuleTarget, val priority: Int, val soundUri: String?, val vibrationPattern: VibrationPattern, val alarmMode: AlarmMode, val enabled: Boolean)

@Entity(tableName = "received_mails", indices = [Index(value = ["accountId", "remoteMessageId"], unique = true)])
data class ReceivedMailEntity(@PrimaryKey val id: String, val accountId: String, val remoteMessageId: String, val receivedAtEpochMillis: Long, val from: String?, val headersJson: String, val subject: String?, val body: String?, val bodyFetched: Boolean, val processingState: MailProcessingState, val alertTriggered: Boolean, val matchedRuleId: String?)
