package ksubprocess

import kotlinx.cinterop.*
import ksubprocess.io.KFileHandle
import ksubprocess.io.PosixException
import platform.Foundation.*
import platform.posix.read

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
        if (byteCount == 0) return 0
        // readDataOfLength waits for the full length, read returns as soon as data is available
        val bytesRead = array.usePinned {
            read(file.fileDescriptor, it.addressOf(arrayOffset), byteCount.convert())
        }.toInt()
        if (bytesRead == -1) throw PosixException.forErrno("read")
        return if (bytesRead == 0) -1 else bytesRead
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
