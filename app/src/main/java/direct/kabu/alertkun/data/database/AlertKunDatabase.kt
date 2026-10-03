package direct.kabu.alertkun.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import direct.kabu.alertkun.data.dao.AccountDao
import direct.kabu.alertkun.data.dao.ReceivedMailDao
import direct.kabu.alertkun.data.dao.RuleDao
import direct.kabu.alertkun.data.entity.AlertRuleEntity
import direct.kabu.alertkun.data.entity.MailAccountEntity
import direct.kabu.alertkun.data.entity.ReceivedMailEntity

@Database(entities = [MailAccountEntity::class, AlertRuleEntity::class, ReceivedMailEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AlertKunDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun ruleDao(): RuleDao
    abstract fun receivedMailDao(): ReceivedMailDao
    companion object { @Volatile private var instance: AlertKunDatabase? = null; fun getInstance(context: Context): AlertKunDatabase = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, AlertKunDatabase::class.java, "alertkun.db").build().also { instance = it } } }
}
