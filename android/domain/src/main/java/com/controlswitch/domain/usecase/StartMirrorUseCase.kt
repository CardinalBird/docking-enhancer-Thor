package com.controlswitch.domain.usecase

import com.controlswitch.domain.model.MirrorStartRequest
import com.controlswitch.domain.repository.MirrorProcessRepository
import com.controlswitch.domain.repository.MirrorSettingsRepository

class StartMirrorUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(request: MirrorStartRequest) {
        mirrorProcessRepository.start(request)
        mirrorSettingsRepository.saveStarted(request, nowMillis())
    }
}
