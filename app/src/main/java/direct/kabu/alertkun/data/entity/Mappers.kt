package direct.kabu.alertkun.data.entity

import direct.kabu.alertkun.data.database.Converters
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.ReceivedMail

private val converters = Converters()
fun MailAccount.toEntity() = MailAccountEntity(id, name, protocol, host, port, username, credentialKey, deleteAfterReceive, trustedCertificateId, enabled, serverDeletePolicy, tlsValidationMode, certificateFingerprint)
fun MailAccountEntity.toDomain() = MailAccount(id, name, protocol, host, port, username, credentialKey, deleteAfterReceive, trustedCertificateId, enabled, serverDeletePolicy, tlsValidationMode, certificateFingerprint)
fun AlertRule.toEntity() = AlertRuleEntity(id, name, searchText, target, priority, soundUri, vibrationPattern, alarmMode, enabled, backgroundColor, soundType)
fun AlertRuleEntity.toDomain() = AlertRule(id, name, searchText, target, priority, soundUri, vibrationPattern, alarmMode, enabled, backgroundColor, soundType)
fun ReceivedMail.toEntity() = ReceivedMailEntity(id, accountId, remoteMessageId, receivedAtEpochMillis, from, converters.fromHeaders(headers), subject, body, bodyFetched, processingState, alertTriggered, matchedRuleId, backgroundColor)
fun ReceivedMailEntity.toDomain() = ReceivedMail(id, accountId, remoteMessageId, receivedAtEpochMillis, from, converters.toHeaders(headersJson), subject, body, bodyFetched, processingState, alertTriggered, matchedRuleId, backgroundColor)
