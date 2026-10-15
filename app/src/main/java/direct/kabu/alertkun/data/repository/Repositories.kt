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
import direct.kabu.alertkun.data.preferences.MonitoringHealthPreferences
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
class MailRepository(private val dao: ReceivedMailDao) { suspend fun resolveDeletes(accountId: String, deleted: Set<String>, absent: Set<String>) = dao.resolveDeletes(accountId, deleted, absent); suspend fun deleteCandidatesForAccount(accountId: String) = dao.getDeleteCandidatesForAccount(accountId).map { it.toDomain() }; suspend fun finalizeBatch(outcomes: List<direct.kabu.alertkun.model.MailProcessingOutcome>) = dao.finalizeBatch(outcomes); val mails: Flow<List<ReceivedMail>> = dao.observeAll().map { it.map { entity -> entity.toDomain() } }; suspend fun saveReceived(mail: ReceivedMail) { require(mail.processingState == MailProcessingState.RECEIVED); dao.insertReceivedMail(mail.toEntity()) }; suspend fun getByRemoteId(accountId: String, remoteMessageId: String) = dao.getByRemoteMessageId(accountId, remoteMessageId)?.toDomain(); suspend fun exists(accountId: String, remoteMessageId: String) = dao.getByRemoteMessageId(accountId, remoteMessageId) != null; suspend fun receivedMails() = dao.getByProcessingState(MailProcessingState.RECEIVED).map { it.toDomain() }; suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor = MailBackgroundColor.DEFAULT) = dao.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor); suspend fun deleteOlderThan(epochMillis: Long) = dao.deleteOlderThan(epochMillis) }
class SettingsRepository private constructor(private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, @Suppress("UNUSED_PARAMETER") isolatedStore: Unit) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore, Unit)
    val myDnsMasterId: Flow<String> = store.data.map { it[MYDNS_MASTER_ID] ?: "" }
    suspend fun updateMyDnsMasterId(masterId: String) { store.edit {
        if ((it[MYDNS_MASTER_ID] ?: "") != masterId.trim()) direct.kabu.alertkun.data.preferences.FcmSchedulePreferences.invalidate(it)
        it[MYDNS_MASTER_ID] = masterId.trim()
    } }
    internal val fcmRegistrationTargets = store.data.map {
        direct.kabu.alertkun.push.FcmRegistrationTarget(it[MYDNS_MASTER_ID] ?: "", it[POLL] ?: 5,
            it[ENABLED] ?: false, it[direct.kabu.alertkun.data.preferences.FcmSchedulePreferences.revision] ?: 0L)
    }
    val settings: Flow<MonitoringSettings> = store.data.map { p ->
        MonitoringSettings(p[POLL] ?: 5, p[RETENTION] ?: 7, p[FAILURE] ?: 3, p[ENABLED] ?: false,
            p[CHECK_ON_APP_START] ?: true, p[MonitoringHealthPreferences.enabled] ?: true)
    }
    val appLanguage: Flow<AppLanguage> = store.data.map { p -> p[LANGUAGE]?.let { AppLanguage.valueOf(it) } ?: AppLanguage.ENGLISH }
    suspend fun update(settings: MonitoringSettings) {
        require(settings.pollIntervalMinutes in 1..1500)
        require(settings.mailRetentionDays in -1..99)
        require(settings.consecutiveFailureThreshold in 1..99)
        store.edit { p ->
            invalidateFcmScheduleIfChanged(p, settings.pollIntervalMinutes, settings.monitoringEnabled)
            if (!settings.monitoringEnabled || !settings.healthNotificationEnabled ||
                p[ENABLED] != true || p[MonitoringHealthPreferences.enabled] == false) {
                p.remove(MonitoringHealthPreferences.observingSince)
            }
            p[POLL] = settings.pollIntervalMinutes
            p[RETENTION] = settings.mailRetentionDays
            p[FAILURE] = settings.consecutiveFailureThreshold
            p[ENABLED] = settings.monitoringEnabled
            p[CHECK_ON_APP_START] = settings.checkImmediatelyOnAppStart
            p[MonitoringHealthPreferences.enabled] = settings.healthNotificationEnabled
        }
    }
    /** Save the monitoring form together; legacy callers leave the health preference unchanged. */
    suspend fun updateMonitoringSettings(poll: Int, threshold: Int, enabled: Boolean, checkOnStart: Boolean,
        healthNotificationEnabled: Boolean? = null) {
        require(poll in 1..1500)
        require(threshold in 1..99)
        store.edit { p ->
            invalidateFcmScheduleIfChanged(p, poll, enabled)
            if (!enabled || p[ENABLED] != true) p.remove(MonitoringHealthPreferences.observingSince)
            p[POLL] = poll
            p[FAILURE] = threshold
            p[ENABLED] = enabled
            p[CHECK_ON_APP_START] = checkOnStart
            if (healthNotificationEnabled != null) {
                if (healthNotificationEnabled != (p[MonitoringHealthPreferences.enabled] ?: true)) {
                    p.remove(MonitoringHealthPreferences.observingSince)
                }
                p[MonitoringHealthPreferences.enabled] = healthNotificationEnabled
            }
        }
    }

    /** Independent of monitoring scheduling and Gateway registration. */
    suspend fun updateHealthNotificationEnabled(enabled: Boolean) {
        store.edit { p ->
            if (enabled != (p[MonitoringHealthPreferences.enabled] ?: true)) {
                p.remove(MonitoringHealthPreferences.observingSince)
            }
            p[MonitoringHealthPreferences.enabled] = enabled
        }
    }
    suspend fun updateAppLanguage(language: AppLanguage) { store.edit { it[LANGUAGE] = language.name } }
    /** Only the existing mail_retention_days changes; never reschedule monitoring or register with Gateway. */
    suspend fun updateMailRetentionPolicy(policy: direct.kabu.alertkun.model.ServerDeletePolicy) {
        store.edit { it[RETENTION] = direct.kabu.alertkun.model.LocalMailRetention.days(policy) }
    }

    /** Persist an attempt before touching Room; a failed purge is not repeated in the same hour. */
    internal suspend fun claimLocalMailCleanup(hourStart: Long): Boolean {
        var claimed = false
        store.edit { p ->
            if (p[LAST_LOCAL_CLEANUP_HOUR]?.let { it >= hourStart } != true) {
                p[LAST_LOCAL_CLEANUP_HOUR] = hourStart
                claimed = true
            }
        }
        return claimed
    }
    private fun invalidateFcmScheduleIfChanged(p: androidx.datastore.preferences.core.MutablePreferences, poll: Int, enabled: Boolean) {
        if ((p[POLL] ?: 5) != poll || (p[ENABLED] ?: false) != enabled) {
            direct.kabu.alertkun.data.preferences.FcmSchedulePreferences.invalidate(p)
        }
    }
    companion object {
        internal fun forStore(store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) = SettingsRepository(store, Unit)
        private val MYDNS_MASTER_ID = androidx.datastore.preferences.core.stringPreferencesKey("mydns_master_id")
        internal val POLL = intPreferencesKey("poll_interval_minutes")
        private val RETENTION = intPreferencesKey("mail_retention_days")
        private val LAST_LOCAL_CLEANUP_HOUR = androidx.datastore.preferences.core.longPreferencesKey("last_local_mail_cleanup_hour")
        private val FAILURE = intPreferencesKey("consecutive_failure_threshold")
        internal val ENABLED = booleanPreferencesKey("monitoring_enabled")
        private val CHECK_ON_APP_START = booleanPreferencesKey("check_immediately_on_app_start")
        private val LANGUAGE = androidx.datastore.preferences.core.stringPreferencesKey("app_language")
    }
}
