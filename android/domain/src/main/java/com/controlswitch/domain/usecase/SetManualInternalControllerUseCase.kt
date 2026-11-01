package com.controlswitch.domain.usecase

import com.controlswitch.domain.repository.MirrorSettingsRepository

class SetManualInternalControllerUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(guid: String?) {
        mirrorSettingsRepository.setManualInternalController(guid)
    }
}
