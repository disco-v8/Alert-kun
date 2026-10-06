package direct.kabu.alertkun.data.dao

import androidx.room.Dao
import direct.kabu.alertkun.model.MailBackgroundColor
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import direct.kabu.alertkun.data.entity.AlertRuleEntity
import direct.kabu.alertkun.data.entity.MailAccountEntity
import direct.kabu.alertkun.data.entity.ReceivedMailEntity
import direct.kabu.alertkun.model.MailProcessingState
import kotlinx.coroutines.flow.Flow

@Dao interface AccountDao { @Query("SELECT * FROM accounts") fun observeAll(): Flow<List<MailAccountEntity>>; @Query("SELECT * FROM accounts WHERE id = :id") suspend fun getById(id: String): MailAccountEntity?; @Upsert suspend fun upsert(entity: MailAccountEntity); @Query("DELETE FROM accounts WHERE id = :id") suspend fun delete(id: String) }
@Dao interface RuleDao { @Query("SELECT * FROM rules ORDER BY priority ASC") fun observeAll(): Flow<List<AlertRuleEntity>>; @Query("SELECT * FROM rules WHERE id = :id") suspend fun getById(id: String): AlertRuleEntity?; @Upsert suspend fun upsert(entity: AlertRuleEntity); @Query("DELETE FROM rules WHERE id = :id") suspend fun delete(id: String) }
@Dao interface ReceivedMailDao {
    @androidx.room.Transaction
    suspend fun finalizeBatch(outcomes: List<direct.kabu.alertkun.model.MailProcessingOutcome>) {
        require(outcomes.map { it.mailId }.distinct().size == outcomes.size)
        outcomes.forEach {
            check(getById(it.mailId)?.processingState == MailProcessingState.RECEIVED)
            finalizeProcessing(it.mailId, it.alertTriggered, it.matchedRuleId, it.backgroundColor)
        }
    }
 @Query("SELECT * FROM received_mails ORDER BY receivedAtEpochMillis DESC") fun observeAll(): Flow<List<ReceivedMailEntity>>; @Query("SELECT * FROM received_mails WHERE id = :id") suspend fun getById(id: String): ReceivedMailEntity?; @Query("SELECT * FROM received_mails WHERE accountId = :accountId AND remoteMessageId = :remoteMessageId") suspend fun getByRemoteMessageId(accountId: String, remoteMessageId: String): ReceivedMailEntity?; @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReceivedMail(entity: ReceivedMailEntity); @Query("SELECT * FROM received_mails WHERE processingState = :state") suspend fun getByProcessingState(state: MailProcessingState): List<ReceivedMailEntity>; @Query("UPDATE received_mails SET processingState = 'PROCESSED', alertTriggered = :alertTriggered, matchedRuleId = :matchedRuleId, backgroundColor = :backgroundColor WHERE id = :id") suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor); @Query("DELETE FROM received_mails WHERE receivedAtEpochMillis < :olderThanEpochMillis") suspend fun deleteOlderThan(olderThanEpochMillis: Long) }
