package com.bortho.autovelox.di

import android.content.Context
import androidx.room.Room
import com.bortho.autovelox.data.Clock
import com.bortho.autovelox.data.local.VaPianDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): VaPianDatabase =
        Room.databaseBuilder(context, VaPianDatabase::class.java, VaPianDatabase.NAME).build()

    @Provides
    @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }
}
