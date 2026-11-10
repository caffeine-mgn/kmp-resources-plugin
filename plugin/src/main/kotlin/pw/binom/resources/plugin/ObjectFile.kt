package pw.binom.resources.plugin

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Запись объектника на диск без держания блоба в памяти.
 *
 * Объектник любого формата (ELF, Mach-O, COFF) — это `[заголовки][данные][таблицы]`.
 * Заголовки и таблицы маленькие и собираются в память, а данные льются прямо из
 * файла контейнера: так сборка 300-мегабайтного набора не съедает гигабайт heap
 * у демона Gradle.
 */
internal object ObjectFile {

    /**
     * @param pre байты до блока данных;
     * @param data файл с данными блоба;
     * @param dataSize сколько байт из [data] писать (0 — блока нет);
     * @param post байты после блока данных.
     */
    fun write(
        out: File,
        pre: ByteArray,
        data: File,
        dataSize: Long,
        postOffset: Int,
        post: ByteArray,
    ) {
        out.parentFile?.mkdirs()
        FileChannel.open(
            out.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { dst ->
            var position = 0L
            position = writeBuffer(dst, ByteBuffer.wrap(pre), position)

            if (dataSize > 0) {
                require(data.isFile) { "kmpres: нет файла данных ${data.absolutePath}" }
                FileChannel.open(data.toPath(), StandardOpenOption.READ).use { src ->
                    var copied = 0L
                    while (copied < dataSize) {
                        val n = src.transferTo(copied, dataSize - copied, dst.position(position))
                        check(n > 0) {
                            "kmpres: не удалось скопировать данные из ${data.absolutePath} " +
                                "(скопировано $copied из $dataSize)"
                        }
                        copied += n
                        position += n
                    }
                }
            }

            // Между концом данных и таблицами может быть выравнивание:
            // добиваем нулями до абсолютной позиции таблиц.
            if (position < postOffset) {
                writeBuffer(dst, ByteBuffer.allocate((postOffset - position).toInt()), position)
            }
            writeBuffer(dst, ByteBuffer.wrap(post), postOffset.toLong())
        }
    }

    private fun writeBuffer(channel: FileChannel, buffer: ByteBuffer, position: Long): Long {
        var pos = position
        while (buffer.hasRemaining()) {
            pos += channel.write(buffer, pos)
        }
        return pos
    }

    /** Найти clang/llvm-ar и прочие утилиты поставки Kotlin/Native. */
    fun findKonanTool(name: String): String? {
        val konanDir = System.getProperty("konan.data.dir")
            ?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".konan")

        val deps = File(konanDir, "dependencies")
        if (!deps.isDirectory) return null

        return deps.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("llvm-") }
            ?.sortedByDescending { it.name }
            ?.asSequence()
            ?.map { File(it, "bin/$name") }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    /** MD5 файла потоком, без чтения целиком. */
    fun md5(file: File): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    const val BUFFER_SIZE = 64 * 1024

    /** Скопировать файл в канал, обновляя дайджест. */
    fun copyInto(channel: FileChannel, file: File, digest: MessageDigest?): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest?.update(buffer, 0, n)
                var offset = 0
                while (offset < n) {
                    offset += channel.write(ByteBuffer.wrap(buffer, offset, n - offset))
                }
                total += n
            }
        }
        return total
    }

    fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
