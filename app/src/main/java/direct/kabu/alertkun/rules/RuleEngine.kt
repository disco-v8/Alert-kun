package direct.kabu.alertkun.rules

import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.RuleTarget

sealed interface RuleEvaluation { data class Match(val rule: AlertRule) : RuleEvaluation; data object NoMatch : RuleEvaluation; data object BodyRequired : RuleEvaluation }
class RuleEngine {
    fun evaluate(subject: String?, body: String?, bodyFetched: Boolean, rules: List<AlertRule>, from: String? = null): RuleEvaluation {
        val matches = rules.filter { it.enabled && matches(it, subject.orEmpty(), body.orEmpty(), from.orEmpty()) }.sortedWith(compareBy<AlertRule> { it.priority }.thenBy { it.id })
        if (matches.isNotEmpty()) return RuleEvaluation.Match(matches.first())
        return if (!bodyFetched && rules.any { it.enabled && (it.target == RuleTarget.BODY || it.target == RuleTarget.SUBJECT_AND_BODY) }) RuleEvaluation.BodyRequired else RuleEvaluation.NoMatch
    }
    private fun matches(rule: AlertRule, subject: String, body: String, from: String) = when (rule.target) { RuleTarget.SUBJECT -> subject.contains(rule.searchText, true); RuleTarget.BODY -> body.contains(rule.searchText, true); RuleTarget.SUBJECT_AND_BODY -> subject.contains(rule.searchText, true) || body.contains(rule.searchText, true); RuleTarget.SENDER -> from.contains(rule.searchText, true) }
}
