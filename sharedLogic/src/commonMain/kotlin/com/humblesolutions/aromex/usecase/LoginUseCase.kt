package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.model.ResolvedCompany
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.AuthRepository
import com.humblesolutions.aromex.repository.CompanyDirectoryRepository
import com.humblesolutions.aromex.repository.UserRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Login result. Either:
 *  - Success: signed in, session + active config built.
 *  - NeedsCompanyChoice: the email resolved to multiple companies; the caller
 *    must ask the user to pick one and then call [LoginUseCase.finishLogin].
 */
sealed class LoginResult {
    data class Success(val authenticated: AuthenticatedSession) : LoginResult()
    data class NeedsCompanyChoice(
        val email: String,
        val password: String,
        val candidates: List<ResolvedCompany>,
    ) : LoginResult()
}

class LoginUseCase(
    private val directory: CompanyDirectoryRepository,
    private val auth: AuthRepository,
    private val user: UserRepository,
) {
    @Throws(LoginException::class, CancellationException::class)
    suspend fun execute(email: String, password: String): LoginResult {
        require(email.isNotBlank()) { "email cannot be blank" }
        require(password.isNotBlank()) { "password cannot be blank" }

        val companies = directory.resolveCompanies(email)
        if (companies.isEmpty()) throw LoginException(LoginError.UnknownEmail)

        if (companies.size > 1) {
            return LoginResult.NeedsCompanyChoice(email, password, companies)
        }
        return LoginResult.Success(finishLogin(companies.single(), email, password))
    }

    /**
     * Used by the chooser screen after the user picks a company from the
     * NeedsCompanyChoice candidates.
     */
    @Throws(LoginException::class, CancellationException::class)
    suspend fun finishLogin(
        company: ResolvedCompany,
        email: String,
        password: String,
    ): AuthenticatedSession {
        val uid = auth.signIn(company.firebaseConfig, email, password)
        val profile = user.getUserProfile(company.firebaseConfig, uid)
            ?: run {
                auth.signOut(company.firebaseConfig)
                throw LoginException(LoginError.MissingUserRecord)
            }
        if (!profile.isActive) {
            auth.signOut(company.firebaseConfig)
            throw LoginException(LoginError.AccountDisabled)
        }
        val companyProfile = user.getCompanyProfile(company.firebaseConfig)
            ?: run {
                auth.signOut(company.firebaseConfig)
                throw LoginException(LoginError.Unexpected("companySettings/profile missing"))
            }
        return AuthenticatedSession(
            session = UserSession(
                uid = profile.uid,
                email = profile.email,
                displayName = profile.displayName,
                role = profile.role,
                permissions = profile.permissions,
                companyId = company.companyId,
                hlCompanyId = companyProfile.hlCompanyId,
                currency = companyProfile.currency,
                tax = companyProfile.tax,
                timezone = companyProfile.timezone,
                isActive = profile.isActive,
            ),
            config = company.firebaseConfig,
        )
    }
}
