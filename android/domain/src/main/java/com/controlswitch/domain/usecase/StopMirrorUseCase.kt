package com.controlswitch.domain.usecase

import com.controlswitch.domain.repository.MirrorProcessRepository
import com.controlswitch.domain.repository.MirrorSettingsRepository

class StopMirrorUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke() {
        try {
            mirrorProcessRepository.stop()
        } finally {
            mirrorProcessRepository.clearProcessFiles()
            mirrorSettingsRepository.setExpectedRunning(false)
        }
    }
}
