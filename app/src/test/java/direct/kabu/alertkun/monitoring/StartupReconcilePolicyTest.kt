package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.model.MonitoringSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupReconcilePolicyTest {
    @Test fun enabledMonitoringWithStartupCheckRebasesTheSchedule() {
        assertEquals(StartupReconcileAction.REBASE_WITH_IMMEDIATE_CHECK, StartupReconcilePolicy.actionFor(MonitoringSettings(monitoringEnabled = true, checkImmediatelyOnAppStart = true)))
    }

    @Test fun startupCheckIsNotRunWhenDisabledOrMonitoringIsOff() {
        assertEquals(StartupReconcileAction.RECONCILE_ONLY, StartupReconcilePolicy.actionFor(MonitoringSettings(monitoringEnabled = true, checkImmediatelyOnAppStart = false)))
        assertEquals(StartupReconcileAction.NONE, StartupReconcilePolicy.actionFor(MonitoringSettings(monitoringEnabled = false, checkImmediatelyOnAppStart = true)))
    }

    @Test fun startupCheckEstablishesTheNextTargetFromItsActualStart() {
        val started = 18 * 60 * 60_000L + 20 * 60_000L
        assertEquals(started + 15 * 60_000L, StartupReconcilePolicy.targetAfterCheck(started, 5))
        assertEquals(started + 15 * 60_000L, StartupReconcilePolicy.targetAfterCheck(started, 15))
    }

    @Test fun reconcileWithoutImmediateCheckReplacesAPastTargetWithAFutureOne() {
        val now = 18 * 60 * 60_000L + 20 * 60_000L
        assertEquals(now + 15 * 60_000L, StartupReconcilePolicy.targetWithoutImmediateCheck(now, 5))
        assertEquals(now + 15 * 60_000L, StartupReconcilePolicy.targetWithoutImmediateCheck(now, 15))
    }

    @Test fun futureMatchingScheduleIsKeptWithoutChangingItsGenerationOrTarget() {
        assertTrue(
            StartupReconcilePolicy.shouldKeepExistingSchedule(
                nowEpochMillis = 100L,
                nextScheduledAtEpochMillis = 101L,
                savedScheduleKind = MonitoringScheduleKind.PERIODIC.name,
                expectedScheduleKind = MonitoringScheduleKind.PERIODIC,
                normalWorkExists = true,
            ),
        )
    }

    @Test fun pastMissingOrWrongKindScheduleIsRebasedWithoutAnImmediateCheck() {
        assertFalse(StartupReconcilePolicy.shouldKeepExistingSchedule(100L, 100L, MonitoringScheduleKind.PERIODIC.name, MonitoringScheduleKind.PERIODIC, true))
        assertFalse(StartupReconcilePolicy.shouldKeepExistingSchedule(100L, 101L, MonitoringScheduleKind.ONE_TIME.name, MonitoringScheduleKind.PERIODIC, true))
        assertFalse(StartupReconcilePolicy.shouldKeepExistingSchedule(100L, 101L, MonitoringScheduleKind.PERIODIC.name, MonitoringScheduleKind.PERIODIC, false))
    }

    @Test fun startupGateAllowsOnlyOneActionInAProcess() {
        val gate = StartupReconcileGate()
        assertTrue(gate.consumeOnce())
        assertFalse(gate.consumeOnce())
    }

    @Test fun startupGenerationInvalidatesOldWorkersWhileRunNowNeedsNoGenerationChange() {
        assertFalse(MonitoringExecutionPolicy.mayReschedule(true, 5L, 4L))
        assertTrue(MonitoringExecutionPolicy.mayReschedule(true, 5L, 5L))
    }
}
