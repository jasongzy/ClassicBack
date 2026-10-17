package com.jasongzy.classicback

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

class ModuleMain : XposedModule() {
    private val callbacks = WeakHashMap<OnBackInvokedCallback, WeakReference<OnBackInvokedCallback>>()
    @Volatile private var enabled = false

    @SuppressLint("PrivateApi")
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) return
        val handles = mutableListOf<HookHandle>()
        try {
            val dispatcher = Class.forName("android.window.WindowOnBackInvokedDispatcher", false, null)
            val info = Class.forName("android.window.OnBackInvokedCallbackInfo", false, null)
            val binderCallback = Class.forName("android.window.IOnBackInvokedCallback", false, null)
            val infoConstructor = info.declaredConstructors.single {
                val types = it.parameterTypes
                types.size in 2..4 && types[0] == binderCallback &&
                    types[1] == Int::class.javaPrimitiveType &&
                    (types.size < 3 || types[2] == Boolean::class.javaPrimitiveType) &&
                    (types.size < 4 || types[3] == Int::class.javaPrimitiveType)
            }
            val register = dispatcher.getDeclaredMethod(
                "registerOnBackInvokedCallbackUnchecked", OnBackInvokedCallback::class.java,
                Int::class.javaPrimitiveType,
            )
            val unregister = dispatcher.getDeclaredMethod(
                "unregisterOnBackInvokedCallback", OnBackInvokedCallback::class.java,
            )
            // Boot-image callers may have inlined the registration and callback-info constructor.
            val callerNames = setOf(
                "setTopOnBackInvokedCallback", "registerOnBackInvokedCallback",
                "registerSystemOnBackInvokedCallback", "unregisterOnBackInvokedCallback",
                "registerOnBackInvokedCallbackUnchecked", "attachToWindow", "setActualDispatcher",
                "transferCallbacksToDispatcher", "clearCallbacksOnDispatcher", "clear",
            )
            for (type in listOf(dispatcher, Class.forName("android.window.ProxyOnBackInvokedDispatcher", false, null))) {
                for (method in type.declaredMethods.filter { it.name in callerNames }) {
                    check(deoptimize(method)) { "Failed to deoptimize $method" }
                }
            }
            handles += hook(infoConstructor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val args = if (enabled) classicCallbackInfoArgs(chain.args) else null
                if (args == null) chain.proceed() else chain.proceed(args)
            }
            handles += hook(register).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if (!enabled) return@intercept chain.proceed()
                val callback = chain.args[0] as OnBackInvokedCallback
                val replacement = synchronized(callbacks) {
                    callbacks[callback]?.get() ?: (classicNavigationCallback(callback) ?: classicFlutterCallback(callback))
                        ?.also { callbacks[callback] = WeakReference(it) }
                }
                if (replacement == null) chain.proceed() else {
                    val args = chain.args.toTypedArray()
                    args[0] = replacement
                    chain.proceed(args)
                }
            }
            handles += hook(unregister).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val callback = chain.args[0] as? OnBackInvokedCallback
                val replacement = if (enabled) synchronized(callbacks) { callbacks[callback]?.get() } else null
                if (replacement == null) chain.proceed() else chain.proceed(arrayOf(replacement))
            }
            enabled = true
            log(Log.INFO, "ClassicBack", "Classic back enabled in ${param.processName}")
        } catch (error: Throwable) {
            enabled = false
            for (handle in handles) runCatching { handle.unhook() }
            log(Log.ERROR, "ClassicBack", "Keeping original back behavior in ${param.processName}", error)
        }
    }
}

internal fun classicCallbackInfoArgs(args: List<Any?>): Array<Any?>? {
    if (args.size !in 2..4 || args[1] != -1 ||
        (args.size >= 3 && args[2] != false) || (args.size == 4 && args[3] != 0)) return null
    // Change only the priority reported to WM; dispatcher ordering and invocation stay intact.
    return args.toTypedArray().also { it[1] = 0 }
}

internal fun classicNavigationCallback(callback: OnBackInvokedCallback): OnBackInvokedCallback? {
    if (callback.javaClass.interfaces.none { it.name == "android.window.OnBackAnimationCallback" }) return null
    // shortcut: match the verified NavigationEvent input layout; extend after validating new layouts.
    val captured = callback.javaClass.declaredFields.singleOrNull { !Modifier.isStatic(it.modifiers) } ?: return null
    val input = captured.type
    if ((!Modifier.isAbstract(input.modifiers) && !Modifier.isFinal(input.modifiers)) ||
        input.classLoader !== callback.javaClass.classLoader ||
        input.declaredConstructors.none {
            it.parameterTypes.contentEquals(arrayOf(OnBackInvokedDispatcher::class.java, Int::class.javaPrimitiveType))
        }) return null
    val fields = input.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
    if (fields.size != 4 || fields.map { it.type }.toSet() != setOf(
            OnBackInvokedDispatcher::class.java, Int::class.javaPrimitiveType,
            OnBackInvokedCallback::class.java, Boolean::class.javaPrimitiveType,
        )) return null
    val base = input.superclass ?: return null
    val baseFields = base.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
    if (!Modifier.isAbstract(base.modifiers) || base.superclass != Any::class.java || baseFields.size != 2 ||
        baseFields.count { it.type == Boolean::class.javaPrimitiveType } != 1 ||
        baseFields.none { !it.type.isPrimitive && it.type.classLoader === input.classLoader }) return null
    val owner = captured.apply { isAccessible = true }.get(callback) ?: return null
    val registered = fields.single { it.type == OnBackInvokedCallback::class.java }
    if (registered.apply { isAccessible = true }.get(owner) !== callback) return null
    // NavigationEvent synthesizes the start state when completion arrives without a predictive gesture.
    return OnBackInvokedCallback { callback.onBackInvoked() }
}

@Suppress("DEPRECATION")
private fun classicFlutterCallback(callback: OnBackInvokedCallback): OnBackInvokedCallback? {
    // Flutter's predictive callback needs gesture state, so use its verified legacy entry.
    val owner = callback.javaClass.declaredFields.filter {
        !Modifier.isStatic(it.modifiers) &&
            (it.type == Any::class.java || Activity::class.java.isAssignableFrom(it.type))
    }.mapNotNull { it.apply { isAccessible = true }.get(callback) as? Activity }.singleOrNull() ?: return null
    val host = owner.javaClass.getMethod("onBackPressed").declaringClass
    if (host.superclass != Activity::class.java || !hasFlutterDelegate(host)) return null
    val field = host.declaredFields.singleOrNull {
        !Modifier.isStatic(it.modifiers) && it.type == OnBackInvokedCallback::class.java
    } ?: return null
    if (field.apply { isAccessible = true }.get(owner) !== callback) return null
    val reference = WeakReference(owner)
    return OnBackInvokedCallback { reference.get()?.onBackPressed() }
}

internal fun hasFlutterDelegate(host: Class<*>): Boolean {
    // FlutterJNI keeps its native name even when R8 renames the activity, delegate and engine.
    return host.declaredFields.any { delegate ->
        !Modifier.isStatic(delegate.modifiers) && delegate.type.classLoader === host.classLoader &&
            delegate.type.declaredFields.any { engine ->
                !Modifier.isStatic(engine.modifiers) && engine.type.classLoader === host.classLoader &&
                    engine.type.declaredFields.any { it.type.name == "io.flutter.embedding.engine.FlutterJNI" }
            }
    }
}
