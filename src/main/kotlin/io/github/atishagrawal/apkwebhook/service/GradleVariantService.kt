package io.github.atishagrawal.apkwebhook.service

import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * Enumerates Android build variants. Two strategies in priority order:
 *
 *  1. Read each module's [GradleAndroidModel] — populated by AGP during sync and used by
 *     AS's own Build Variants panel. Works even when "Do not build Gradle task list during
 *     sync" is on (the modern AS default).
 *  2. Fall back to scanning cached `assemble<Variant>` task names from the external system
 *     project structure — works on plain IntelliJ projects, and on AS when task
 *     enumeration is enabled.
 *
 * Both paths prefer the `:app` module. Empty list ⇒ caller falls back to the static
 * settings list.
 */
class GradleVariantService(private val project: Project) {

    fun listVariants(): List<String> {
        listVariantsFromAndroidModel()?.let { return it }
        return listVariantsFromTaskScan()
    }

    private fun listVariantsFromAndroidModel(): List<String>? {
        val candidates = ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val model = try {
                GradleAndroidModel.get(module)
            } catch (t: Throwable) {
                LOG.warn("APK-Webhook: GradleAndroidModel.get failed for module ${module.name}", t)
                null
            }
            model?.let { module to it }
        }
        if (candidates.isEmpty()) {
            LOG.info("APK-Webhook variants: no GradleAndroidModel on any module — falling back to task scan")
            return null
        }

        // IntelliJ module name for Gradle :app is typically "<project>.app"; bare "app" also accepted.
        val (chosenModule, chosenModel) = candidates.firstOrNull { (m, _) ->
            m.name.endsWith(".app") || m.name == "app"
        } ?: candidates.first()

        // Cartesian product of declared flavors (by dimension, in declaration order) × declared
        // build types. Reads declared *structure*, which AGP populates on every sync regardless
        // of single-variant-sync — unlike `model.variants`, which only carries the loaded variant.
        val buildTypes = chosenModel.buildTypeNames.toList()
        val flavorsByDim = chosenModel.productFlavorNamesByFlavorDimension
        val flavorCombos: List<String> = if (flavorsByDim.isEmpty()) {
            listOf("")
        } else {
            flavorsByDim.values.fold(listOf("")) { acc, dimFlavors ->
                acc.flatMap { existing ->
                    dimFlavors.map { f ->
                        if (existing.isEmpty()) f
                        else existing + f.replaceFirstChar(Char::uppercase)
                    }
                }
            }
        }
        val declared = flavorCombos.flatMap { combo ->
            buildTypes.map { bt ->
                if (combo.isEmpty()) bt
                else combo + bt.replaceFirstChar(Char::uppercase)
            }
        }.distinct().sorted()

        LOG.info(
            "APK-Webhook variants from GradleAndroidModel: module=${chosenModule.name} " +
                "loaded=${chosenModel.variants.map { it.name }} " +
                "declared-buildTypes=$buildTypes declared-flavorsByDim=$flavorsByDim " +
                "declared-variants=$declared"
        )
        return declared.takeIf { it.isNotEmpty() }
    }

    private fun listVariantsFromTaskScan(): List<String> {
        return try {
            val basePath = project.basePath ?: return emptyList()
            val info = ProjectDataManager.getInstance()
                .getExternalProjectData(project, GradleConstants.SYSTEM_ID, basePath)
                ?: run {
                    LOG.warn("APK-Webhook variants: no cached gradle data at $basePath — has the project been Gradle-synced?")
                    return emptyList()
                }
            val structure = info.externalProjectStructure ?: return emptyList()

            val allTaskNodes = ExternalSystemApiUtil.findAllRecursively(structure, ProjectKeys.TASK)
            val appTaskNames = allTaskNodes
                .filter {
                    val path = it.data.linkedExternalProjectPath
                    path.endsWith("/app") || path.endsWith("\\app") || path.endsWith(":app")
                }
                .map { it.data.name }
            val allTaskNames = allTaskNodes.map { it.data.name }

            val scanList = if (appTaskNames.isNotEmpty()) appTaskNames else allTaskNames

            val variants = scanList
                .mapNotNull { name -> ASSEMBLE_PATTERN.find(name)?.groupValues?.get(1) }
                .filter { it != "Debug" && it != "Release" }
                .map { it.replaceFirstChar(Char::lowercase) }
                .distinct()
                .sorted()

            LOG.info("APK-Webhook variant task-scan: total-tasks=${allTaskNodes.size} app-tasks=${appTaskNames.size} variants=$variants")
            if (variants.isEmpty()) {
                val sample = allTaskNodes
                    .map { "${it.data.name}@${it.data.linkedExternalProjectPath.substringAfterLast('/')}" }
                    .filter { it.startsWith("assemble") }
                    .take(12)
                LOG.warn("APK-Webhook variants: task-scan enumeration empty — sample assemble tasks seen: $sample")
            }
            variants
        } catch (t: Throwable) {
            LOG.warn("APK-Webhook variant task-scan failed", t)
            emptyList()
        }
    }

    /** Convert variant display name ("devDebug") → full Gradle task path. */
    fun variantToTask(variant: String): String =
        ":app:assemble${variant.replaceFirstChar(Char::uppercase)}"

    companion object {
        private val LOG = Logger.getInstance(GradleVariantService::class.java)
        private val ASSEMBLE_PATTERN = Regex("^:?(?:[^:]+:)*assemble([A-Z][A-Za-z0-9]+(?:Debug|Release))$")
    }
}
