package com.localai.assistant.di

import com.localai.assistant.data.repository.ConversationRepositoryImpl
import com.localai.assistant.data.repository.ModelRepositoryImpl
import com.localai.assistant.domain.repository.ConversationRepository
import com.localai.assistant.domain.repository.ModelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for repository bindings
 * Uses @Binds for better performance than @Provides
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: ConversationRepositoryImpl
    ): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindModelRepository(
        impl: ModelRepositoryImpl
    ): ModelRepository
}
