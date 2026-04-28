package com.vela.assistant.di

import com.vela.assistant.data.repository.ConversationRepositoryImpl
import com.vela.assistant.data.repository.ModelAvailabilityRepositoryImpl
import com.vela.assistant.data.repository.ModelRepositoryImpl
import com.vela.assistant.domain.repository.ConversationRepository
import com.vela.assistant.domain.repository.ModelAvailabilityRepository
import com.vela.assistant.domain.repository.ModelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: ConversationRepositoryImpl,
    ): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindModelRepository(
        impl: ModelRepositoryImpl,
    ): ModelRepository

    @Binds
    @Singleton
    abstract fun bindModelAvailabilityRepository(
        impl: ModelAvailabilityRepositoryImpl,
    ): ModelAvailabilityRepository
}
