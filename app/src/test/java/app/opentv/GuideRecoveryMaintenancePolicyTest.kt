package app.opentv

import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.GuideRecoveryMaintenancePolicy as P
import org.junit.Assert.*
import org.junit.Test

class GuideRecoveryMaintenancePolicyTest {
    private val threshold = EpgRepository.HARD_FREE_FLOOR_BYTES + P.HEADROOM_BYTES
    @Test fun `recovery requires idle and independent working headroom`() {
        assertFalse(P.mayStart(threshold - 1, true))
        assertTrue(P.mayStart(threshold, true))
        assertFalse(P.mayStart(Long.MAX_VALUE, false))
        assertFalse(P.mayStart(-1, true))
    }
    @Test fun `checkpoint reserves main file expansion plus working headroom`() {
        assertTrue(P.mayCheckpoint(threshold + 100, 200, 100))
        assertFalse(P.mayCheckpoint(threshold + 99, 200, 100))
        assertTrue(P.mayCheckpoint(threshold, 100, 200))
        assertFalse(P.mayCheckpoint(Long.MAX_VALUE, Long.MAX_VALUE, 0))
        assertFalse(P.mayCheckpoint(Long.MAX_VALUE, 1, -1))
    }
}
