package pw.binom.resources.plugin

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запись ELF64-релокатива (.o) с ресурсами и символами.
 *
 * Зачем свой писатель: `objcopy -I binary` в окружении Kotlin/Native нет —
 * в каталоге `~/.konan/dependencies/llvm-<версия>/bin/` лежат clang, lld,
 * llvm-ar, но НЕ objcopy. Публичный плагин не может требовать системный
 * binutils, поэтому объектный файл собираем байтами сами.
 *
 * ## Что генерируется
 *
 * Секции:
 * ```
 * [0] NULL          — обязательная пустая
 * [1] .data         — сами ресурсы (контейнер KRES)
 * [2] .symtab       — символы
 * [3] .strtab       — имена символов
 * [4] .shstrtab     — имена секций
 * ```
 *
 * ## Память
 *
 * Данные не поднимаются в кучу: [writeTo] пишет заголовки и таблицы, а блоб
 * отдаёт потоком из файла контейнера через [ObjectFile].
 */
object ElfWriter {

    /** Запись символа. */
    private data class Sym(
        val nameOffset: Int,
        val info: Int,
        val shndx: Int,
        val value: Long,
    )

    // Константы ELF
    private const val SHT_NULL = 0
    private const val SHT_PROGBITS = 1
    private const val SHT_SYMTAB = 2
    private const val SHT_STRTAB = 3
    private const val SHF_WRITE = 1L
    private const val SHF_ALLOC = 2L
    private const val SHN_ABS = 0xFFF1
    private const val STB_GLOBAL = 1
    private const val STT_OBJECT = 1
    private const val STT_NOTYPE = 0

    /**
     * Собрать `.o` с ресурсами.
     *
     * @param data файл контейнера (льётся в секцию данных потоком);
     * @param dataSize сколько байт из него взять;
     * @param sectionName имя секции с данными; по умолчанию `.data`.
     *   Для неизменяемых данных имеет смысл `.rodata` — тогда страницы
     *   получатся read-only и шариться между процессами.
     * @param writable ставить ли `SHF_WRITE`. Должно соответствовать смыслу
     *   имени секции: `.rodata` с флагом записи — противоречие.
     */
    fun writeTo(
        out: File,
        data: File,
        dataSize: Long,
        arch: NativeArch,
        startSymbol: String,
        endSymbol: String,
        sizeSymbol: String,
        sectionName: String = ".data",
        writable: Boolean = true,
        dataAlign: Int = 16,
    ) {
        // --- .strtab: имена символов
        val strtab = StringBuilder()
        // ELF: первый байт таблицы строк — пустая строка, на неё ссылается
        // null-символ (индекс 0). Без него имя первого символа получает
        // смещение 0 и читается как пустое: lld на Linux это терпит, а на
        // Android такой объект уже не линкуется.
        strtab.append('\u0000')
        val nameOffsets = mutableMapOf<String, Int>()
        fun intern(name: String): Int {
            nameOffsets[name]?.let { return it }
            val offset = strtab.length
            strtab.append(name).append('\u0000')
            nameOffsets[name] = offset
            return offset
        }

        val startOff = intern(startSymbol)
        val endOff = intern(endSymbol)
        val sizeOff = intern(sizeSymbol)
        val strtabBytes = strtab.toString().toByteArray(Charsets.UTF_8)

        // --- символы
        val globalObjectInfo = (STB_GLOBAL shl 4) or STT_OBJECT
        val syms = listOf(
            // null-символ обязателен первым
            Sym(nameOffset = 0, info = 0, shndx = 0, value = 0),
            Sym(startOff, globalObjectInfo, shndx = 1, value = 0),
            Sym(endOff, globalObjectInfo, shndx = 1, value = dataSize),
            Sym(sizeOff, (STB_GLOBAL shl 4) or STT_NOTYPE, shndx = SHN_ABS, value = dataSize),
        )

        // --- имена секций
        val shstrtab = StringBuilder()
        val shNames = mutableMapOf<String, Int>()
        fun shName(name: String): Int {
            val offset = shstrtab.length
            shstrtab.append(name).append('\u0000')
            shNames[name] = offset
            return offset
        }

        shName("")          // NULL-секция
        shName(sectionName)
        shName(".symtab")
        shName(".strtab")
        shName(".shstrtab")
        val shstrtabBytes = shstrtab.toString().toByteArray(Charsets.UTF_8)

        // --- раскладка файла
        val ehdrSize = 64
        val dataOffset = align(ehdrSize, dataAlign)
        val symtabOffset = align(dataOffset + dataSize, 8)
        val symtabSize = syms.size * 24
        val strtabOffset = symtabOffset + symtabSize
        val shstrtabOffset = align(strtabOffset + strtabBytes.size, 8)
        val shoff = align(shstrtabOffset + shstrtabBytes.size, 8)
        val shnum = 5
        val total = shoff + shnum * 64

        // Заголовок (и добивка до начала данных).
        val pre = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        pre.put(0x7F)
        pre.put('E'.code.toByte())
        pre.put('L'.code.toByte())
        pre.put('F'.code.toByte())
        pre.put(2)              // ELFCLASS64
        pre.put(1)              // ELFDATA2LSB
        pre.put(1)              // EV_CURRENT
        pre.put(0)              // ELFOSABI_NONE
        repeat(8) { pre.put(0) } // padding
        pre.putShort(1)         // e_type = ET_REL
        pre.putShort(arch.elfMachine.toShort()) // e_machine
        pre.putInt(1)           // e_version
        pre.putLong(0)          // e_entry
        pre.putLong(0)          // e_phoff
        pre.putLong(shoff.toLong()) // e_shoff
        pre.putInt(0)           // e_flags
        pre.putShort(64)        // e_ehsize
        pre.putShort(0)         // e_phentsize
        pre.putShort(0)         // e_phnum
        pre.putShort(64)        // e_shentsize
        pre.putShort(shnum.toShort()) // e_shnum
        pre.putShort(4)         // e_shstrndx

        // Таблицы после данных: пишем в отдельный буфер, позиции — относительно
        // начала этого буфера (symtabOffset).
        val post = ByteBuffer.allocate((total - symtabOffset)).order(ByteOrder.LITTLE_ENDIAN)

        post.position(0)
        for (s in syms) {
            post.putInt(s.nameOffset)
            post.put(s.info.toByte())
            post.put(0)                       // st_other
            post.putShort(s.shndx.toShort())
            post.putLong(s.value)
            post.putLong(0)                   // st_size
        }

        post.position((strtabOffset - symtabOffset))
        post.put(strtabBytes)

        post.position((shstrtabOffset - symtabOffset))
        post.put(shstrtabBytes)

        post.position((shoff - symtabOffset))

        // [0] NULL
        writeShdr(post, 0, SHT_NULL, 0, 0, 0, 0, 0, 0, 0)

        // [1] секция с данными
        writeShdr(
            post, shNames[sectionName]!!, SHT_PROGBITS,
            flags = SHF_ALLOC or (if (writable) SHF_WRITE else 0L),
            offset = dataOffset.toLong(),
            size = dataSize,
            link = 0, info = 0, addralign = dataAlign.toLong(), entsize = 0,
        )

        // [2] .symtab
        writeShdr(
            post, shNames[".symtab"]!!, SHT_SYMTAB,
            flags = 0,
            offset = symtabOffset.toLong(),
            size = symtabSize.toLong(),
            link = 3,            // .strtab
            info = 1,            // индекс первого глобального символа
            addralign = 8,
            entsize = 24,
        )

        // [3] .strtab
        writeShdr(
            post, shNames[".strtab"]!!, SHT_STRTAB,
            flags = 0,
            offset = strtabOffset.toLong(),
            size = strtabBytes.size.toLong(),
            link = 0, info = 0, addralign = 1, entsize = 0,
        )

        // [4] .shstrtab
        writeShdr(
            post, shNames[".shstrtab"]!!, SHT_STRTAB,
            flags = 0,
            offset = shstrtabOffset.toLong(),
            size = shstrtabBytes.size.toLong(),
            link = 0, info = 0, addralign = 1, entsize = 0,
        )

        ObjectFile.write(out, pre.array(), data, dataSize, symtabOffset, post.array())
    }

    private fun writeShdr(
        buf: ByteBuffer,
        name: Int,
        type: Int,
        flags: Long,
        offset: Long,
        size: Long,
        link: Int,
        info: Int,
        addralign: Long,
        entsize: Long,
    ) {
        buf.putInt(name)
        buf.putInt(type)
        buf.putLong(flags)
        buf.putLong(0)      // sh_addr
        buf.putLong(offset)
        buf.putLong(size)
        buf.putInt(link)
        buf.putInt(info)
        buf.putLong(addralign)
        buf.putLong(entsize)
    }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) / alignment * alignment

    private fun align(value: Long, alignment: Int): Int =
        align(value.toInt(), alignment)
}
