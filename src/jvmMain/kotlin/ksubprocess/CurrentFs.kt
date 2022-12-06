package ksubprocess

import okio.FileSystem

internal actual val CurrentFs: FileSystem = FileSystem.SYSTEM
