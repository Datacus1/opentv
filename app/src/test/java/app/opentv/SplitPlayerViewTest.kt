package app.opentv

import android.content.Context
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SplitPlayerViewTest {
    private fun view(): PlayerView = LayoutInflater.from(ApplicationProvider.getApplicationContext<Context>())
        .inflate(R.layout.split_player_view, null, false) as PlayerView

    @Test
    fun `split surface uses native clipped texture rather than an overflowing surface hole`() {
        val view = view()
        assertTrue(view.videoSurfaceView is TextureView)
        assertTrue(view.clipChildren)
        assertTrue(view.clipToPadding)
        assertTrue(!view.useController)
    }

    @Test
    fun `zoom retains exact half viewport and expansion fits full viewport`() {
        val view = view()
        val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        frame.setAspectRatio(16f / 9f)
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 960, 1080)
        assertEquals(960, view.width)
        assertEquals(1080, view.height)
        assertTrue(view.clipChildren)
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1920, 1080)
        assertEquals(1920, view.width)
        assertEquals(1080, view.height)
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
    }
}
