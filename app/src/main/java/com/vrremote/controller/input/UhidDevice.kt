package com.vrremote.controller.input

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One /dev/uhid node, i.e. one virtual HID device.
 *
 * Talked to with the plain Linux UHID ABI (see include/uapi/linux/uhid.h), the
 * same approach scrcpy uses for its non-rooted UHID mode.
 */
internal class UhidDevice(
    private val name: String,
    private val reportDescriptor: ByteArray,
    private val vendorId: Int = 0x1209,
    private val productId: Int = 0x0001,
) {

    private companion object {
        const val TAG = "VrRemoteInput"

        const val UHID_CREATE2 = 11
        const val UHID_INPUT2 = 12

        /** Linux BUS_VIRTUAL. */
        const val BUS_VIRTUAL = 0x0006

        /** sizeof(struct uhid_event), rounded up for safety. */
        const val UHID_EVENT_SIZE = 4380

        /** name[128] + phys[64] + uniq[64] + rd_size + bus + 4 ints. */
        const val CREATE2_HEADER_SIZE = 276
    }

    private var descriptor: FileDescriptor? = null
    private var reader: Thread? = null
    private val readerBuffer = ByteBuffer.allocateDirect(UHID_EVENT_SIZE).order(ByteOrder.nativeOrder())

    val isOpen: Boolean get() = descriptor != null

    /** @return 0 on success, a negative errno value otherwise. */
    fun open(): Int {
        if (descriptor != null) return 0
        val fd = try {
            Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
        } catch (error: ErrnoException) {
            Log.w(TAG, "cannot open /dev/uhid: ${error.errno}", error)
            return -error.errno
        }
        val request = create2Request()
        return try {
            Os.write(fd, request, 0, request.size)
            descriptor = fd
            startReader(fd)
            0
        } catch (error: ErrnoException) {
            Log.w(TAG, "cannot create $name: ${error.errno}", error)
            closeQuietly(fd)
            -error.errno
        }
    }

    fun send(report: ByteArray): Boolean {
        val fd = descriptor ?: return false
        return try {
            val request = ByteBuffer
                .allocate(4 + 2 + report.size)
                .order(ByteOrder.nativeOrder())
                .putInt(UHID_INPUT2)
                .putShort(report.size.toShort())
                .put(report)
                .array()
            Os.write(fd, request, 0, request.size)
            true
        } catch (error: ErrnoException) {
            Log.w(TAG, "cannot send report to $name: ${error.errno}", error)
            false
        }
    }

    fun close() {
        val fd = descriptor ?: return
        descriptor = null
        reader?.interrupt()
        reader = null
        closeQuietly(fd)
    }

    private fun create2Request(): ByteArray {
        val buffer = ByteBuffer
            .allocate(4 + CREATE2_HEADER_SIZE + reportDescriptor.size)
            .order(ByteOrder.nativeOrder())
        buffer.putInt(UHID_CREATE2)
        buffer.put(padded(name.toByteArray(Charsets.UTF_8), 128))
        buffer.put(ByteArray(64)) // phys
        buffer.put(ByteArray(64)) // uniq
        buffer.putShort(reportDescriptor.size.toShort())
        buffer.putShort(BUS_VIRTUAL.toShort())
        buffer.putInt(vendorId)
        buffer.putInt(productId)
        buffer.putInt(1) // version
        buffer.putInt(0) // country
        buffer.put(reportDescriptor)
        return buffer.array()
    }

    /**
     * The kernel reports UHID_START/OPEN/OUTPUT back through the same node. We
     * do not need the payload, but the events have to be drained or the queue
     * eventually blocks the HID layer.
     */
    private fun startReader(fd: FileDescriptor) {
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted && descriptor != null) {
                try {
                    readerBuffer.clear()
                    Os.read(fd, readerBuffer)
                } catch (error: ErrnoException) {
                    break
                } catch (error: Exception) {
                    break
                }
            }
        }, "uhid-$name")
        thread.isDaemon = true
        reader = thread
        thread.start()
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (ignored: Exception) {
        }
    }

    private fun padded(value: ByteArray, size: Int): ByteArray {
        val result = ByteArray(size)
        value.copyInto(result, endIndex = minOf(value.size, size))
        return result
    }
}
