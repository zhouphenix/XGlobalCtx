package com.phenix.xglobal.ctx.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalContextTest {

    private class TestCapability(override val id: String) : GlobalCapability {
        var attached = false
        var detached = false
        var foregrounded = 0
        var backgrounded = 0
        var configChanged = 0

        override fun onAttach(context: GlobalContext) { attached = true }
        override fun onDetach() { detached = true }
        override fun onForeground() { foregrounded++ }
        override fun onBackground() { backgrounded++ }
        override fun onConfigurationChanged() { configChanged++ }
    }

    @Test
    fun `store get returns default and set updates value`() {
        val ctx = GlobalContextImpl()
        val key = StateKey("test.mode", "light")

        assertEquals("light", ctx.store.get(key))
        ctx.store.set(key, "dark")
        assertEquals("dark", ctx.store.get(key))
    }

    @Test
    fun `store keys with same id share state`() {
        val ctx = GlobalContextImpl()
        val k1 = StateKey("same.id", 1)
        val k2 = StateKey("same.id", 99)

        assertEquals(1, ctx.store.get(k1))
        assertEquals(1, ctx.store.get(k2)) // 同 id 同一存储槽
    }

    @Test
    fun `flow emits updates and dedupes same values`() = runTest {
        val ctx = GlobalContextImpl()
        val key = StateKey("test.flow", 0)
        val seen = mutableListOf<Int>()
        val job = launch { ctx.store.flow(key).collect { seen.add(it) } }
        testScheduler.runCurrent()

        ctx.store.set(key, 1)
        ctx.store.set(key, 2)
        testScheduler.runCurrent()

        // StateFlow conflation：collect 侧只保证看到最新值，连续写入可能合并
        job.cancel()
        assertEquals(listOf(0, 2), seen)
    }

    @Test
    fun `bus is non-sticky and delivers post-subscription events`() = runTest {
        val ctx = GlobalContextImpl()
        ctx.bus.tryPost("before")

        val received = mutableListOf<Any>()
        val job = launch { ctx.bus.events.collect { received.add(it) } }
        testScheduler.runCurrent()

        ctx.bus.post("after")
        testScheduler.runCurrent()
        job.cancel()

        assertFalse(received.contains("before"))
        assertEquals(listOf<Any>("after"), received)
    }

    @Test
    fun `capability lifecycle callbacks fire in order`() {
        val ctx = GlobalContextImpl()
        val cap = TestCapability("cap.a")

        ctx.register(cap)
        assertTrue(cap.attached)
        assertFalse(cap.foregrounded > 0) // 注册时处于后台，不触发前台回调

        ctx.notifyForeground()
        assertEquals(1, cap.foregrounded)

        // 后注册的能力若已在前台，立即补发 onForeground
        val cap2 = TestCapability("cap.b")
        ctx.register(cap2)
        assertEquals(1, cap2.foregrounded)

        ctx.notifyBackground()
        assertEquals(1, cap.backgrounded)

        ctx.notifyConfigurationChanged()
        assertEquals(1, cap.configChanged)

        ctx.unregister(cap)
        assertTrue(cap.detached)

        // 重复卸载无副作用
        ctx.unregister(cap)
        assertEquals(1, cap.backgrounded)
    }

    @Test
    fun `register same id twice is idempotent`() {
        val ctx = GlobalContextImpl()
        val cap = TestCapability("dup")
        ctx.register(cap)
        ctx.register(TestCapability("dup")) // 同 id 不同实例，忽略

        assertFailsWith<NoSuchElementException> {
            ctx.getCapability<TestCapability>("missing")
        }
        assertEquals(cap, ctx.getCapability<TestCapability>("dup"))
    }

    @Test
    fun `terminate detaches all capabilities`() {
        val ctx = GlobalContextImpl()
        val cap = TestCapability("t")
        ctx.register(cap)
        ctx.terminate()

        assertTrue(cap.detached)
        assertFailsWith<NoSuchElementException> { ctx.getCapability<TestCapability>("t") }
    }
}
