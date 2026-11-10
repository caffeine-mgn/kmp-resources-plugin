package pw.binom.resources.plugin

/**
 * Архитектура нативного таргета — общая для ELF-, Mach-O- и COFF-писателей.
 *
 * Значения полей — то, что нужно соответствующему формату: `e_machine` для
 * ELF и `cputype`/`cpusubtype` для Mach-O.
 */
enum class NativeArch(
    val elfMachine: Int,
    val machoCpuType: Int,
    val machoCpuSubtype: Int,
) {
    /** x86-64: `EM_X86_64` / `CPU_TYPE_X86_64`. */
    X86_64(62, 0x01000007, 3),

    /** ARM64: `EM_AARCH64` / `CPU_TYPE_ARM64`. */
    AARCH64(183, 0x0100000C, 0),

    /**
     * ARM64_32 (watchOS): 32-битные указатели в 64-битном Mach-O,
     * `CPU_TYPE_ARM64_32` / `CPU_SUBTYPE_ARM64_32_V8`.
     */
    ARM64_32(183, 0x0200000C, 1),
}

/** Формат объектного файла, под который собирается архив ресурсов. */
enum class NativeFormat { ELF, MACHO, COFF }

/**
 * Нативный таргет проекта с точки зрения ресурсов.
 *
 * @param name имя таргета Kotlin (`linuxX64`, `macosArm64`, ...) — из него же
 *   строится имя каталога артефактов.
 * @param clangTarget трипл для `clang --target`: под него компилируется
 *   C-обёртка. Для Apple-таргетов версия ОС должна быть не выше минимальной
 *   у потребителя.
 */
data class NativeTargetSpec(
    val name: String,
    val format: NativeFormat,
    val arch: NativeArch,
    val clangTarget: String,
) {
    /** Имя подкаталога в `build/generated/kmpres/obj/`. */
    val id: String get() = name.lowercase()

    companion object {
        /**
         * Описание таргета по имени, `null` — если плагин его не умеет
         * (32-битные ARM: для них нужен 32-битный объектный формат).
         */
        fun of(targetName: String): NativeTargetSpec? = when (targetName.lowercase()) {
            // --- ELF
            "linuxx64" -> elf(targetName, NativeArch.X86_64, "x86_64-unknown-linux-gnu")
            "linuxarm64" -> elf(targetName, NativeArch.AARCH64, "aarch64-unknown-linux-gnu")
            "androidnativex64" -> elf(targetName, NativeArch.X86_64, "x86_64-linux-android")
            "androidnativearm64" -> elf(targetName, NativeArch.AARCH64, "aarch64-linux-android")

            // --- PE/COFF. Символы в x86-64 COFF пишутся без ведущего `_`.
            "mingwx64" -> NativeTargetSpec(
                targetName, NativeFormat.COFF, NativeArch.X86_64, "x86_64-w64-mingw32",
            )

            // --- Mach-O
            "macosx64" -> macho(targetName, NativeArch.X86_64, "x86_64-apple-macos10.13")
            "macosarm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-macos11.0")
            "iosx64" -> macho(targetName, NativeArch.X86_64, "x86_64-apple-ios12.0-simulator")
            "iosarm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-ios12.0")
            "iossimulatorarm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-ios12.0-simulator")
            "tvosx64" -> macho(targetName, NativeArch.X86_64, "x86_64-apple-tvos12.0-simulator")
            "tvosarm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-tvos12.0")
            "tvossimulatorarm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-tvos12.0-simulator")
            "watchosx64" -> macho(targetName, NativeArch.X86_64, "x86_64-apple-watchos5.0-simulator")
            "watchossimulatorarm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-watchos5.0-simulator")
            "watchosdevicearm64" -> macho(targetName, NativeArch.AARCH64, "arm64-apple-watchos5.0")
            "watchosarm64" -> macho(targetName, NativeArch.ARM64_32, "arm64_32-apple-watchos5.0")

            // androidNativeArm32/x86 и watchosArm32 (armv7k) — 32-битные, не поддержаны.
            else -> null
        }

        private fun elf(name: String, arch: NativeArch, triple: String) =
            NativeTargetSpec(name, NativeFormat.ELF, arch, triple)

        private fun macho(name: String, arch: NativeArch, triple: String) =
            NativeTargetSpec(name, NativeFormat.MACHO, arch, triple)
    }
}
