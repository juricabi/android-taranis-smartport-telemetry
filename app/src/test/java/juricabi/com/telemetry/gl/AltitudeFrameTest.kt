package juricabi.com.telemetry.gl

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AltitudeFrameTest {

    @Test
    fun firstAnswerWinsAndRetiredFlightCannotPublish() {
        AltitudeFrame.forget()
        try {
            val oldEpoch = AltitudeFrame.currentEpoch()
            val first = TerrainScene.Companion.Reference(true, 120f)
            val competing = TerrainScene.Companion.Reference(false, 0f)

            assertSame(first, AltitudeFrame.settle(first, oldEpoch))
            assertSame(first, AltitudeFrame.settle(competing, oldEpoch))

            AltitudeFrame.forget()
            assertNull(AltitudeFrame.settle(first, oldEpoch))

            val currentEpoch = AltitudeFrame.currentEpoch()
            assertSame(competing, AltitudeFrame.settle(competing, currentEpoch))
        } finally {
            AltitudeFrame.forget()
        }
    }

    @Test
    fun withinAboveLaunchTheLiftOnlyGrows() {
        AltitudeFrame.forget()
        try {
            val epoch = AltitudeFrame.currentEpoch()
            val first = TerrainScene.Companion.Reference(true, 500f)
            val tighter = TerrainScene.Companion.Reference(true, 620f)
            val looser = TerrainScene.Companion.Reference(true, 300f)

            assertSame(first, AltitudeFrame.settle(first, epoch))
            // a tighter pass proves the flight sat lower than first thought
            assertSame(tighter, AltitudeFrame.settle(tighter, epoch))
            // and proof never unproves
            assertSame(tighter, AltitudeFrame.settle(looser, epoch))
        } finally {
            AltitudeFrame.forget()
        }
    }

    @Test
    fun aboveLaunchEvidenceCanUpgradeButNeverDowngradeTheFrame() {
        AltitudeFrame.forget()
        try {
            val epoch = AltitudeFrame.currentEpoch()
            val seaLevel = TerrainScene.Companion.Reference(false, 0f)
            val aboveLaunch = TerrainScene.Companion.Reference(true, 120f)

            assertSame(seaLevel, AltitudeFrame.settle(seaLevel, epoch))
            assertSame(aboveLaunch, AltitudeFrame.settle(aboveLaunch, epoch))
            assertSame(aboveLaunch, AltitudeFrame.settle(seaLevel, epoch))
        } finally {
            AltitudeFrame.forget()
        }
    }
}
