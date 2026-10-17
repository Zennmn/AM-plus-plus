package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** A glass-enabled profile must declare every role the code requires, or the whole glass install fails. */
class FragmentChromeProfileInventoryTest {
    @Test fun everyGlassProfileDeclaresTheCompleteChromeInventory() {
        val checked = AppleMusicHostProfiles.all.filter { it.capability("glass") && it.document.has("fragmentChrome") }
        assertTrue(checked.isNotEmpty())
        checked.forEach { profile ->
            val members = profile.document.getJSONObject("fragmentChrome").getJSONObject("members")
            FragmentChromeInventory.required.methods.forEach {
                assertTrue("${profile.id}: fragmentChrome.methods.$it missing", members.getJSONObject("methods").has(it))
            }
            FragmentChromeInventory.required.fields.forEach {
                assertTrue("${profile.id}: fragmentChrome.fields.$it missing", members.getJSONObject("fields").has(it))
            }
        }
    }

    @Test fun collapseCapabilityRequiresSheetSettleSeams() {
        AppleMusicHostProfiles.all.filter { it.capability("glassScrollCollapse") }.forEach { profile ->
            val methods = profile.document.getJSONObject("fragmentChrome").getJSONObject("members").getJSONObject("methods")
            assertTrue(profile.id, methods.has("settleSheet") && methods.has("refreshCollapsedOffset"))
        }
    }
}
