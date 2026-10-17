package com.jasongzy.classicback

import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ModuleMainTest {
    @Test
    fun adaptsObfuscatedNavigationInputAndPreservesCompletion() {
        val input = navigationInput()
        val replacement = requireNotNull(classicNavigationCallback(input.e))
        assertFalse(replacement is OnBackAnimationCallback)
        assertEquals(0, input.a.completed)
        replacement.onBackInvoked()
        replacement.onBackInvoked()
        assertEquals(2, input.a.completed)
        assertEquals(0, input.a.previews)
    }

    @Test
    fun adaptsNavigationInputMergedByR8() {
        val input = MergedNavigationInput(navigationInput().c, 0)
        val replacement = requireNotNull(classicNavigationCallback(input.e))
        assertFalse(replacement is OnBackAnimationCallback)
        replacement.onBackInvoked()
        assertEquals(1, input.a.completed)
    }

    @Test
    fun preservesUnknownCallbacksAndMismatchedOwners() {
        assertNull(classicNavigationCallback(OnBackInvokedCallback {}))
        assertNull(classicNavigationCallback(object : OnBackAnimationCallback {
            override fun onBackInvoked() = Unit
        }))
        val input = navigationInput()
        val original = input.e
        input.e = OnBackInvokedCallback {}
        assertNull(classicNavigationCallback(original))
    }

    @Test
    fun recognizesFlutterStructureAfterObfuscation() {
        assertTrue(hasFlutterDelegate(Host::class.java))
        assertFalse(hasFlutterDelegate(Any::class.java))
        assertFalse(hasFlutterDelegate(Engine::class.java))
    }

    @Test
    fun disablesSystemPreviewAcrossSupportedLayouts() {
        val callback = Any()
        for (args in listOf(listOf(callback, -1), listOf(callback, -1, false), listOf(callback, -1, false, 0))) {
            assertArrayEquals(args.toTypedArray().also { it[1] = 0 }, classicCallbackInfoArgs(args))
            assertEquals(-1, args[1])
        }
    }

    @Test
    fun preservesAppAnimationsOverridesAndUnknownLayouts() {
        val callback = Any()
        for (args in listOf(
            listOf(callback, 0, false), listOf(callback, -1, true),
            listOf(callback, -1, false, 1), listOf(callback, -1, false, 2),
            listOf(callback), listOf(callback, -1, false, 0, 0), listOf(callback, -1, "false"),
        )) {
            assertNull(classicCallbackInfoArgs(args))
        }
    }

    private class Host(val a: Delegate)
    private class Delegate(val b: Engine)
    private class Engine(val c: io.flutter.embedding.engine.FlutterJNI)

    private fun navigationInput(): NavigationInput {
        val dispatcher = object : OnBackInvokedDispatcher {
            override fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) = Unit
            override fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) = Unit
        }
        return object : NavigationInput(dispatcher, 0) {}
    }

    private class NavigationDispatcher {
        var completed = 0
        var previews = 0
    }

    private abstract class NavigationInputBase {
        val a = NavigationDispatcher()
        var b = false
    }

    private abstract class NavigationInput(val c: OnBackInvokedDispatcher, val d: Int) : NavigationInputBase() {
        var e: OnBackInvokedCallback = NavigationCallback(this)
        var f = false
    }

    private class NavigationCallback(val a: NavigationInput) : OnBackAnimationCallback {
        override fun onBackInvoked() { a.a.completed++ }
        override fun onBackStarted(backEvent: BackEvent) { a.a.previews++ }
        override fun onBackProgressed(backEvent: BackEvent) { a.a.previews++ }
        override fun onBackCancelled() { a.a.previews++ }
    }

    private class MergedNavigationInput(val c: OnBackInvokedDispatcher, val d: Int) : NavigationInputBase() {
        val e: OnBackInvokedCallback = MergedNavigationCallback(this)
        var f = false
    }

    private class MergedNavigationCallback(val a: MergedNavigationInput) : OnBackAnimationCallback {
        override fun onBackInvoked() { a.a.completed++ }
    }
}
