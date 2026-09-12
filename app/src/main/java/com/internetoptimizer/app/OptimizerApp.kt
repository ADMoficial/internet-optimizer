package com.internetoptimizer.app

import android.app.Application
import android.content.Context

/**
 * Application class — initializes singletons and sets up logging.
 *
 * In a production app, this is where you'd initialize:
 * - Crash reporting (e.g. Firebase Crashlytics)
 * - Dependency injection (e.g. Hilt)
 * - Native library preloading
 *
 * For this MVP, we keep it minimal.
 */
class OptimizerApp : Application() {

    init {
        // Set the context for any static helpers that need it
        INSTANCE = this
    }

    companion object {
        @Volatile
        private var INSTANCE: OptimizerApp? = null

        fun getInstance(): OptimizerApp = INSTANCE!!

        fun getContext(): Context = INSTANCE!!.applicationContext
    }
}
