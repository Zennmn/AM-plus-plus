package dev.amenhancer.module.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shared Catalog query seam used by both manual refresh and cold-cache misses. */
internal fun interface CatalogEntityLookup {
    fun lookup(mediaKind: String, ids: List<String>): List<Any>
}

/**
 * Invokes Apple Music's suspend MediaApiRepository contract without hooking it.
 * The interface Method and Holder instance are resolved by the caller/factory;
 * this class owns the one verified Continuation and response-unwrapping path.
 */
internal class MediaApiRepositoryCatalogInvoker(
    private val method: Method,
    private val repository: Any,
    private val classLoader: ClassLoader,
) : CatalogEntityLookup {
    override fun lookup(mediaKind: String, ids: List<String>): List<Any> {
        if (ids.isEmpty()) return emptyList()
        val response = invokeCatalog(mediaKind, ids)
            ?: error("$mediaKind Catalog 批量查询失败")
        val dataMethod = LibraryRefreshHost.findMethod(response, "getData", 0)
            ?: error("MediaApiResponse 缺少 getData")
        return when (val data = dataMethod.invoke(response)) {
            is Array<*> -> data.filterNotNull()
            is Iterable<*> -> data.filterNotNull()
            else -> emptyList()
        }
    }

    private fun invokeCatalog(mediaKind: String, ids: List<String>): Any? {
        val continuationType = method.parameterTypes[3]
        val continuationClassLoader =
            continuationType.classLoader ?: method.declaringClass.classLoader ?: classLoader
        val coroutineContext = resolveHostEmptyCoroutineContext(
            continuationClassLoader,
            continuationType,
        ) ?: error(
            "宿主 EmptyCoroutineContext 不可用（Continuation=${continuationType.name}）",
        )
        val responseType = catalogResponseType(continuationType)
        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val resumed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val continuation = Proxy.newProxyInstance(
            continuationClassLoader,
            arrayOf(continuationType),
        ) { _, callbackMethod, args ->
            when (callbackMethod.name) {
                "getContext" -> return@newProxyInstance coroutineContext
                "resumeWith" -> {
                    if (!resumed.compareAndSet(false, true)) return@newProxyInstance null
                    val value = args?.firstOrNull()
                    val error = value?.let(::failureFromCoroutineResult)
                    when {
                        error != null -> failure.set(error)
                        responseType == null -> failure.set(
                            IllegalStateException("$mediaKind Catalog 响应类型不可用"),
                        )
                        value != null && responseType.isInstance(value) -> result.set(value)
                        else -> failure.set(
                            IllegalStateException(
                                "$mediaKind Catalog 返回了 ${value?.javaClass?.name ?: "null"}，" +
                                    "期望 ${responseType.name}",
                            ),
                        )
                    }
                    done.countDown()
                    return@newProxyInstance null
                }
                "toString" -> return@newProxyInstance "AMCatalogBatchContinuation"
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
            }
            defaultValue(callbackMethod.returnType)
        }
        val immediate = method.apply { isAccessible = true }.invoke(
            repository,
            mediaKind,
            ids,
            emptyMap<Any?, Any?>(),
            continuation,
        )
        if (isCatalogImmediateResponse(responseType, immediate) &&
            resumed.compareAndSet(false, true)
        ) {
            result.set(immediate)
            done.countDown()
        }
        if (!done.await(CATALOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw TimeoutException("Catalog $mediaKind 批量查询超时（${ids.size} 个 id）")
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun catalogResponseType(continuationType: Class<*>): Class<*>? {
        val loaders = listOfNotNull(
            method.declaringClass.classLoader,
            continuationType.classLoader,
            classLoader,
        ).distinct()
        return loaders.asSequence().mapNotNull { loader ->
            runCatching {
                Class.forName(
                    "com.apple.android.music.mediaapi.repository.MediaApiResponse",
                    false,
                    loader,
                )
            }.getOrNull()
        }.firstOrNull()
    }

    private fun failureFromCoroutineResult(value: Any?): Throwable? = runCatching {
        if (value is Throwable) return value
        if (value == null || !value.javaClass.name.endsWith("Result\$Failure")) return null
        value.javaClass.getDeclaredField("exception").apply { isAccessible = true }
            .get(value) as? Throwable
    }.getOrNull()

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private companion object {
        const val CATALOG_TIMEOUT_SECONDS = 120L
    }
}

/** Lazily resolves the pinned interface Method and Holder instance on first lookup. */
internal class AppleMusicCatalogEntityLookup(
    private val symbols: TargetSymbolResolver,
    private val classLoader: ClassLoader,
) : CatalogEntityLookup {
    private val delegate: MediaApiRepositoryCatalogInvoker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val method = symbols.resolve(
            AppleMusicSymbols.MediaApiRepositoryGetEntitiesWithIdsInvocationMethod,
        ).valueOrNull() ?: error("Catalog 批量查询符号不可用")
        val repository = repositoryInstance(method)
            ?: error("MediaApiRepositoryHolder 未提供实例")
        MediaApiRepositoryCatalogInvoker(method, repository, classLoader)
    }

    override fun lookup(mediaKind: String, ids: List<String>): List<Any> =
        delegate.lookup(mediaKind, ids)

    private fun repositoryInstance(method: Method): Any? = runCatching {
        val holder = Class.forName(
            "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder",
            true,
            classLoader,
        )
        holder.declaredFields.asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                runCatching { field.apply { isAccessible = true }.get(null) }.getOrNull()
            }
            .firstOrNull(method.declaringClass::isInstance)
    }.getOrNull()
}
