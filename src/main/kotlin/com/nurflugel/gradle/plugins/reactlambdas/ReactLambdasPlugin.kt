package com.nurflugel.gradle.plugins.reactlambdas

import at.phatbl.shellexec.ShellExec
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.internal.jvm.Jvm
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.register
import java.io.File


/** This class adds the tasks to get certs for affected services and install them into the current JVM */
@Suppress("unused", "ConvertCallChainIntoSequence", "RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class ReactLambdasPlugin : Plugin<Project> {

    private fun packageJsonExists(project: Project): Boolean = File(project.projectDir, "package.json").exists()
    private fun isLambda(project: Project): Boolean = project.projectDir.toString().contains("src/lambda")
    private fun isReact(project: Project): Boolean = project.projectDir.toString().contains("react")
    private val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"

    override fun apply(project: Project) {
        // register plugin extension
        val ext = project.extensions.create("reactLambdas", ReactLambdasPluginExtension::class, project)
        applyPlugins(project)
        registerTasks(project, ext)
    }

    private fun printBanner(project: Project, ext: ReactLambdasPluginExtension) {
        val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"
        val prefix = """
            ========================================================================================================
            Welcome to Gradle version:          ${project.gradle.gradleVersion}
            Java version:                       ${Jvm.current()}
            Java home:                          ${Jvm.current().javaHome}
            Gradle user directory is set to:    ${project.gradle.gradleUserHomeDir}
            Project directory:                  ${project.projectDir}
            Running build script:               ${project.buildFile}
            ReactEnvironment:                   $reactEnvironment
            Subprojects:                        ${project.subprojects.map { it.name }}
            """.trimIndent()
        println(prefix)
    }

    /** Apply the specified plugins to the project */
    private fun applyPlugins(project: Project) = with(project) {
        pluginManager.apply("idea")
        pluginManager.apply("project-report")
        pluginManager.apply("com.github.ben-manes.versions")
        pluginManager.apply("com.dorongold.task-tree")
        pluginManager.apply("at.phatbl.shellexec")
    }

    /** Here's where we specify the tasks for the project and/or subprojects */
    private fun registerTasks(project: Project, ext: ReactLambdasPluginExtension) = with(project) {

        /** Everything in subprojects applies only to the sub-projects - not the global project */
        subprojects {
            tasks {
                tasks.register<ShellExec>("install") {
                    description = "Do the 'install' operation"
                    group = "Monorepo"
                    // this looks pretty fancy - but this is how we can have overridable commands per-project.  You could override it by having
                    // this bit in your project's build.gradle:
                    // ccReactLambdas {
                    //    installLambdaCommand = "npm install --verbose"
                    // }
                    command = when {
                        isReact(this.project) -> ext.installReactCommand ?: "npm install"
                        else                  -> ext.installLambdaCommand ?: "yarn"
                    }
                    onlyIf { packageJsonExists(this.project) } // using this.project gets the current subproject - not the global project

                }

                tasks.register<ShellExec>("installProduction") {
                    description = "Package the production dependencies.  Run it like this: 'gradle installProduction -DREACT_APP_ENVIRONMENT=dev"
                    group = "Monorepo"
                    command = when {
                        isReact(this.project) -> ext.installReactProductionCommand ?: "npm run build"
                        else                  -> ext.installLambdaProductionCommand ?: "yarn --production"
                    }
                    onlyIf { packageJsonExists(this.project) }
                    doLast { println("The env used is: $reactEnvironment") }
                }

                tasks.register<ShellExec>("test") {
                    description = "Run the tests for both React and Lambdas"
                    group = "Monorepo"
                    command = when {  // run the test AND copy the resulting coverage files to 
                        isReact(this.project) -> ext.testReactCommand ?: "npm run test-coverage"
                        else                  -> ext.testLambdaCommand ?: "yarn test --testTimeout=10000"
                    }
                    onlyIf { packageJsonExists(this.project) }
                }
                tasks.register<ShellExec>("copyCoverage") {
                    description = "Copy the coverage files to a central area for merging "
                    group = "Monorepo"
                    val dirName = this.project.path.substringAfterLast("/") // get the dir name
                    command = when {  // run the test AND copy the resulting coverage files to 
                        isReact(this.project) -> ext.testReactCommand ?: "cp coverage/coverage-final.json ../build/coverage/react-coverage-final.json"
                        else                  -> ext.testLambdaCommand ?: "cp coverage/coverage-final.json ../../../build/coverage/$dirName-coverage-final.json"
                    }
                    onlyIf { packageJsonExists(this.project) }
                }

                tasks.register<ShellExec>("lint") {
                    description = "Lint the code"
                    group = "Monorepo"
                    command = when {
                        isReact(this.project) -> ext.lintReactCommand ?: "npm run lint-ts"
                        else                  -> ext.lintLambdaCommand ?: "yarn run lint"
                    }
                    onlyIf { packageJsonExists(this.project) && (file(".eslintrc").exists() || file(".eslintrc.js").exists()) }
                }

                tasks.register<ShellExec>("tsc") {
                    description = "Run tsc on any project that has a tsconfig.json "
                    group = "Monorepo"
                    command = "yarn run tsc"
                    onlyIf {
                        val lambda = isLambda(this.project)
                        val exists = file("tsconfig.json").exists()
                        println("tsconfig.json exists = $exists")
                        lambda && exists
                    }
                }

                // React only
                tasks.register<ShellExec>("runReact") {
                    description = "Run the React app like this: 'gradle runReact -DREACT_APP_ENVIRONMENT=dev'.  If -DREACT_APP_ENVIRONMENT is omitted, local is used"
                    group = "Monorepo"
                    command = ext.runReactCommand ?: "npm run start-$reactEnvironment"
                    onlyIf { isReact(this.project) }
                }

                // React only
                tasks.register<ShellExec>("buildCss") {
                    description = "Build the CSS"
                    group = "Monorepo"
                    command = ext.buildCssCommand ?: "npm run build-css"
                    onlyIf { isReact(this.project) }
                }

                tasks.register<Exec>("cleanUpForDeploy") {
                    description = "Remove any files in the lambda dirs not needed for deployment - this WILL delete source-controlled files, so run locally with care!"
                    group = "Monorepo"
                    isIgnoreExitValue = true
                    commandLine = "ls -l serverless.yaml event.json package-lock.json package.json serverless.yaml jest-config.js test *.txt *.ts yarn.lock jest.config.js"
                            .split(" ")
                    onlyIf { isLambda(this.project) && packageJsonExists(this.project) }
                }

                // below are housekeeping tasks
                tasks.register<ShellExec>("createModulesList") {
                    description = "create a list of the contents for each module and export that into a file - very useful for fast comparisons of different builds"
                    group = "Monorepo Housekeeping"
                    command = "ls -w1 node_modules > node_modules.out"
                    onlyIf { packageJsonExists(this.project) }
                }

                tasks.register<Delete>("cleanNodeModules") {
                    description = "Clean the node modules dirs"
                    group = "Monorepo Housekeeping"
                    delete = setOf("node_modules", "node_modules.out")
                }
            }
        }
        tasks.register<ShellExec>("mergeTestResults") {
            description = "Merge all the test coverage results"
            group = "Monorepo"
            command = createMergeCommands(project)
        }

        tasks.register<ShellExec>("cleanCoverage") {
            description = "Clean out the coverage folders between tests"
            group = "Monorepo Housekeeping"
            command = "find . -name coverage | grep -v modules | xargs rm -rf"
        }
        tasks.register("regenerateSettingsDotGradle") {

            description = "Utility class to regenerate the tools/settings.gradle.kts file - if you create more lambdas, run this "
            group = "Monorepo Housekeeping"
            doLast {
                // create the file header
                val stringBuilder = StringBuilder("""rootProject.name = "${rootProject.name}"
"""
                                                 )
                // deal with the react contents if they exist
                val reactDir = File("react")
                println("React dir exists: ${reactDir.exists()}")
                if (reactDir.exists()) {
                    stringBuilder.append("""

include("react")
project(":react").projectDir = File("../react")
"""
                                        )
                }

                // Now create a mapping entry for each lambda
                val lambdasDir = File("src/lambda")
                if (lambdasDir.exists()) {
                    lambdasDir.listFiles()
                            .filter {
                                val hasPackageJson = it.listFiles()?.map { file -> file.name }?.any { name -> name == "package.json" }
                                                     ?: false
                                hasPackageJson
                            }
                            .map { it.name }
                            .sorted()
                            .map {
                                """
include("$it")
project(":$it").projectDir = File("../src/lambda/$it")
"""
                            }
                            .forEach { stringBuilder.append(it) }

                }

                // finally, add the plugin repo settings
                stringBuilder.append("""

            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenLocal()
                }
            } 
        """.trimIndent()
                                    )

                // write it to disk
                val fileContents = stringBuilder.toString()
                File("tools/settings.gradle.kts").writeText(fileContents)

            }
        }


        tasks.register<ShellExec>("outputToolVersions") {
            description = "Show the tool versions"
            group = "Monorepo Housekeeping"

            command = """
                echo "Git version " $(git --version)
                echo "NPM version " $(npm --version)
                echo "Node version" $(node --version)
                echo "Yarn version" $(yarn --version)
                echo "Tsc version " $(tsc --version)
                echo "pip3 version " $(pip3 -V)
                echo "junit-merge version " $(junit-merge -V)
                ${ext.outputToolVersionsExtra ?: ""}
                """ + createMergeCommands(project)
        }

        afterEvaluate {
            // we print the banner here as it needs to be done AFTER evaluation so the vars can be read in
            printBanner(project, ext)
        }
    }

    /** Some of the tools don't give you an option to ignore stuff that's not there and fail, so we have to have different
     * commands for whether or not React is there.  It's assumed that there is at least one lambda in the project; else, we'll
     * have to have a third condition of react w/no lambdas.
     */
    private fun createMergeCommands(project: Project): String {
        val hasReact = File("${project.projectDir}/..")
                .listFiles()
                .any { it.name == "react" }
        val createBuildDirClause = """
             mpdir -p .nyc_output
             mkdir -p build/coverage-report
             mkdir -p build/coverage
             mkdir -p build/testCoverageFiles
        """.trimIndent()
        val mergeReactClause = if (hasReact) "junit-merge  react/coverage/jest/junit.xml  -o allReactMergedTests.xml || true" else ""
        val coverageReactClause = if (hasReact) "cp react/coverage/coverage-final.json build/coverage/coverage-react.json || true" else ""
        val mergeLambdasClause = """
                    # Next, run the command to merge all the Junit xml files into one toplevel xml file - https://www.npmjs.com/package/junit-merge
                    junit-merge  src/lambda/*/coverage/jest/junit.xml -o allLambdasMergedTests.xml || true
                    """.trimIndent()

        val mergingMergedClause = when {
            hasReact -> "junit-merge build/allLambdasMergedTests.xml build/allReactMergedTests.xml -o build/allMergedTests.xml || true"
            else     -> "junit-merge build/allLambdasMergedTests.xml -o build/allMergedTests.xml || true"
        }

        val assemblyClause = """
                    #  Finally, create an HTML report from THAT via junit2html - https: //github.com/inorton/junit2html
                    junit2html build/allMergedTests.xml build/allMergedTests.html
               
                    # Next, run the command to merge all the Junit xml files into one toplevel xml file - https: //www.npmjs.com/package/junit-merge
                    junit-merge src/lambda/*/coverage/jest/junit.xml -o build/allLambdasMergedTests.xml || true
                    junit-merge  build/allLambdasMergedTests.xml -o build/allMergedTests.xml || true
                
                    #  Finally, create an HTML report from THAT via junit2html - https://github.com/inorton/junit2html
                    junit2html build/allMergedTests.xml build/allMergedTests.html
                    
                    # Test coverage summary is nasty... we have to "roll up" all the sub projects
                    
                    # First, merge all the coverage files.  I tried to use a custom dir, but nyc didn't like that, so we use the default:
                    nyc merge build/coverage .nyc_output/merged-coverage
                    
                    # Create the HTML report from the merged files
                    nyc report --report-dir build/coverage-report --reporter=html
                    
                    # However, we need a SUMMARY of coverage for our pipeline requirements, not JSON - so we have to convert
                    nyc report --reporter json-summary -t build/coverage --report-dir build/coverage-summary
                      
    """.trimIndent()
        return createBuildDirClause + mergeReactClause + mergeLambdasClause + mergingMergedClause + assemblyClause
    }
}




