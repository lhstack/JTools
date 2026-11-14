package com.lhstack.tools.debugger

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** 只复用调试器自己创建的配置；文件锁禁止两个 IDE 同时写同一份配置。 */
internal class SandboxProfile private constructor(
    val root: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    companion object {
        fun key(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun acquire(parent: Path, identity: String): SandboxProfile {
            val store = parent.resolve(".jtools-debug-profiles")
            if (!Files.exists(store, NOFOLLOW_LINKS)) Files.createDirectory(store)
            require(Files.isDirectory(store, NOFOLLOW_LINKS)) { "调试配置目录不能是符号链接" }
            val root = store.resolve(key(identity))
            val marker = root.resolve(".jtools-debug-profile")
            if (!Files.exists(root, NOFOLLOW_LINKS)) {
                Files.createDirectory(root)
                Files.writeString(marker, "jtools-debug-profile-v1", CREATE_NEW)
                if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(
                        root,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                    )
                }
            }
            require(Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
                "拒绝复用非调试器创建的配置目录"
            }
            require(Files.readString(marker) == "jtools-debug-profile-v1") { "调试配置标记无效" }
            val lockPath = root.resolve("profile.lock")
            require(!Files.isSymbolicLink(lockPath)) { "调试配置锁不能是符号链接" }
            val channel = FileChannel.open(lockPath, CREATE, WRITE)
            try {
                val lock = try {
                    channel.tryLock()
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    null
                }
                checkNotNull(lock) { "专用调试配置正在使用；请复用原沙箱，或使用 fresh=true" }
                for (name in listOf("config", "home")) {
                    val path = root.resolve(name)
                    if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
                    require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "调试配置子目录不能是符号链接" }
                }
                return SandboxProfile(root, channel, lock)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }
}
