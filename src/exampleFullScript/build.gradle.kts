import at.phatbl.shellexec.ShellExec

group = "net.something.someone"
version = "1.0.0"

plugins {
    id("at.phatbl.shellexec") version "1.5.2"
    id("com.dorongold.task-tree") version "1.5"
}
dependencies {}

val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"
private fun packageJsonExists(project: Project): Boolean = File(project.projectDir, "package.json").exists()
private fun isLambda(project: Project): Boolean = project.projectDir.toString().contains("src/lambda")
private fun isReact(project: Project): Boolean = project.projectDir.toString().contains("react")
private val reactEnvironment = System.getProperty("REACT_APP_ENVIRONMENT") ?: "local"


println("""
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
       )

/** Everything in subprojects applies only to the sub-projects - not the global project */
subprojects {
    tasks {
        tasks.register<ShellExec>("install") {
            description = "Do the 'install' operation"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.installReactCommand ?: "npm install"
                else                  -> ext.installLambdaCommand ?: "yarn"
            }
            onlyIf { packageJsonExists(this.project) } // using this.project gets the current subproject - not the global project
        }

        tasks.register<ShellExec>("installProduction") {
            description = "Package the production dependencies.  Run it like this: 'gradle installProduction -DREACT_APP_ENVIRONMENT=dev"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.installReactProductionCommand ?: "npm run build"
                else                  -> ext.installLambdaProductionCommand ?: "yarn --production"
            }
            onlyIf { packageJsonExists(this.project) }
            doLast { println("The env used is: $reactEnvironment") }
        }

        tasks.register<ShellExec>("test") {
            description = "Run the tests for both React and Lambdas"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.testReactCommand ?: "npm run test-coverage"
                else                  -> ext.testLambdaCommand ?: "yarn test --testTimeout=10000"
            }
            onlyIf { packageJsonExists(this.project) }
        }

        tasks.register<ShellExec>("lint") {
            description = "Lint the code"
            group = "React/Lambdas"
            command = when {
                isReact(this.project) -> ext.lintReactCommand ?: "npm run lint-ts"
                else                  -> ext.lintLambdaCommand ?: "yarn run lint"
            }
            onlyIf { packageJsonExists(this.project) && (file(".eslintrc").exists() || file(".eslintrc.js").exists()) }
        }

        tasks.register<ShellExec>("tsc") {
            description = "Run tsc on any project that has a tsconfig.json "
            group = "React/Lambdas"
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
            group = "React/Lambdas"
            command = ext.runReactCommand ?: "npm run start-$reactEnvironment"
            onlyIf { isReact(this.project) }
        }

        // React only
        tasks.register<ShellExec>("buildCss") {
            description = "Build the CSS"
            group = "React/Lambdas"
            command = ext.buildCssCommand ?: "npm run build-css"
            onlyIf { isReact(this.project) }
        }

        tasks.register<Exec>("cleanUpForDeploy") {
            description = "Remove any files in the lambda dirs not needed for deployment - this WILL delete source-controlled files, so run locally with care!"
            group = "React/Lambdas"
            isIgnoreExitValue = true
            commandLine = ("ls -l serverless.yaml event.json package-lock.json package.json serverless.yaml jest-config.js " +
                           "test *.txt *.ts yarn.lock jest.config.js").split(" ")
            onlyIf { isLambda(this.project) && packageJsonExists(this.project) }
        }

        // below are housekeeping tasks
        tasks.register<ShellExec>("createModulesList") {
            description = "create a list of the contents for each module and export that into a file - very useful for fast comparisons of different builds"
            group = "React/Lambdas Housekeeping"
            command = "ls -w1 node_modules > node_modules.out"
            onlyIf { packageJsonExists(this.project) }
        }

        tasks.register<Delete>("cleanNodeModules") {
            description = "Clean the node modules dirs"
            group = "React/Lambdas Housekeeping"
            delete = setOf("node_modules", "node_modules.out")
        }
    }
}

tasks.register<ShellExec>("mergeTestResults") {
    description = "Merge all the test results"
    group = "React/Lambdas"
    command = """
            mkdir -p ../lcov
            """ + createMergeCommands(project)
}


tasks.register("regenerateSettingsDotGradle") {

    description = "Utility class to regenerate the tools/settings.gradle.kts file - if you create more lambdas, run this "
    group = "React/Lambdas Housekeeping"
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
                    corporatePluginRepo()
                }
            } 
        """.trimIndent()
                            )

        // write it to disk
        val fileContents = stringBuilder.toString()
        File("settings.gradle.kts").writeText(fileContents)

    }
}

tasks.register<ShellExec>("outputToolVersions") {
    description = "Show the tool versions"
    group = "React/Lambdas Housekeeping"

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

/**
 * Some of the tools don't give you an option to ignore stuff that's not there and fail, so we have to have different
 * commands for whether or not React is there.  It's assumed that there is at least one lambda in the project; else, we'll
 * have to have a third condition of react w/no lambdas.
 */
private fun createMergeCommands(project: Project): String {
    val hasReact = File("${project.projectDir}/..")
            .listFiles()
            .any { it.name == "react" }

    println("hasReact files is $hasReact")
    return """
                # Next, run the command to merge all the Junit xml files into one toplevel xml file - https://www.npmjs.com/package/junit-merge
                junit-merge  ../src/lambda/*/coverage/jest/junit.xml -o ../allLambdasMergedTests.xml || true
                """
    + if (hasReact)
        "junit-merge  ../react/coverage/jest/junit.xml  -o ../allReactMergedTests.xml || true"
    + """
                junit - merge../ allLambdasMergedTests . xml .. / allReactMergedTests.xml - o../ allMergedTests . xml || true
        
                #  Finally, create an HTML report from THAT via junit2html - https: //github.com/inorton/junit2html
                junit2html../ allMergedTests . xml .. / allMergedTests.html
                
                # Next, run the command to merge all the Junit xml files into one toplevel xml file - https: //www.npmjs.com/package/junit-merge
                junit-merge ../src/lambda/*/coverage/jest/junit.xml -o ../allLambdasMergedTests.xml || true
                junit-merge  ../allLambdasMergedTests.xml -o ../allMergedTests.xml || true
            
                 #  Finally, create an HTML report from THAT via junit2html - https://github.com/inorton/junit2html
                 junit2html ../allMergedTests.xml ../allMergedTests.html
""".trimIndent()
}
}
