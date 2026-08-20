package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.Language
import com.humblesolutions.aromex.repository.PreferencesRepository

class GetSelectedLanguageUseCase(
    private val preferencesRepository: PreferencesRepository,
) {
    suspend fun execute(): Language =
        Language.fromCode(preferencesRepository.getSelectedLanguageCode())
}
