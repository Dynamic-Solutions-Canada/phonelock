import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Renders one variant's AboutLibraries export as plain text (HYG-3): every library, sorted by module, then each
 * licence's full text once. Adapted from persistent's third-party-notices script.
 *
 * Fails the build when a library names no licence, or when [runtimeModules] (the variant's runtime classpath)
 * holds a module the export doesn't list, so the file can't silently drift from what the APK ships.
 */
abstract class RenderThirdPartyNotices : DefaultTask() {
    /** The variant's aboutlibraries.json (from exportLibraryDefinitions<Variant>). */
    @get:InputFiles
    abstract val definitions: ConfigurableFileCollection

    /** "group:module" of every external module on the variant's runtime classpath. */
    @get:Input
    abstract val runtimeModules: SetProperty<String>

    /** Where third_party_notices.txt is written; added to the variant's assets. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun render() {
        val file = definitions.asFileTree.matching { include("**/*.json") }.singleFile

        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(file) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val libraries = (json["libraries"] as? List<Map<String, Any?>>).orEmpty().sortedBy { it["uniqueId"] as String }

        @Suppress("UNCHECKED_CAST")
        val licenses = (json["licenses"] as? Map<String, Map<String, Any?>>).orEmpty()

        checkComplete(libraries)
        val out = StringBuilder()
        out.append("THIRD-PARTY SOFTWARE NOTICES: PhoneLock parent app\n\n")
        // One line per paragraph: the app wraps it to the screen, so fixed line breaks would leave ragged lines.
        out.append("This app includes the third-party libraries listed below. Each remains under its own licence, ")
        out.append("whose full text (or, for terms AboutLibraries has no text for, its address) follows the list.\n\n")
        out.append("Total libraries: ${libraries.size}\n\n")
        for (library in libraries) {
            out.append("${library["uniqueId"]}:${library["artifactVersion"]}\n")
            val names = licenseKeys(library).map { licenses[it]?.get("spdxId") ?: licenses[it]?.get("name") ?: it }
            out.append("License: ${names.joinToString(", ")}\n")
            library["website"]?.let { out.append("Website: $it\n") }
            out.append("\n")
        }
        for (key in licenses.keys.sorted()) {
            val license = licenses.getValue(key)
            out.append("----- ${license["spdxId"] ?: license["name"]} -----\n")
            out.append("${plainText((license["content"] ?: license["url"] ?: "") as String)}\n\n")
        }

        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("third_party_notices.txt").writeText(out.toString())
    }

    /** Fails unless every library names a licence and every runtime module is listed. */
    private fun checkComplete(libraries: List<Map<String, Any?>>) {
        val unlicensed = libraries.filter { licenseKeys(it).isEmpty() }
        if (unlicensed.isNotEmpty()) {
            throw GradleException("No licence for: ${unlicensed.joinToString(", ") { it["uniqueId"] as String }}")
        }
        val listed = libraries.map { it["uniqueId"] as String }.toSet()
        // Kotlin multiplatform libraries resolve to a platform artifact (-jvm, -android) that AboutLibraries
        // lists under the library's root module.
        val missing = runtimeModules.get()
            .filterNot { it in listed || it.replace(Regex("-(jvm|android)$"), "") in listed }
            .sorted()
        if (missing.isNotEmpty()) {
            throw GradleException("Runtime modules missing from the notices: ${missing.joinToString(", ")}")
        }
    }

    private fun licenseKeys(library: Map<String, Any?>): List<String> {
        @Suppress("UNCHECKED_CAST")
        return (library["licenses"] as? Collection<String>).orEmpty().toList()
    }

    private companion object {
        /**
         * Licence text as a person reads it: SPDX template fields such as
         * `<<var;name=copyright;original=<year> <owner>;match=.+>>` become their original text.
         */
        fun plainText(text: String): String = text
            .replace(Regex("<<var;[^>]*?original=(.*?);match=.*?>>"), "$1")
            .replace(Regex("<</?optional[^>]*>>"), "")
            .trim()
    }
}
