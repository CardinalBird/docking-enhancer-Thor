package com.controlswitch.domain.repository

interface DockStateRepository {
    fun isDockActive(): Boolean
}
