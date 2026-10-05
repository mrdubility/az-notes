package com.az.notes.di

import android.content.Context
import androidx.room.Room
import com.az.notes.data.local.AppDatabase
import com.az.notes.data.local.ConflictRecordDao
import com.az.notes.data.local.ReadProgressDao
import com.az.notes.data.local.SyncBaselineDao
import com.az.notes.data.local.SyncLogDao
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
    fun provideReadProgressDao(db: AppDatabase): ReadProgressDao = db.readProgressDao()

    @Provides
    fun provideSyncBaselineDao(db: AppDatabase): SyncBaselineDao = db.syncBaselineDao()

    @Provides
    fun provideSyncLogDao(db: AppDatabase): SyncLogDao = db.syncLogDao()

    @Provides
    fun provideConflictRecordDao(db: AppDatabase): ConflictRecordDao = db.conflictRecordDao()
}
