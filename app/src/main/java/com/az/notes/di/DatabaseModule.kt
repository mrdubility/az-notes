package com.az.notes.di

import android.content.Context
import androidx.room.Room
import com.az.notes.data.local.AppDatabase
import com.az.notes.data.local.FileIndexDao
import com.az.notes.data.local.ReadProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Room 数据库依赖注入（§4.1）。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideFileIndexDao(db: AppDatabase): FileIndexDao = db.fileIndexDao()

    @Provides
    fun provideReadProgressDao(db: AppDatabase): ReadProgressDao = db.readProgressDao()
}
