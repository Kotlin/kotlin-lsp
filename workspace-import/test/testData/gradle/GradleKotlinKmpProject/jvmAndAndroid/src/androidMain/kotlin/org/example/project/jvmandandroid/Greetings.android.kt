package org.example.project.jvmandandroid

import android.os.Build
import org.example.project.sayHello

actual fun platformName(): String = "Android ${Build.VERSION.SDK_INT}"

fun androidGreeting(): String = sayHello(platformName())
