package org.openrndr.dokgen

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import java.io.File
import javax.inject.Inject

const val PLUGIN_NAME = "dokgen"

class DokGenException(message: String) : Exception("$PLUGIN_NAME exception: $message")


// this class provides the configuration dsl
// the inner classes represent configurable data
// the methods represent closures in the dsl through which configuration can be set
open class DokGenPluginExtension @Inject constructor(objectFactory: ObjectFactory) {
    open class ExamplesConf {
        var webRootUrl: String? = null
    }

    open class RunnerConf {
        var jvmArgs = mutableListOf<String>()
    }

    var examplesConf: ExamplesConf? = objectFactory.newInstance(ExamplesConf::class.java)
    var runnerConf: RunnerConf? = objectFactory.newInstance(RunnerConf::class.java)


    fun runner(action: Action<RunnerConf>) {
        runnerConf?.let {
            action.execute(it)
        }
    }

    fun examples(action: Action<ExamplesConf>) {
        examplesConf?.let {
            action.execute(it)
        }
    }

}

open class ProcessSourcesTask @Inject constructor(val examplesConf: DokGenPluginExtension.ExamplesConf?) : DefaultTask() {

    init {
        group = PLUGIN_NAME
        description = "processes into markdown and examples"
    }

    @InputDirectory
    var sourcesDir: File = File(project.projectDir, "src/main/kotlin/docs")

    @OutputDirectory
    var mdOutputDir: File = File(project.buildDir, "$PLUGIN_NAME/md")

    @OutputDirectory
    var examplesOutputDir: File = File(project.projectDir, "src/main/kotlin/examples")


    @TaskAction
    fun run(inputs: IncrementalTaskInputs) {
        val toProcess = mutableListOf<File>()
        inputs.outOfDate {
            toProcess.add(it.file)
        }
        DokGen.processSources(
            toProcess,
            sourcesDir,
            mdOutputDir,
            examplesOutputDir,
            webRootUrl = examplesConf?.webRootUrl
        )
    }
}

open class RunExamplesTask @Inject constructor(
    private val runnerConf: DokGenPluginExtension.RunnerConf?
) : DefaultTask() {
    init {
        group = PLUGIN_NAME
        description = "run the exported examples programs"
    }

    @InputDirectory
    var examplesDirectory: File = File(project.projectDir, "src/main/kotlin/examples")

    @TaskAction
    fun run(inputs: IncrementalTaskInputs) {
        val toRun = mutableListOf<File>()
        inputs.outOfDate {
            toRun.add(it.file)
        }


        val sourceSetContainer = project.property("sourceSets") as SourceSetContainer
        val ss = sourceSetContainer.getByName("main")

        val execClasses = DokGen.getExampleClasses(toRun, File(project.projectDir, "src/main/kotlin/examples"))

        execClasses.forEach { klass ->
            try {
                project.javaexec { spec ->
                    spec.classpath = ss.runtimeClasspath
                    runnerConf?.let {
                        spec.jvmArgs = it.jvmArgs
                    }
                    spec.main = klass
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    }
}

open class DocsifyTask @Inject constructor() : DefaultTask() {
    init {
        group = "dokgen"
        description = "docsify"
    }

    val docsifySources = javaClass.classLoader.getResource("docsify")
    val dokgenBuildDir = File(project.buildDir, PLUGIN_NAME)
    val dokgenMdDir = File(dokgenBuildDir, "md")
    val docsifyBuildDir = File(project.buildDir, "$PLUGIN_NAME/docsify")
    var docsifyDocsDir: File = File(docsifyBuildDir, "docs")
    var mediaInputDirectory: File = File(project.projectDir, "media")
    var mediaOutputDirectory: File = File(docsifyDocsDir, "media")


    @TaskAction
    fun run() {
        val jar = project.zipTree(docsifySources.path.split("!")[0])
        project.copy { spec ->
            spec.from(jar)
            spec.into(dokgenBuildDir)
            spec.include("docsify/**/*")
        }

        project.copy { spec ->
            spec.from(mediaInputDirectory)
            spec.into(mediaOutputDirectory)
        }

        project.copy { spec ->
            spec.from(dokgenMdDir)
            spec.into(docsifyDocsDir)
        }
    }

}

open class ServeDocsTask @Inject constructor() : DefaultTask() {
    val docsifyBuildDir = File(project.buildDir, "$PLUGIN_NAME/docsify")
    init {
        group = "dokgen"
        description = "runs the serves docs"
    }
    @TaskAction
    fun run() {
        println(docsifyBuildDir)
        println(docsifyBuildDir.exists())
        project.exec { exec ->
            exec.workingDir = docsifyBuildDir
            println(exec.workingDir)
            println(exec.workingDir.exists())
            exec.commandLine("docker-compose")
            exec.args = listOf("up")
        }
    }
}


class GradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val conf = project.extensions.create(PLUGIN_NAME,
            DokGenPluginExtension::class.java,
            project.objects
        )

//        val sourceSets = project.property("sourceSets") as SourceSetContainer
//        sourceSets.create("doks") { conf ->
//            conf.allSource.srcDir("${project.projectDir}/doks")
//        }

        project.afterEvaluate { loaded ->

            val dokGenTask = project.tasks.create(PLUGIN_NAME)
            dokGenTask.group = PLUGIN_NAME
            dokGenTask.description = "do the work"

            val processSources = project.tasks.create("processSources", ProcessSourcesTask::class.java, conf.examplesConf)
            val runExamples = project.tasks.create("runExamples", RunExamplesTask::class.java, conf.runnerConf)

            runExamples.dependsOn(processSources.path)
            runExamples.dependsOn(project.tasks.getByPath("compileKotlin"))

            dokGenTask.dependsOn(processSources.path)
            dokGenTask.dependsOn(runExamples.path)

            val docsifyTask = project.tasks.create("docsify", DocsifyTask::class.java)
            dokGenTask.finalizedBy(docsifyTask)

            docsifyTask.dependsOn(dokGenTask)


            val serveDocsTask = project.tasks.create("serveDocs", ServeDocsTask::class.java)
            serveDocsTask.dependsOn(docsifyTask)
        }
    }
}