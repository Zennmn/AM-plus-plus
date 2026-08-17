package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class FeatureInstallationModuleTest {
    @Test
    fun `resource registration is an early idempotent phase before embedded binding`() {
        val events = mutableListOf<String>()
        val module = FeatureInstallationModule(
            plans = listOf(plan("dual", events, "dual resources")),
            installLayoutInflationHooks = { events += "layout hooks" },
            registerApplicationCreated = { _, _, _ -> events += "application observer" },
            reportHealth = { _, health -> events += "report ${health.feature}" },
            reportError = { message, _ -> events += "error $message" },
        )

        module.registerResources(config())
        module.registerResources(config())

        assertEquals(listOf("dual resources", "layout hooks"), events)

        val session = module.installNow(config()) {
            events += "application context"
            context("6.5.1 (1583)")
        }

        assertEquals(
            listOf(
                "dual resources",
                "layout hooks",
                "application context",
                "install dual",
                "report dual",
            ),
            events,
        )
        assertEquals(FeatureInstallationPhase.COMPLETE, session.snapshot().phase)

        module.registerResources(config())
        module.installNow(config()) {
            events += "unexpected application context"
            context("unexpected")
        }
        assertEquals(5, events.size)
    }

    @Test
    fun `one call owns resource registration and ordered feature health`() {
        val events = mutableListOf<String>()
        var contextFactoryCalls = 0
        var applicationCreated: (((() -> HookContext) -> Unit))? = null
        val module = FeatureInstallationModule(
            plans = listOf(
                plan("dual", events, "dual resources"),
                plan("editorial", events),
                plan("phone", events, "phone resources"),
                plan("blur", events),
                plan("font", events, "font resources"),
                plan("online", events),
            ),
            installLayoutInflationHooks = { events += "layout hooks" },
            registerApplicationCreated = { _, _, callback ->
                events += "application observer"
                applicationCreated = callback
            },
            reportHealth = { _, health -> events += "report ${health.feature}" },
            reportError = { message, _ -> events += "error $message" },
        )

        val session = module.install(config(), javaClass.classLoader!!)

        assertEquals(
            listOf(
                "dual resources",
                "phone resources",
                "font resources",
                "layout hooks",
                "application observer",
            ),
            events,
        )
        assertEquals(FeatureInstallationPhase.RESOURCES_REGISTERED, session.snapshot().phase)

        applicationCreated!! {
            contextFactoryCalls += 1
            context("6.5.0 (1580)")
        }

        assertEquals(
            listOf(
                "dual resources",
                "phone resources",
                "font resources",
                "layout hooks",
                "application observer",
                "install dual",
                "report dual",
                "install editorial",
                "report editorial",
                "install phone",
                "report phone",
                "install blur",
                "report blur",
                "install font",
                "report font",
                "install online",
                "report online",
            ),
            events,
        )
        assertEquals(FeatureInstallationPhase.COMPLETE, session.snapshot().phase)
        assertEquals(
            listOf("dual", "editorial", "phone", "blur", "font", "online"),
            session.snapshot().health.map(FeatureHealth::feature),
        )
        assertEquals(
            List(6) { "6.5.0 (1580)" },
            session.snapshot().health.map(FeatureHealth::targetVersion),
        )

        applicationCreated!! {
            contextFactoryCalls += 1
            context("unexpected")
        }
        assertSame(session, module.install(config(), javaClass.classLoader!!))
        assertEquals(17, events.size)
        assertEquals(1, contextFactoryCalls)
    }

    @Test
    fun `feature failure keeps its existing diagnostic and does not block later health`() {
        val reports = mutableListOf<FeatureHealth>()
        val errors = mutableListOf<String>()
        val module = FeatureInstallationModule(
            plans = listOf(
                FeatureInstallationPlan(feature("first", FeatureInstallResult.active("installed"))),
                FeatureInstallationPlan(throwingFeature("broken")),
                FeatureInstallationPlan(feature("last", FeatureInstallResult.degraded("fallback"))),
            ),
            installLayoutInflationHooks = {},
            registerApplicationCreated = { _, _, callback -> callback { context("target") } },
            reportHealth = { _, health -> reports += health },
            reportError = { message, _ -> errors += message },
        )

        val session = module.install(config(), javaClass.classLoader!!)

        assertEquals(listOf("first", "broken", "last"), reports.map(FeatureHealth::feature))
        assertEquals(
            listOf(FeatureState.ACTIVE, FeatureState.FAILED, FeatureState.DEGRADED),
            reports.map(FeatureHealth::state),
        )
        assertEquals("StringBuilder: unexpected adapter failure", reports[1].message)
        assertEquals(listOf("broken failed"), errors)
        assertEquals(FeatureInstallationPhase.COMPLETE, session.snapshot().phase)
    }

    @Test
    fun `embedded install registers resources and installs immediately without application observer`() {
        val events = mutableListOf<String>()
        val module = FeatureInstallationModule(
            plans = listOf(
                plan("dual", events, "dual resources"),
                plan("font", events, "font resources"),
            ),
            installLayoutInflationHooks = { events += "layout hooks" },
            registerApplicationCreated = { _, _, _ -> events += "application observer" },
            reportHealth = { _, health -> events += "report ${health.feature}" },
            reportError = { message, _ -> events += "error $message" },
        )

        val session = module.installNow(config()) { context("6.5.1 (1583)") }

        assertEquals(
            listOf(
                "dual resources",
                "font resources",
                "layout hooks",
                "install dual",
                "report dual",
                "install font",
                "report font",
            ),
            events,
        )
        assertEquals(FeatureInstallationPhase.COMPLETE, session.snapshot().phase)
        assertSame(session, module.installNow(config()) { context("unexpected") })
    }

    @Test
    fun `embedded context failure does not publish a poisoned active session`() {
        var resourceRegistrations = 0
        val module = FeatureInstallationModule(
            plans = listOf(
                FeatureInstallationPlan(
                    feature = feature("feature", FeatureInstallResult.active("installed")),
                    registerResources = { resourceRegistrations += 1 },
                ),
            ),
            installLayoutInflationHooks = {},
            registerApplicationCreated = { _, _, _ -> },
            reportHealth = { _, _ -> },
            reportError = { _, _ -> },
        )

        assertThrows(IllegalStateException::class.java) {
            module.installNow(config()) { error("context failed") }
        }
        val recovered = module.installNow(config()) { context("6.5.1 (1583)") }

        assertEquals(FeatureInstallationPhase.COMPLETE, recovered.snapshot().phase)
        assertEquals(1, resourceRegistrations)
    }

    @Test
    fun `resource failure preserves propagation and stops later installation stages`() {
        val events = mutableListOf<String>()
        val module = FeatureInstallationModule(
            plans = listOf(
                FeatureInstallationPlan(
                    feature = feature("dual", FeatureInstallResult.active("unused")),
                    registerResources = {
                        events += "dual resources"
                        error("resource registration failed")
                    },
                ),
                plan("phone", events, "phone resources"),
            ),
            installLayoutInflationHooks = { events += "layout hooks" },
            registerApplicationCreated = { _, _, _ -> events += "application observer" },
            reportHealth = { _, _ -> events += "report" },
            reportError = { _, _ -> events += "error" },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            module.install(config(), javaClass.classLoader!!)
        }

        assertEquals("resource registration failed", error.message)
        assertEquals(listOf("dual resources"), events)

        assertThrows(IllegalStateException::class.java) {
            module.registerResources(config())
        }
        assertEquals(listOf("dual resources"), events)
    }

    private fun plan(
        key: String,
        events: MutableList<String>,
        resourceEvent: String? = null,
    ): FeatureInstallationPlan = FeatureInstallationPlan(
        feature = object : FeatureHook {
            override val key: String = key
            override fun install(context: HookContext): FeatureInstallResult {
                events += "install $key"
                return FeatureInstallResult.active("$key installed")
            }
        },
        registerResources = { resourceEvent?.let(events::add) },
    )

    private fun feature(key: String, result: FeatureInstallResult): FeatureHook =
        object : FeatureHook {
            override val key: String = key
            override fun install(context: HookContext): FeatureInstallResult = result
        }

    private fun throwingFeature(key: String): FeatureHook = object : FeatureHook {
        override val key: String = key
        override fun install(context: HookContext): FeatureInstallResult =
            error("unexpected adapter failure")
    }

    private fun context(identity: String): HookContext = HookContext(
        config = config(),
        target = TargetAdaptation(
            identity = identity,
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
        ),
    )

    private fun config(): TargetConfigClient = TargetConfigClient(
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> emptyMap<String, Any?>()
                "toString" -> "feature-installation-test-preferences"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as SharedPreferences,
    )
}
