package direct.kabu.alertkun.rules

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEngineTest {
    private val engine = RuleEngine()

    @Test fun senderMatchesAsciiCaseInsensitivelyAndPartially() {
        val result = engine.evaluate(null, null, false, listOf(rule("sender", RuleTarget.SENDER)), "System <ALERT@example.invalid>")
        assertEquals("sender", (result as RuleEvaluation.Match).rule.id)
    }

    @Test fun senderMatchesJapaneseDisplayName() {
        val senderRule = rule("sender", RuleTarget.SENDER).copy(searchText = "監視担当")
        assertEquals(senderRule, (engine.evaluate(null, null, false, listOf(senderRule), "監視担当 <sender@example.invalid>") as RuleEvaluation.Match).rule)
    }

    @Test fun senderMissAndNullDoNotRequestBodyOrMatchSubjectOrBody() {
        val rules = listOf(rule("sender", RuleTarget.SENDER))
        assertEquals(RuleEvaluation.NoMatch, engine.evaluate("alert", "alert", false, rules, "other@example.invalid"))
        assertEquals(RuleEvaluation.NoMatch, engine.evaluate("alert", "alert", false, rules, null))
    }

    @Test fun senderAndSubjectUseExistingPriorityAndIdOrder() {
        val sender = rule("a", RuleTarget.SENDER)
        assertEquals(sender, (engine.evaluate("alert", null, false, listOf(rule("z"), sender), "alert@example.invalid") as RuleEvaluation.Match).rule)
    }

    @Test fun senderMissStillRequestsBodyWhenBodyRuleNeedsIt() {
        assertEquals(RuleEvaluation.BodyRequired, engine.evaluate(null, null, false,
            listOf(rule("sender", RuleTarget.SENDER), rule("body", RuleTarget.BODY)), "other@example.invalid"))
    }

    @Test fun subjectMatchesCaseInsensitivelyAndPartially() = assertMatch("subject", "System ALERT active", null, false, listOf(rule("subject")))
    @Test fun bodyMatches() = assertMatch("body", "normal", "contains alert text", true, listOf(rule("body", RuleTarget.BODY)))
    @Test fun subjectAndBodyMatchesSubject() = assertMatch("both", "alert", null, false, listOf(rule("both", RuleTarget.SUBJECT_AND_BODY)))
    @Test fun subjectAndBodyMatchesBody() = assertMatch("both", "normal", "an ALERT occurred", true, listOf(rule("both", RuleTarget.SUBJECT_AND_BODY)))
    @Test fun disabledRulesAreIgnored() = assertEquals(RuleEvaluation.NoMatch, engine.evaluate("alert", null, false, listOf(rule("disabled", enabled = false))))
    @Test fun lowestPriorityWins() = assertMatch("high", "alert", null, false, listOf(rule("low", priority = 10), rule("high", priority = 1)))
    @Test fun equalPriorityUsesIdOrder() = assertMatch("a", "alert", null, false, listOf(rule("z"), rule("a")))
    @Test fun nullSubjectDoesNotMatch() = assertEquals(RuleEvaluation.NoMatch, engine.evaluate(null, null, false, listOf(rule("subject"))))
    @Test fun fetchedNullBodyDoesNotMatchBodyRule() = assertEquals(RuleEvaluation.NoMatch, engine.evaluate("normal", null, true, listOf(rule("body", RuleTarget.BODY))))
    @Test fun bodyRuleRequestsUnfetchedBody() = assertEquals(RuleEvaluation.BodyRequired, engine.evaluate("normal", null, false, listOf(rule("body", RuleTarget.BODY))))
    @Test fun subjectAndBodySubjectMatchDoesNotRequestBody() = assertMatch("both", "alert", null, false, listOf(rule("both", RuleTarget.SUBJECT_AND_BODY)))
    @Test fun subjectAndBodyMissRequestsUnfetchedBody() = assertEquals(RuleEvaluation.BodyRequired, engine.evaluate("normal", null, false, listOf(rule("both", RuleTarget.SUBJECT_AND_BODY))))
    @Test fun fetchedBodyWithNoMatchIsNoMatch() = assertEquals(RuleEvaluation.NoMatch, engine.evaluate("normal", "also normal", true, listOf(rule("both", RuleTarget.SUBJECT_AND_BODY))))

    private fun assertMatch(id: String, subject: String?, body: String?, bodyFetched: Boolean, rules: List<AlertRule>) {
        assertEquals(id, (engine.evaluate(subject, body, bodyFetched, rules) as RuleEvaluation.Match).rule.id)
    }

    private fun rule(id: String, target: RuleTarget = RuleTarget.SUBJECT, priority: Int = 1, enabled: Boolean = true) = AlertRule(
        id, "name", "alert", target, priority, null, VibrationPattern.NONE, AlarmMode.ONCE, enabled,
    )
}
