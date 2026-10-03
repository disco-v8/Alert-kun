package direct.kabu.alertkun.data.repository

import direct.kabu.alertkun.data.dao.RuleDao
import direct.kabu.alertkun.data.entity.AlertRuleEntity
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleRepositoryTest {
    @Test fun blankNameIsRejected() = assertInvalid(rule(name = "   "))
    @Test fun blankSearchTextIsRejected() = assertInvalid(rule(searchText = "   "))
    @Test fun nonPositivePriorityIsRejected() = assertInvalid(rule(priority = 0))

    @Test fun nameAndSearchTextAreTrimmedBeforeSaving() = runBlocking {
        val dao = FakeRuleDao()
        RuleRepository(dao).upsert(rule(name = "  Server alert  ", searchText = "  failure  "))
        assertEquals("Server alert", dao.lastSaved!!.name)
        assertEquals("failure", dao.lastSaved!!.searchText)
    }

    private fun assertInvalid(rule: AlertRule) = runBlocking {
        try {
            RuleRepository(FakeRuleDao()).upsert(rule)
            throw AssertionError("invalid rule was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun rule(name: String = "Rule", searchText: String = "alert", priority: Int = 1) = AlertRule(
        "rule", name, searchText, RuleTarget.SUBJECT, priority, null, VibrationPattern.NONE, AlarmMode.ONCE, true,
    )
}

private class FakeRuleDao : RuleDao {
    private val stored = MutableStateFlow<List<AlertRuleEntity>>(emptyList())
    var lastSaved: AlertRuleEntity? = null

    override fun observeAll(): Flow<List<AlertRuleEntity>> = stored
    override suspend fun getById(id: String): AlertRuleEntity? = stored.value.firstOrNull { it.id == id }
    override suspend fun upsert(entity: AlertRuleEntity) {
        lastSaved = entity
        stored.value = (stored.value.filterNot { it.id == entity.id } + entity).sortedBy { it.priority }
    }
    override suspend fun delete(id: String) { stored.value = stored.value.filterNot { it.id == id } }
}
