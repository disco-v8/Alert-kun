package direct.kabu.alertkun.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import direct.kabu.alertkun.data.dao.AccountDao
import direct.kabu.alertkun.data.dao.ReceivedMailDao
import direct.kabu.alertkun.data.dao.RuleDao
import direct.kabu.alertkun.data.entity.AlertRuleEntity
import direct.kabu.alertkun.data.entity.MailAccountEntity
import direct.kabu.alertkun.data.entity.ReceivedMailEntity

@Database(entities = [MailAccountEntity::class, AlertRuleEntity::class, ReceivedMailEntity::class], version = 6, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AlertKunDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun ruleDao(): RuleDao
    abstract fun receivedMailDao(): ReceivedMailDao
    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE received_mails ADD COLUMN serverDeleteResolution TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN tlsValidationMode TEXT NOT NULL DEFAULT 'STRICT'")
                db.execSQL("ALTER TABLE accounts ADD COLUMN certificateFingerprint TEXT")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN serverDeletePolicy TEXT NOT NULL DEFAULT 'NEVER'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN soundType TEXT NOT NULL DEFAULT 'ALARM'")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT'")
                db.execSQL("ALTER TABLE received_mails ADD COLUMN backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT'")
            }
        }
        @Volatile private var instance: AlertKunDatabase? = null
        fun getInstance(context: Context): AlertKunDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AlertKunDatabase::class.java, "alertkun.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
        }
    }
}
