package com.appforge.shell

import android.content.Context

/**
 * Implement this interface in your own Kotlin or Java file (uploaded in the builder).
 * The class needs a public no-argument constructor (or be a Kotlin object).
 * call() may run on a background thread. Use (context as Activity).runOnUiThread for UI work.
 * Return a JSON string. The website reads it with AppBridge.callPlugin(name, method, args).
 */
interface AppPlugin {
    val name: String
    fun call(context: Context, method: String, argsJson: String): String
}
