// 糖然科技 TangranTech —— 独立 Slimefun 附属插件构建脚本（对齐 WorldTaste-Plugin 结构）
// 仅依赖 Paper API 与本地 Slimefun jar，不联网下载 Slimefun。
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "com.tangran"
version = "1.0.0-standalone"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = "UTF-8"
    // 目标服务端为 1.21.x（Paper 要求 Java 21），编译基线直接取 21，
    // 与 Komutech-Plugin 保持一致。
    options.release.set(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // @Nullable/@NotNull 仅用于标注可空契约，编译期可见即可，不打入产物
    compileOnly("org.jetbrains:annotations:24.1.0")
    // 本地 Slimefun 依赖（compileOnly，运行期由服务器提供）
    compileOnly(files("libs/Slimefun4-347d926-Beta.jar"))
}

// paper-api 1.21.x 只发布 JVM 21 variant，显式指定以正常选中依赖。
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

// 把 content/ 下的内容 YAML 与数据文件一并打入 jar（插件运行期从自身资源读取）。
val contentFiles = listOf(
    "groups.yml", "recipe_types.yml", "items.yml", "machines.yml", "mob_drops.yml",
    "recipe_machines.yml", "mb_machines.yml", "template_machines.yml", "menus.yml",
    "generators.yml", "mat_generators.yml", "super_multi_block_machines.yml"
)

tasks.processResources {
    filteringCharset = "UTF-8"
    from(rootProject.projectDir.resolve("content")) {
        include(contentFiles)
        include("data/**")
        into("") // 置于 jar 根目录
    }
}

tasks.jar {
    archiveBaseName.set("TangranTech")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.build {
    dependsOn(tasks.jar)
}
