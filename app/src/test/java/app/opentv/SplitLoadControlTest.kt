package app.opentv

import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import app.opentv.player.PromotableSplitLoadControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLoadControlTest {
    @Test
    fun `promotion restores normal buffer policy for subsequent track selections`() {
        val control = PromotableSplitLoadControl(15_000, 120_000, 2_500, 5_000, 60_000)
        val calculate = PromotableSplitLoadControl::class.java.getDeclaredMethod(
            "calculateTargetBufferBytes", Array<ExoTrackSelection?>::class.java,
        ).apply { isAccessible = true }
        assertEquals(32 * 1024 * 1024, calculate.invoke(control, emptyArray<ExoTrackSelection?>()))
        assertEquals(0L, control.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue(!control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        control.promote()
        assertEquals(60_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue(control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        assertTrue((calculate.invoke(control, emptyArray<ExoTrackSelection?>()) as Int) != 32 * 1024 * 1024)
    }
}
