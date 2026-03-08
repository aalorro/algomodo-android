package com.artmondo.algomodo.di

import android.content.Context
import androidx.room.Room
import com.artmondo.algomodo.data.db.AppDatabase
import com.artmondo.algomodo.data.db.PresetDao
import com.artmondo.algomodo.data.preferences.AppPreferences
import com.artmondo.algomodo.data.preferences.dataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "algomodo_database"
        ).addMigrations(AppDatabase.MIGRATION_1_2)
         .build()
    }

    @Provides
    @Singleton
    fun providePresetDao(database: AppDatabase): PresetDao {
        return database.presetDao()
    }

    @Provides
    @Singleton
    fun provideAppPreferences(@ApplicationContext context: Context): AppPreferences {
        return AppPreferences(context.dataStore)
    }
}
