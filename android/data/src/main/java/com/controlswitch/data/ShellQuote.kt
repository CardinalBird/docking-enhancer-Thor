package com.controlswitch.data

/** Single-quotes a string for safe interpolation into a shell command. */
internal fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"
