import java.util.Base64

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.20"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "pw.binom"

/*
 * Версия: -Pversion=<явно> (ручной прогон), затем имя тега (GITHUB_REF_NAME),
 * иначе — текущая версия разработки. Тег может прийти с префиксом `v`.
 */
version = providers.gradleProperty("version")
    .map { it.trim().trimStart('v', 'V') }
    .orElse(
        providers.environmentVariable("GITHUB_REF_NAME")
            .map { it.trim().trimStart('v', 'V') }
            .filter { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+.*")) },
    )
    .orElse("1.4.1")
    .get()

repositories {
    mavenCentral()
}

dependencies {
    // Полный KGP, а не только -api: `KotlinMultiplatformExtension`, `targets`,
    // `compilations`, `binaries.linkerOpts` живут в kotlin-gradle-plugin.
    // compileOnly — плагин не тянет компилятор Kotlin потребителю, тот
    // приносит свой.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    website = "https://github.com/caffeine-mgn/kmp-resources-plugin"
    vcsUrl = "https://github.com/caffeine-mgn/kmp-resources-plugin"
    plugins {
        create("kmpResources") {
            // Именно этот id используют потребители: plugins { id("pw.binom.resources") }
            id = "pw.binom.resources"
            implementationClass = "pw.binom.resources.plugin.KmpResourcesPlugin"
            displayName = "KMP Resources"
            description = "Вшивает файлы в нативный бинарник KMP без внешних зависимостей"
            tags = listOf("kotlin", "multiplatform", "kotlin-native", "resources", "elf", "mach-o", "coff")
        }
    }
}

java {
    // Gradle-плагины должны работать на JVM 17: потребитель может собирать
    // с более старой Java, а плагин грузится в его же JVM.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

/*
 * Подпись включается только когда есть чем подписывать: CI передаёт
 * `-PsigningUseGpg=true` (+ импортированный ключ), локально можно передать
 * in-memory ключ через `-PsigningInMemoryKey*`.
 */
val signingEnabled = findProperty("signingUseGpg") == "true" ||
    findProperty("signingInMemoryKey") != null ||
    findProperty("binom.gpg.private_key") != null

pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<org.gradle.plugins.signing.SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] Using system gpg via signing.gnupg.keyName=${findProperty("signing.gnupg.keyName")}")
        return@withPlugin
    }

    val key = providers.gradleProperty("signingInMemoryKey").orNull
        ?: providers.gradleProperty("binom.gpg.private_key").orNull
    val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
        ?: providers.gradleProperty("binom.gpg.key_id").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
        ?: providers.gradleProperty("binom.gpg.password").orNull
    val isBase64 = providers.gradleProperty("signingInMemoryKeyIsBase64").orNull?.toBoolean() ?: false

    if (key != null && keyId != null && password != null) {
        val decodedKey = if (isBase64) {
            String(Base64.getDecoder().decode(key))
        } else {
            key.replace("\\n", "\n")
        }
        extensions.configure<org.gradle.plugins.signing.SigningExtension>("signing") {
            useInMemoryPgpKeys(keyId, decodedKey, password)
        }
        logger.lifecycle("[signing] Using in-memory PGP key $keyId")
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (signingEnabled) {
        signAllPublications()
    }

    coordinates(
        groupId = "pw.binom",
        artifactId = "kmp-resources-plugin",
        version = project.version.toString(),
    )

    pom {
        name.set("kmp-resources-plugin")
        description.set(
            "Gradle-плагин pw.binom.resources: ресурсы для Kotlin Multiplatform " +
                "в ELF/Mach-O/COFF без objcopy, доступ из общего кода через Resources.",
        )
        url.set("https://github.com/caffeine-mgn/kmp-resources-plugin")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("subochev")
                name.set("Anton Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/caffeine-mgn/kmp-resources-plugin.git")
            developerConnection.set("scm:git:ssh://git@github.com/caffeine-mgn/kmp-resources-plugin.git")
            url.set("https://github.com/caffeine-mgn/kmp-resources-plugin")
        }
    }
}
