import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.squareup.wire")
}

val daemonTargetByPlatform = linkedMapOf(
    "android-arm64" to "arm64-v8a:aarch64-linux-android",
    "android-arm" to "armeabi-v7a:armv7-linux-androideabi",
    "android-x64" to "x86_64:x86_64-linux-android",
    "android-x86" to "x86:i686-linux-android",
)
val defaultDaemonTargets = listOf(
    "arm64-v8a:aarch64-linux-android",
    "armeabi-v7a:armv7-linux-androideabi",
    "x86_64:x86_64-linux-android",
)
val daemonTargets =
    (rootProject.findProperty("target-platform") as String?)
        ?.split(",")
        ?.map {
            daemonTargetByPlatform[it.trim()]
                ?: throw GradleException("No vpnhotspotd target for Flutter platform $it")
        }
        ?: defaultDaemonTargets

val isWindows = System.getProperty("os.name").startsWith("Windows")

val cargoExecutablePath = providers.provider {
    val name = if (isWindows) "cargo.exe" else "cargo"
    val home = System.getProperty("user.home")
    val searchPath =
        System.getenv("PATH").orEmpty().split(File.pathSeparator) +
            listOf("$home/.cargo/bin", "/opt/homebrew/bin", "/usr/local/bin")
    searchPath
        .filter { it.isNotEmpty() }
        .map { File(it, name) }
        .firstOrNull { it.canExecute() }
        ?.path
        ?: name
}

abstract class BuildDaemonNativeLibsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoDir: DirectoryProperty

    @get:Input
    abstract val cargoProfile: Property<String>

    @get:Input
    abstract val androidPlatform: Property<Int>

    @get:Input
    abstract val abiTargets: ListProperty<String>

    @get:Internal
    abstract val cargoExecutable: Property<String>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val targetDir: DirectoryProperty

    @TaskAction
    fun build() {
        val cargoDir = sourceDir.get().asFile
        val targetDir = targetDir.get().asFile
        val cargoBin = cargoExecutable.get()
        val cargoParent = File(cargoBin).parentFile?.absolutePath
        val existingPath = System.getenv("PATH").orEmpty()
        val fullPath = if (cargoParent != null) "$cargoParent${File.pathSeparator}$existingPath" else existingPath
        outputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val profile = cargoProfile.get()
        val rustFlags = listOf(
            "--remap-path-prefix=${System.getProperty("user.home").trimEnd('/')}/=",
            "--remap-path-prefix=${cargoDir.absolutePath}=.",
            "--remap-path-prefix=${cargoDir.absolutePath}/=",
        ).joinToString("\u001F")
        for (entry in abiTargets.get()) {
            val (abi, target) = entry.split(":", limit = 2)
            val command = mutableListOf(
                cargoBin,
                "ndk",
                "--target",
                abi,
                "--platform",
                androidPlatform.get().toString(),
                "build",
                "--locked",
                "--bin",
                "vpnhotspotd",
            ).apply {
                if (profile == "release") add("--release")
            }
            val process = ProcessBuilder(command).directory(cargoDir).redirectErrorStream(true).apply {
                environment().run {
                    this["PATH"] = fullPath
                    this["ANDROID_NDK_HOME"] = ndkDirectory.get().asFile.absolutePath
                    this["CARGO_BUILD_TARGET_DIR"] = targetDir.absolutePath
                    this["CARGO_ENCODED_RUSTFLAGS"] = rustFlags
                }
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) {
                "cargo ndk build failed for $target\n$output"
            }
            val binary = targetDir.resolve("$target/$profile/vpnhotspotd")
            check(binary.isFile) { "Missing daemon binary: ${binary.absolutePath}" }
            outputDir.file("$abi/libvpnhotspotd.so").get().asFile.apply {
                parentFile.mkdirs()
                binary.copyTo(this, overwrite = true)
            }
        }
    }
}

abstract class GenerateSupplicantAidlJavaTask : DefaultTask() {
    @get:Input
    abstract val minSdkVersion: Property<Int>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val supplicantAidlDir: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val commonAidlDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aidlExecutable: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val frameworkAidl: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val sourceDirs = listOf(supplicantAidlDir.get().asFile, commonAidlDir.get().asFile)
        for (sourceDir in sourceDirs) check(sourceDir.isDirectory) {
            "Missing vendored AOSP supplicant AIDL directory: ${sourceDir.absolutePath}"
        }
        val outputDir = outputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val aidlFiles = sourceDirs.asSequence().flatMap { sourceDir ->
            sourceDir.walkTopDown().filter { it.isFile && it.extension == "aidl" }
        }.sortedBy { it.path }.toList()
        val process = ProcessBuilder(
            buildList {
                add(aidlExecutable.get().asFile.absolutePath)
                add("--lang=java")
                add("--structured")
                add("--stability=vintf")
                add("--version=5")
                add("--min_sdk_version=${minSdkVersion.get()}")
                add("-p")
                add(frameworkAidl.get().asFile.absolutePath)
                for (sourceDir in sourceDirs) {
                    add("-I")
                    add(sourceDir.absolutePath)
                }
                add("-o")
                add(outputDir.absolutePath)
                addAll(aidlFiles.map { it.absolutePath })
            },
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "aidl failed\n$output" }
        var strippedVintfStabilityCalls = 0
        var rewrittenParcelableStabilityMethods = 0
        val markVintfStabilityCall = "      this.markVintfStability();\n"
        val parcelableStabilityMethod = Regex(
            "  @Override\n[ \\t]+public final int getStability\\(\\) \\{ " +
                "return android\\.os\\.Parcelable\\.PARCELABLE_STABILITY_VINTF; }\n",
        )
        val rewrittenParcelableStabilityMethod = "  public final int getStability() { return 1; }\n"
        for (file in outputDir.walkTopDown().filter { it.isFile && it.extension == "java" }) {
            val source = file.readText()
            strippedVintfStabilityCalls += source.split(markVintfStabilityCall).size - 1
            rewrittenParcelableStabilityMethods += parcelableStabilityMethod.findAll(source).count()
            val next = source
                .replace(markVintfStabilityCall, "")
                .let { parcelableStabilityMethod.replace(it, rewrittenParcelableStabilityMethod) }
            if (source != next) file.writeText(next)
        }
        check(strippedVintfStabilityCalls > 0) { "Generated AIDL Java did not contain markVintfStability calls." }
        check(rewrittenParcelableStabilityMethods > 0) {
            "Generated AIDL Java did not contain Parcelable VINTF stability methods."
        }
    }
}

val javaVersion = 17
android {
    namespace = "be.mygod.vpnhotspot"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndkVersion.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        warning += "FullBackupContent"
        warning += "UnsafeOptInUsageError"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val hiddenApiStubAnnotations = configurations.create("hiddenApiStubAnnotations")
val compileHiddenApiStubs = tasks.register<JavaCompile>("compileHiddenApiStubs") {
    source("src/hiddenApiStubs/java")
    classpath = files(androidComponents.sdkComponents.bootClasspath) + hiddenApiStubAnnotations
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/hiddenApiStubs/classes"))
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
    options.release.set(javaVersion)
}
val hiddenApiStubsClasses = files(compileHiddenApiStubs.flatMap { it.destinationDirectory })
    .builtBy(compileHiddenApiStubs)
val hiddenApiStubsJar = tasks.register<Jar>("hiddenApiStubsJar") {
    from(hiddenApiStubsClasses)
    archiveFileName.set("hidden-api-stubs.jar")
}

wire {
    kotlin {
        enumMode = "sealed_class"
        rpcRole = "none"
    }
}

androidComponents.onVariants { variant ->
    val variantTitle = variant.name.replaceFirstChar(Char::titlecase)
    val supplicantAidlTask = tasks.register<GenerateSupplicantAidlJavaTask>("generate${variantTitle}SupplicantAidlJava") {
        minSdkVersion.set(android.defaultConfig.minSdk!!)
        supplicantAidlDir.set(
            layout.projectDirectory.dir("src/main/aidl-supplicant/android.hardware.wifi.supplicant/5"),
        )
        commonAidlDir.set(
            layout.projectDirectory.dir("src/main/aidl-supplicant/android.hardware.wifi.common/2"),
        )
        aidlExecutable.set(androidComponents.sdkComponents.aidl.flatMap { it.executable })
        frameworkAidl.set(androidComponents.sdkComponents.aidl.flatMap { it.framework })
        outputDir.set(layout.buildDirectory.dir("generated/source/supplicantAidl/${variant.name}"))
    }
    variant.sources.java?.addGeneratedSourceDirectory(supplicantAidlTask, GenerateSupplicantAidlJavaTask::outputDir)

    val daemonTask = tasks.register<BuildDaemonNativeLibsTask>(
        "build${variantTitle}DaemonNativeLibs",
    ) {
        sourceDir.set(layout.projectDirectory.dir("src/main/rust/vpnhotspotd"))
        protoDir.set(layout.projectDirectory.dir("src/main/proto"))
        cargoProfile.set(if (variant.buildType == "release") "release" else "debug")
        androidPlatform.set(android.defaultConfig.minSdk!!)
        abiTargets.set(daemonTargets)
        cargoExecutable.set(cargoExecutablePath)
        ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
        outputDir.set(layout.buildDirectory.dir("generated/nativeLibs/daemon/${variant.name}"))
        targetDir.set(layout.buildDirectory.dir("rust/vpnhotspotd"))
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(daemonTask, BuildDaemonNativeLibsTask::outputDir)
}

ksp {
    arg("room.expandProjection", "true")
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    compileOnly(files(hiddenApiStubsJar))
    ksp(libs.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.browser)
    implementation(libs.hiddenapibypass)
    implementation(libs.ktor.io.jvm)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.librootkotlinx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.ktx)
    implementation(libs.timber)
    implementation(libs.wire.runtime)
    implementation(libs.zxing.core)
    debugImplementation("androidx.compose.ui:ui-tooling")
    hiddenApiStubAnnotations(libs.annotation.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
