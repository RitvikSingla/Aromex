package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.repository.PreferencesRepository

class SetSelectedLanguageUseCase(
    private val preferencesRepository: PreferencesRepository,
) {
    suspend fun execute(code: String) {
        preferencesRepository.setSelectedLanguageCode(code)
    }
}
