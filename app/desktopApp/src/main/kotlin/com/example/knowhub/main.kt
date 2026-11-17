package com.example.knowhub

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.knowhub.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KnowHub",
    ) {
        App()
    }
}
