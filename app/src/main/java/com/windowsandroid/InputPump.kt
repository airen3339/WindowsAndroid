package com.windowsandroid

import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keyboard input for the guest, in order, off the UI thread. Typed text becomes key presses
 * (with Shift where the US layout needs it) spaced a few milliseconds apart, so a burst from
 * the IME or a paste never overruns the xHCI report queue. Sticky modifiers from the extra-keys
 * bar (Ctrl, Alt, Win, Shift) wrap the next key and then release.
 */
object InputPump {
    private val queue = LinkedBlockingQueue<() -> Unit>()
    private const val GAP_MS = 3L

    // HID modifier usages as bits: 1 shl (usage - 0xe0). Toggled on the UI thread, taken on the pump's.
    private val sticky = AtomicInteger(0)
    val stickyMods: Int get() = sticky.get()
    /** Called on the main thread. */
    var onStickyChanged: ((Int) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private fun notifySticky(v: Int) = main.post { onStickyChanged?.invoke(v) }

    init {
        Thread({
            while (true) {
                val job = queue.take()
                try { job() } catch (_: Throwable) {}
            }
        }, "winvm-input").apply { isDaemon = true; start() }
    }

    fun post(job: () -> Unit) { queue.put(job) }

    fun toggleSticky(usage: Int) {
        notifySticky(sticky.updateAndGet { it xor (1 shl (usage - 0xe0)) })
    }

    private fun takeSticky(): Int {
        val m = sticky.getAndSet(0)
        if (m != 0) notifySticky(0)
        return m
    }

    private fun down(usage: Int) { VmNative.nativeKeyHid(usage, true); Thread.sleep(GAP_MS) }
    private fun up(usage: Int) { VmNative.nativeKeyHid(usage, false); Thread.sleep(GAP_MS) }

    /** Press and release one key, with any sticky modifiers (and extra ones) held around it. */
    fun tap(usage: Int, vararg mods: Int) = post {
        val sticky = takeSticky()
        val held = (0 until 8).filter { sticky and (1 shl it) != 0 }.map { 0xe0 + it } + mods.toList()
        held.forEach { down(it) }
        down(usage); up(usage)
        held.asReversed().forEach { up(it) }
    }

    /** A key going down or up as the user holds it (hardware keyboards). */
    fun key(usage: Int, isDown: Boolean) = post { VmNative.nativeKeyHid(usage, isDown) }

    fun keyLinux(code: Int, isDown: Boolean) = post { VmNative.nativeKeyLinux(code, isDown) }

    fun typeText(text: CharSequence) {
        for (c in text) {
            val (usage, shift) = HidKeys.forChar(c) ?: continue
            if (shift) tap(usage, HidKeys.LSHIFT) else tap(usage)
        }
    }

    fun backspace(count: Int) = repeat(count) { tap(HidKeys.BACKSPACE) }

    fun combo(vararg usages: Int) = post {
        usages.forEach { down(it) }
        Thread.sleep(30)
        usages.reversed().forEach { up(it) }
    }
}
