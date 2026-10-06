package direct.kabu.alertkun.data.repository

import android.content.Context
import direct.kabu.alertkun.model.MailBackgroundColor
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import direct.kabu.alertkun.data.dao.AccountDao
import direct.kabu.alertkun.data.dao.ReceivedMailDao
import direct.kabu.alertkun.data.dao.RuleDao
import direct.kabu.alertkun.data.entity.toDomain
import direct.kabu.alertkun.data.entity.toEntity
import direct.kabu.alertkun.data.preferences.settingsDataStore
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.AppLanguage
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.model.ReceivedMail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AccountRepository(private val dao: AccountDao) { val accounts: Flow<List<MailAccount>> = dao.observeAll().map { it.map { entity -> entity.toDomain() } }; suspend fun get(id: String) = dao.getById(id)?.toDomain(); suspend fun upsert(account: MailAccount) = dao.upsert(account.toEntity()); suspend fun delete(id: String) = dao.delete(id) }
class RuleRepository(private val dao: RuleDao) { val rules: Flow<List<AlertRule>> = dao.observeAll().map { it.map { entity -> entity.toDomain() } }; suspend fun get(id: String) = dao.getById(id)?.toDomain(); suspend fun allRules() = dao.observeAll().map { it.map { entity -> entity.toDomain() } }.first(); suspend fun upsert(rule: AlertRule) { require(rule.name.isNotBlank()); require(rule.searchText.isNotBlank()); require(rule.priority >= 1); dao.upsert(rule.copy(name = rule.name.trim(), searchText = rule.searchText.trim()).toEntity()) }; suspend fun delete(id: String) = dao.delete(id) }
class MailRepository(private val dao: ReceivedMailDao) { suspend fun finalizeBatch(outcomes: List<direct.kabu.alertkun.model.MailProcessingOutcome>) = dao.finalizeBatch(outcomes); val mails: Flow<List<ReceivedMail>> = dao.observeAll().map { it.map { entity -> entity.toDomain() } }; suspend fun saveReceived(mail: ReceivedMail) { require(mail.processingState == MailProcessingState.RECEIVED); dao.insertReceivedMail(mail.toEntity()) }; suspend fun getByRemoteId(accountId: String, remoteMessageId: String) = dao.getByRemoteMessageId(accountId, remoteMessageId)?.toDomain(); suspend fun exists(accountId: String, remoteMessageId: String) = dao.getByRemoteMessageId(accountId, remoteMessageId) != null; suspend fun receivedMails() = dao.getByProcessingState(MailProcessingState.RECEIVED).map { it.toDomain() }; suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor = MailBackgroundColor.DEFAULT) = dao.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor); suspend fun deleteOlderThan(epochMillis: Long) = dao.deleteOlderThan(epochMillis) }
class SettingsRepository(context: Context) { private val store = context.applicationContext.settingsDataStore; val settings: Flow<MonitoringSettings> = store.data.map { p -> MonitoringSettings(p[POLL] ?: 5, p[RETENTION] ?: 7, p[FAILURE] ?: 3, p[ENABLED] ?: false, p[CHECK_ON_APP_START] ?: true) }; val appLanguage: Flow<AppLanguage> = store.data.map { p -> p[LANGUAGE]?.let { AppLanguage.valueOf(it) } ?: AppLanguage.ENGLISH }; suspend fun update(settings: MonitoringSettings) { require(settings.pollIntervalMinutes in 1..1500); require(settings.mailRetentionDays in 1..99); require(settings.consecutiveFailureThreshold in 1..99); store.edit { p -> p[POLL] = settings.pollIntervalMinutes; p[RETENTION] = settings.mailRetentionDays; p[FAILURE] = settings.consecutiveFailureThreshold; p[ENABLED] = settings.monitoringEnabled; p[CHECK_ON_APP_START] = settings.checkImmediatelyOnAppStart } }; suspend fun updateAppLanguage(language: AppLanguage) { store.edit { it[LANGUAGE] = language.name } }; companion object { private val POLL = intPreferencesKey("poll_interval_minutes"); private val RETENTION = intPreferencesKey("mail_retention_days"); private val FAILURE = intPreferencesKey("consecutive_failure_threshold"); private val ENABLED = booleanPreferencesKey("monitoring_enabled"); private val CHECK_ON_APP_START = booleanPreferencesKey("check_immediately_on_app_start"); private val LANGUAGE = androidx.datastore.preferences.core.stringPreferencesKey("app_language") } }
