package com.controlswitch.domain.usecase

import com.controlswitch.domain.repository.MirrorSettingsRepository

class SetAutoMirrorEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) {
        mirrorSettingsRepository.setAutoMirrorEnabled(enabled)
    }
}
