package com.jasongzy.classicback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ModuleMainTest {
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
}
