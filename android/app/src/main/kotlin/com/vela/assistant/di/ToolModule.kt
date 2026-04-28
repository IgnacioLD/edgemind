package com.vela.assistant.di

import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.vela.assistant.data.tool.CalendarTools
import com.vela.assistant.data.tool.ContactsTools
import com.vela.assistant.data.tool.DeviceTools
import com.vela.assistant.data.tool.MusicTools
import com.vela.assistant.data.tool.SystemTools
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides @Singleton @IntoSet
    fun provideSystemToolsProvider(tools: SystemTools): ToolProvider = tool(tools)

    @Provides @Singleton @IntoSet
    fun provideCalendarToolsProvider(tools: CalendarTools): ToolProvider = tool(tools)

    @Provides @Singleton @IntoSet
    fun provideContactsToolsProvider(tools: ContactsTools): ToolProvider = tool(tools)

    @Provides @Singleton @IntoSet
    fun provideMusicToolsProvider(tools: MusicTools): ToolProvider = tool(tools)

    @Provides @Singleton @IntoSet
    fun provideDeviceToolsProvider(tools: DeviceTools): ToolProvider = tool(tools)
}
