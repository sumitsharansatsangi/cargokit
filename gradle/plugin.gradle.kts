// This is copied from Cargokit (which is the official way to use it currently).
// Details: https://fzyzcjy.github.io/flutter_rust_bridge/manual/integrate/builtin

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Paths
import java.util.Locale
import javax.inject.Inject

CargoKitPlugin.file = buildscript.sourceFile

apply<CargoKitPlugin>()

open class CargoKitExtension {
    var manifestDir: String? = null
    var libname: String? = null
}

abstract class CargoKitBuildTask : DefaultTask() {
    @get:Input
    abstract val buildMode: Property<String>

    @get:Input
    abstract val cargoBuildDir: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val sdkDirectory: Property<String>

    @get:Input
    abstract val compileSdkVersion: Property<Int>

    @get:Input
    abstract val minSdkVersion: Property<Int>

    @get:InputFile
    abstract val pluginFile: RegularFileProperty

    @get:Input
    abstract val targetPlatforms: ListProperty<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val extension = project.extensions.getByType(CargoKitExtension::class.java)
        val manifestDirValue =
            extension.manifestDir
                ?: throw GradleException("Property 'manifestDir' must be set on cargokit extension")
        extension.libname
            ?: throw GradleException("Property 'libname' must be set on cargokit extension")

        val executableName =
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                "run_build_tool.cmd"
            } else {
                "run_build_tool.sh"
            }
        val path = Paths.get(pluginFile.get().asFile.parent, "..", executableName).normalize()
        val manifestDir = Paths.get(project.buildFile.parent, manifestDirValue).normalize()
        val rootProjectDir = project.rootProject.projectDir

        if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
            execOperations.exec {
                commandLine("chmod", "+x", path.toString())
            }
        }

        execOperations.exec {
            executable = path.toString()
            args("build-gradle")
            environment("CARGOKIT_ROOT_PROJECT_DIR", rootProjectDir)
            environment("CARGOKIT_TOOL_TEMP_DIR", "${cargoBuildDir.get()}/build_tool")
            environment("CARGOKIT_MANIFEST_DIR", manifestDir)
            environment("CARGOKIT_CONFIGURATION", buildMode.get())
            environment("CARGOKIT_TARGET_TEMP_DIR", cargoBuildDir.get())
            environment("CARGOKIT_OUTPUT_DIR", outputDir.get().asFile)
            environment("CARGOKIT_NDK_VERSION", ndkVersion.get())
            environment("CARGOKIT_SDK_DIR", sdkDirectory.get())
            environment("CARGOKIT_COMPILE_SDK_VERSION", compileSdkVersion.get())
            environment("CARGOKIT_MIN_SDK_VERSION", minSdkVersion.get())
            environment("CARGOKIT_TARGET_PLATFORMS", targetPlatforms.get().joinToString(","))
            environment("CARGOKIT_JAVA_HOME", System.getProperty("java.home"))
        }
    }
}

class CargoKitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val plugin = findFlutterPlugin(project.rootProject)

        project.extensions.create("cargokit", CargoKitExtension::class.java)

        if (plugin == null) {
            println("Flutter plugin not found, CargoKit plugin will not be applied.")
            return
        }

        val flutterProject = plugin.findProject()
        val flutterAndroid = flutterProject.extensions.findByName("android")
            ?: throw GradleException("Android extension not found on Flutter project.")
        val androidComponents = project.extensions.findByName("androidComponents")
            ?: throw GradleException("Android Components extension not found on CargoKit project.")
        val selector = androidComponents.invokeMethod("selector")
            ?: throw GradleException("Android Components selector was not found.")
        val allVariants = selector.invokeMethod("all")
            ?: throw GradleException("Android Components all-variants selector was not found.")

        androidComponents.invokeMethod(
            "onVariants",
            allVariants,
            object : Action<Any> {
                override fun execute(variant: Any) {
                    configureVariant(project, flutterAndroid, variant)
                }
            },
        )
    }

    private fun configureVariant(
        project: Project,
        flutterAndroid: Any,
        variant: Any,
    ) {
        val buildType = variant.buildTypeName()
        val capitalizedBuildType = buildType.capitalized()
        val cargoOutputDir = project.layout.buildDirectory.dir("jniLibs/$buildType")
        val cargoBuildDir = project.layout.buildDirectory.dir("build")

        configureJniLibs(project, buildType, cargoOutputDir.get().asFile)

        var platforms = flutterTargetPlatforms(project)
        if (buildType == "debug") {
            val debugRustTargets = project.rootProject.findProperty("solunis.debugRustTargetPlatforms")
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: "android-arm64,android-x64"
            platforms = debugRustTargets
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val extension = project.extensions.getByType(CargoKitExtension::class.java)
        val libname = extension.libname
            ?: throw GradleException("Property 'libname' must be set on cargokit extension")
        val manifestDir = extension.manifestDir
            ?: throw GradleException("Property 'manifestDir' must be set on cargokit extension")
        val taskName = "cargokitCargoBuild${libname.capitalized()}$capitalizedBuildType"

        if (project.tasks.findByName(taskName) != null) {
            return
        }

        val ndkVersionValue = flutterAndroid.propertyValue("ndkVersion")?.toString()
            ?: throw GradleException("Please set 'android.ndkVersion' in 'app/build.gradle'.")
        val sdkDirectoryValue = flutterAndroid.propertyValue("sdkDirectory")?.toString()
            ?: project.findAndroidSdkDirectory()
            ?: throw GradleException("Android SDK directory was not found.")
        val defaultConfig = flutterAndroid.propertyValue("defaultConfig")
            ?: throw GradleException("Android defaultConfig was not found.")

        val taskProvider = project.tasks.register(taskName, CargoKitBuildTask::class.java) {
            buildMode.set(buildType)
            this.cargoBuildDir.set(cargoBuildDir.map { it.asFile.absolutePath })
            outputDir.set(cargoOutputDir)
            ndkVersion.set(ndkVersionValue)
            sdkDirectory.set(sdkDirectoryValue)
            minSdkVersion.set(defaultConfig.minSdkVersion())
            compileSdkVersion.set(flutterAndroid.compileSdkVersion())
            targetPlatforms.set(platforms)
            pluginFile.fileValue(file ?: throw GradleException("CargoKit plugin file was not found."))

            val manifestRoot = File(project.buildFile.parentFile, manifestDir)
            inputs.dir(File(manifestRoot, "src"))
            listOf("Cargo.toml", "Cargo.lock", "build.rs", "cargokit.yaml").forEach { fileName ->
                val inputFile = File(manifestRoot, fileName)
                if (inputFile.exists()) {
                    inputs.file(inputFile)
                }
            }
        }

        project.tasks.matching { it.name == "merge${capitalizedBuildType}NativeLibs" }
            .configureEach {
                dependsOn(taskProvider)
                outputs.upToDateWhen { false }
            }

        project.tasks.matching { it.name == "merge${capitalizedBuildType}JniLibFolders" }
            .configureEach {
                dependsOn(taskProvider)
                outputs.upToDateWhen { false }
            }
    }

    private fun findFlutterPlugin(rootProject: Project): Plugin<*>? =
        rootProject.childProjects.values.firstNotNullOfOrNull { project ->
            project.plugins.firstOrNull { plugin ->
                plugin.javaClass.name == "com.flutter.gradle.FlutterPlugin"
            } ?: findFlutterPlugin(project)
        }

    private fun Plugin<*>.findProject(): Project {
        javaClass.methods
            .firstOrNull { it.name == "getProject" && it.parameterCount == 0 }
            ?.let { method ->
                (method.invoke(this) as? Project)?.let { return it }
            }

        var current: Class<*>? = javaClass
        while (current != null) {
            current.declaredFields
                .firstOrNull { it.name == "project" }
                ?.let { field ->
                    field.isAccessible = true
                    (field.get(this) as? Project)?.let { return it }
                }
            current = current.superclass
        }

        throw GradleException("Flutter plugin project was not found.")
    }

    private fun configureJniLibs(project: Project, buildType: String, cargoOutputDir: File) {
        val android = project.extensions.findByName("android")
            ?: throw GradleException("Android extension not found on CargoKit project.")
        val sourceSets = android.propertyValue("sourceSets")
            ?: throw GradleException("Android sourceSets were not found.")
        val sourceSet = sourceSets.invokeMethod("maybeCreate", buildType)
        val jniLibs = sourceSet?.propertyValue("jniLibs")
            ?: throw GradleException("Android jniLibs source directory set was not found.")
        jniLibs.invokeMethod("srcDir", cargoOutputDir)
    }

    private fun flutterTargetPlatforms(project: Project): List<String> {
        val utilsClass = Class.forName("com.flutter.gradle.FlutterPluginUtils")
        val method = utilsClass.methods.firstOrNull {
            it.name == "getTargetPlatforms" && it.parameterTypes.contentEquals(arrayOf(Project::class.java))
        } ?: throw GradleException("FlutterPluginUtils.getTargetPlatforms(Project) was not found.")

        val target = if (Modifier.isStatic(method.modifiers)) {
            null
        } else {
            utilsClass.getField("INSTANCE").get(null)
        }
        val result = method.invoke(target, project)
        return when (result) {
            is Iterable<*> -> result.map { it.toString() }
            is Array<*> -> result.map { it.toString() }
            else -> result.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun Any.buildTypeName(): String {
        val buildType = propertyValue("buildType")
        return buildType?.propertyValue("name")?.toString()
            ?: buildType?.toString()
            ?: propertyValue("name")?.toString()
            ?: throw GradleException("Android variant build type was not found.")
    }

    private fun Any.compileSdkVersion(): Int =
        parseSdkVersion(propertyValue("compileSdk") ?: propertyValue("compileSdkVersion"))
            ?: throw GradleException("Android compileSdkVersion was not found.")

    private fun Any.minSdkVersion(): Int =
        parseSdkVersion(propertyValue("minSdk") ?: propertyValue("minSdkVersion"))
            ?: throw GradleException("Android minSdkVersion was not found.")

    private fun parseSdkVersion(value: Any?): Int? =
        when (value) {
            null -> null
            is Number -> value.toInt()
            is String -> value.substringAfter("android-", value).toIntOrNull()
            else -> value.propertyValue("apiLevel")?.let { parseSdkVersion(it) }
        }

    private fun Project.findAndroidSdkDirectory(): String? {
        val candidates =
            listOf(rootProject.file("local.properties"), file("../android/local.properties"))
        for (propertiesFile in candidates) {
            if (!propertiesFile.exists()) {
                continue
            }
            val properties = java.util.Properties()
            propertiesFile.inputStream().use { properties.load(it) }
            properties.getProperty("sdk.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
    }

    private fun Any.propertyValue(name: String): Any? {
        val suffix = name.capitalized()
        javaClass.methods
            .firstOrNull {
                it.parameterCount == 0 && (it.name == "get$suffix" || it.name == "is$suffix")
            }
            ?.let { return it.invoke(this) }

        var current: Class<*>? = javaClass
        while (current != null) {
            current.declaredFields
                .firstOrNull { it.name == name }
                ?.let { field ->
                    field.isAccessible = true
                    return field.get(this)
                }
            current = current.superclass
        }

        return null
    }

    private fun Any.invokeMethod(name: String, vararg args: Any): Any? {
        val method = javaClass.methods.firstOrNull {
            it.name == name &&
                it.parameterCount == args.size &&
                it.parameterTypes.zip(args).all { (parameterType, arg) ->
                    parameterType.isAssignableFrom(arg.javaClass)
                }
        } ?: throw GradleException("Method '$name' was not found on ${javaClass.name}.")
        return method.invoke(this, *args)
    }

    private fun String.capitalized(): String =
        replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(Locale.US)
            } else {
                it.toString()
            }
        }

    companion object {
        var file: File? = null
    }
}
