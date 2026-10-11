package com.jasongzy.classicback

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.window.OnBackInvokedCallback
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

class ModuleMain : XposedModule() {
    private val callbacks = WeakHashMap<OnBackInvokedCallback, OnBackInvokedCallback>()
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
                    callbacks[callback] ?: classicFlutterCallback(callback)?.also { callbacks[callback] = it }
                }
                if (replacement == null) chain.proceed() else {
                    val args = chain.args.toTypedArray()
                    args[0] = replacement
                    chain.proceed(args)
                }
            }
            handles += hook(unregister).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val callback = chain.args[0] as? OnBackInvokedCallback
                val replacement = if (enabled) synchronized(callbacks) { callbacks[callback] } else null
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

@Suppress("DEPRECATION")
private fun classicFlutterCallback(callback: OnBackInvokedCallback): OnBackInvokedCallback? {
    // shortcut: only Flutter's verified legacy entry is adapted; add others after verifying their back contract.
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
