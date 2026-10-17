package dev.amenhancer.host.applemusic
import org.junit.Assert.*
import org.junit.Test
class HostProfileEligibilityTest {
    private enum class Form { PhoneStacked, TabletDualPane }
    private fun supports(sdk: Int, code: Long, name: String, tablet: Boolean) =
        !tablet && sdk >= 33 && AppleMusicHostProfiles.supportsGlass(code,name)
    private fun supports(sdk: Int, code: Long, name: String, form: Form) =
        sdk >= 33 && AppleMusicHostProfiles.supportsGlass(code,name)
    @Test fun originalEligibilityMatrixIsPreserved() {
        assertTrue(supports(33, 1586, "6.5.2", false))
        assertTrue(supports(36, 1599, "6.5.3", false))
        assertFalse(supports(32, 1586, "6.5.2", false))
        assertFalse(supports(32, 1599, "6.5.3", false))
        assertFalse(supports(36, 1583, "6.5.1", false))
        assertFalse(supports(36, 1586, "6.5.2", true))
        assertFalse(supports(36, 1599, "6.5.3", true))
        assertFalse(supports(36, 1587, "6.5.2", false))
        assertFalse(supports(36, 1600, "6.5.3", false))
        assertFalse(supports(36, 1599, "6.5.4", false))
        assertTrue(supports(33, 1586, "6.5.2", Form.PhoneStacked))
        assertTrue(supports(33, 1586, "6.5.2", Form.TabletDualPane))
        assertTrue(supports(36, 1599, "6.5.3", Form.TabletDualPane))
        assertFalse(supports(32, 1586, "6.5.2", Form.TabletDualPane))
        assertFalse(supports(32, 1599, "6.5.3", Form.PhoneStacked))
        assertFalse(supports(36, 1583, "6.5.1", Form.PhoneStacked))
        assertFalse(supports(36, 1587, "6.5.2", Form.TabletDualPane))
        assertFalse(supports(36, 1599, "6.5.4", Form.TabletDualPane))
        assertTrue(supports(33, 1586, "6.5.2", false))
        assertTrue(supports(36, 1599, "6.5.3", false))
        assertFalse(supports(33, 1586, "6.5.2", true))
        assertFalse(supports(36, 1599, "6.5.3", true))
        assertFalse(supports(32, 1586, "6.5.2", false))
        assertFalse(supports(36, 1587, "6.5.2", false))
    }
    @Test fun referenceAndUnverifiedTuplesAreNotProductionBuilds() {
        assertFalse(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","6.5.0",1580))
        assertTrue(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0-beta",1606))
        assertFalse(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0",1606))
        assertTrue(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0-beta",1607))
        assertTrue(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0-beta",1609))
        assertTrue(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0-beta",1611))
        assertFalse(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0",1611))
        assertFalse(AppleMusicHostProfiles.isProductionBuild("com.apple.android.music","7.0.0-beta",1608))
        assertFalse(AppleMusicHostProfiles.isProductionBuild("other","6.5.3",1599))
    }
}
