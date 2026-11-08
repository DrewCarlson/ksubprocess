package ksubprocess

import kotlinx.cinterop.*
import ksubprocess.io.KFileHandle
import platform.Foundation.*
import platform.posix.memcpy

internal class OkioNSFileHandle(
    readWrite: Boolean,
    private val file: NSFileHandle
) : KFileHandle(readWrite) {
    override fun protectedClose() {
        file.closeAndReturnError(null)
    }

    override fun protectedFlush() {
        file.synchronizeAndReturnError(null)
    }

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (array.isEmpty()) return -1
        val data = file.readDataOfLength(byteCount.toULong())
        if (data.length() == 0uL) {
            return -1
        }
        val bytes = data.toByteArray()
        bytes.copyInto(array, arrayOffset, 0, bytes.size)
        return bytes.size
    }

    override fun protectedResize(size: Long) {
        file.truncateFileAtOffset(size.toULong())
    }

    override fun protectedSize(): Long {
        return file.availableData.length.toLong()
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        if (byteCount == 0) return
        val data = array.usePinned { NSData.dataWithBytes(it.addressOf(arrayOffset), byteCount.convert()) }
        file.writeData(data)
    }
}

internal fun NSData.toByteArray(): ByteArray = ByteArray(this@toByteArray.length.toInt()).apply {
    usePinned {
        memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
    }
}
