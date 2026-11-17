package com.example.knowhub.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformInput(): PlatformInput = remember { NoopPlatformInput() }