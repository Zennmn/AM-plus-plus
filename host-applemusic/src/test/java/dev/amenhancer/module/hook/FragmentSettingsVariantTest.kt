package dev.amenhancer.module.hook

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FragmentSettingsVariantTest {
    private fun names(): JSONObject {
        val names=JSONObject().put("callbackClass","ji.a").put("composerClass","z0.n")
        val methods=JSONObject()
        val targets=mapOf("preferenceItems" to SettingsHostViewModel::class.java.declaredMethods.single { it.name=="getPreferenceItems" },
            "compose" to SettingsHostFragment::class.java.declaredMethods.single { it.name=="r1" },
            "dataCategory" to SettingsHostViewModel::class.java.declaredMethods.single { it.name=="getDataCategory" })
        targets.forEach { (key,method) -> methods.put(key,JSONObject().put("owner",method.declaringClass.name).put("name",method.name)
            .put("parameters",JSONArray(method.parameterTypes.map { it.name })).put("returns",method.returnType.name).put("static",false)) }
        return names.put("members",JSONObject().put("contractVersion",1).put("methods",methods).put("fields",JSONObject()))
    }
    private fun profile()=JSONObject().put("settings",JSONObject()).put("settingsVariants",JSONArray().put(names()))
    private fun loader(brokenOriginal: Boolean)=SettingsContractLoader(mapOf("ji.a" to SettingsHostFunction0::class.java,"z0.n" to SettingsHostComposer::class.java)+
        if(brokenOriginal) mapOf("pi.a" to SettingsHostArtwork::class.java) else emptyMap())
    @Test fun `verified alternate interfaces resolve a complete working row model`() {
        val loader=loader(true);val contract=FragmentSettingsContract.select(loader,profile())
        assertEquals("ji.a",contract.callbackClass)
        val row=FragmentSettingsModels.resolve(loader,contract).createCategory { }
        assertTrue(row is SettingsHostCategory)
        contract.verifyDataCategoryProfile(loader,profile(),contract.dataCategoryMethod(loader))
    }
    @Test fun `ambiguous complete variants never silently select the first`() {
        assertThrows(IllegalStateException::class.java) { FragmentSettingsContract.select(loader(false),profile()) }
    }
    @Test fun `partial method mismatch is rejected before installing callbacks`() {
        val profile=profile();profile.getJSONArray("settingsVariants").getJSONObject(0).getJSONObject("members").getJSONObject("methods").getJSONObject("compose").put("name","missing")
        assertThrows(IllegalStateException::class.java) { FragmentSettingsContract.select(loader(true),profile) }
    }
}
