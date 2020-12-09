package com.nurflugel.gradle.plugins.reactlambdas

import org.gradle.api.Project

open class ReactLambdasPluginExtension(private val project: Project) {
    var installReactCommand: String? = null
    var installLambdaCommand: String? = null

    var installReactProductionCommand: String? = null
    var installLambdaProductionCommand: String? = null

    var testReactCommand: String? = null
    var testLambdaCommand: String? = null

    var lintReactCommand: String? = null
    var lintLambdaCommand: String? = null

    var runReactCommand: String? = null
    var buildCssCommand: String? = null
    var outputToolVersionsExtra: String? = null


}
