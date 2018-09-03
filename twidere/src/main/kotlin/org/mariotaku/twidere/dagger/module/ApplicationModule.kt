/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.dagger.module

import android.app.Application
import android.content.*
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Looper
import android.support.v4.net.ConnectivityManagerCompat
import android.support.v4.text.BidiFormatter
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.upstream.DataSource
import com.twitter.Extractor
import dagger.Module
import dagger.Provides
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.mariotaku.mediaviewer.library.FileCache
import org.mariotaku.mediaviewer.library.MediaDownloader
import org.mariotaku.restfu.http.RestHttpClient
import org.mariotaku.restfu.okhttp3.OkHttpRestClient
import org.mariotaku.twidere.Constants.*
import org.mariotaku.twidere.extension.model.load
import org.mariotaku.twidere.model.DefaultFeatures
import org.mariotaku.twidere.singleton.BusSingleton
import org.mariotaku.twidere.singleton.CacheSingleton
import org.mariotaku.twidere.taskcontroller.refresh.RefreshTaskController
import org.mariotaku.twidere.taskcontroller.sync.JobSchedulerSyncController
import org.mariotaku.twidere.taskcontroller.sync.LegacySyncController
import org.mariotaku.twidere.taskcontroller.sync.SyncTaskController
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.cache.DiskLRUFileCache
import org.mariotaku.twidere.util.cache.JsonCache
import org.mariotaku.twidere.util.lang.SingletonHolder
import org.mariotaku.twidere.util.media.MediaPreloader
import org.mariotaku.twidere.util.media.ThumborWrapper
import org.mariotaku.twidere.util.media.TwidereMediaDownloader
import org.mariotaku.twidere.util.net.TwidereDns
import org.mariotaku.twidere.util.notification.ContentNotificationManager
import org.mariotaku.twidere.util.preference.PreferenceChangeNotifier
import org.mariotaku.twidere.util.sync.DataSyncProvider
import org.mariotaku.twidere.util.sync.SyncPreferences
import java.io.File
import javax.inject.Singleton

@Module
class ApplicationModule(private val application: Application) {

    init {
        if (Thread.currentThread() !== Looper.getMainLooper().thread) {
            throw RuntimeException("Module must be created inside main thread")
        }
    }

    @Provides
    @Singleton
    fun keyboardShortcutsHandler(): KeyboardShortcutsHandler {
        return KeyboardShortcutsHandler(application)
    }

    @Provides
    @Singleton
    fun externalThemeManager(preferences: SharedPreferences): ExternalThemeManager {
        val manager = ExternalThemeManager(application, preferences)
        PreferenceChangeNotifier.get(application).register(KEY_EMOJI_SUPPORT) {
            manager.reloadEmojiPreferences()
        }
        val packageFilter = IntentFilter()
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        application.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
                val packages = application.packageManager.getPackagesForUid(uid)
                if (manager.emojiPackageName in packages) {
                    manager.reloadEmojiPreferences()
                }
            }
        }, packageFilter)
        return manager
    }

    @Provides
    @Singleton
    fun notificationManagerWrapper(): NotificationManagerWrapper {
        return NotificationManagerWrapper(application)
    }

    @Provides
    @Singleton
    fun sharedPreferences(): SharedPreferences {
        return application.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun preferenceChangeNotifier(): PreferenceChangeNotifier {
        return PreferenceChangeNotifier.get(application)
    }

    @Provides
    @Singleton
    fun multiSelectManager(): MultiSelectManager {
        return MultiSelectManager()
    }

    @Provides
    @Singleton
    fun restHttpClient(prefs: SharedPreferences): RestHttpClient {
        val dns = TwidereDns.get(application)
        val cache = CacheSingleton.get(application)
        val conf = HttpClientFactory.HttpClientConfiguration(prefs)
        val client = HttpClientFactory.createRestHttpClient(conf, dns, cache)
        PreferenceChangeNotifier.get(application).register(KEY_ENABLE_PROXY, KEY_PROXY_ADDRESS, KEY_PROXY_TYPE,
                KEY_PROXY_USERNAME, KEY_PROXY_PASSWORD, KEY_CONNECTION_TIMEOUT,
                KEY_RETRY_ON_NETWORK_ISSUE) changed@{
            if (client !is OkHttpRestClient) return@changed
            val builder = OkHttpClient.Builder()
            HttpClientFactory.initOkHttpClient(HttpClientFactory.HttpClientConfiguration(prefs),
                    builder, dns, cache)
            client.client = builder.build()
        }

        return client
    }

    @Provides
    @Singleton
    fun connectionPool(): ConnectionPool {
        return ConnectionPool()
    }

    @Provides
    @Singleton
    fun activityTracker(): ActivityTracker {
        return ActivityTracker()
    }

    @Provides
    @Singleton
    fun readStateManager(): ReadStateManager {
        return ReadStateManager(application)
    }

    @Provides
    @Singleton
    fun contentNotificationManager(activityTracker: ActivityTracker, notificationManagerWrapper: NotificationManagerWrapper,
            preferences: SharedPreferences): ContentNotificationManager {
        val manager = ContentNotificationManager(application, activityTracker, UserColorNameManager.get(application),
                notificationManagerWrapper, preferences)
        PreferenceChangeNotifier.get(application).register(KEY_NAME_FIRST, KEY_I_WANT_MY_STARS_BACK) {
            manager.updatePreferences()
        }
        return manager
    }

    @Provides
    @Singleton
    fun mediaPreloader(preferences: SharedPreferences): MediaPreloader {
        val preloader = MediaPreloader(application)
        preloader.reloadOptions(preferences)
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        preloader.isNetworkMetered = ConnectivityManagerCompat.isActiveNetworkMetered(cm)
        PreferenceChangeNotifier.get(application).register(KEY_MEDIA_PRELOAD, KEY_PRELOAD_WIFI_ONLY) {
            preloader.reloadOptions(preferences)
        }
        return preloader
    }

    @Provides
    @Singleton
    fun mediaDownloader(client: RestHttpClient, thumbor: ThumborWrapper): MediaDownloader {
        return TwidereMediaDownloader(application, client, thumbor)
    }

    @Provides
    @Singleton
    fun extractor(): Extractor {
        return Extractor()
    }

    @Provides
    @Singleton
    fun errorInfoStore(): ErrorInfoStore {
        return ErrorInfoStore(application)
    }

    @Provides
    @Singleton
    fun thumborWrapper(preferences: SharedPreferences): ThumborWrapper {
        val thumbor = ThumborWrapper()
        thumbor.reloadSettings(preferences)
        PreferenceChangeNotifier.get(application).register(KEY_THUMBOR_ENABLED, KEY_THUMBOR_ADDRESS, KEY_THUMBOR_SECURITY_KEY) {
            thumbor.reloadSettings(preferences)
        }
        return thumbor
    }

    @Provides
    @Singleton
    fun provideBidiFormatter(): BidiFormatter {
        return BidiFormatter.getInstance()
    }

    @Provides
    @Singleton
    fun autoRefreshController(): RefreshTaskController {
        return RefreshTaskController.get(application)
    }

    @Provides
    @Singleton
    fun syncController(provider: DataSyncProvider): SyncTaskController {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return JobSchedulerSyncController(application, provider)
        }
        return LegacySyncController(application, provider)
    }

    @Provides
    @Singleton
    fun syncPreferences(): SyncPreferences {
        return SyncPreferences(application)
    }

    @Provides
    @Singleton
    fun taskCreator(preferences: SharedPreferences, activityTracker: ActivityTracker,
            dataSyncProvider: DataSyncProvider): TaskServiceRunner {
        return TaskServiceRunner(application, preferences, activityTracker, dataSyncProvider, BusSingleton)
    }

    @Provides
    @Singleton
    fun defaultFeatures(preferences: SharedPreferences): DefaultFeatures {
        val features = DefaultFeatures()
        features.load(preferences)
        return features
    }

    @Provides
    @Singleton
    fun etagCache(): ETagCache {
        return ETagCache(application)
    }

    @Provides
    @Singleton
    fun locationManager(): LocationManager {
        return application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @Provides
    @Singleton
    fun connectivityManager(): ConnectivityManager {
        return application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Provides
    @Singleton
    fun okHttpClient(preferences: SharedPreferences): OkHttpClient {
        val dns = TwidereDns.get(application)
        val cache = CacheSingleton.get(application)
        val conf = HttpClientFactory.HttpClientConfiguration(preferences)
        val builder = OkHttpClient.Builder()
        HttpClientFactory.initOkHttpClient(conf, builder, dns, cache)
        return builder.build()
    }

    @Provides
    @Singleton
    fun dataSourceFactory(preferences: SharedPreferences): DataSource.Factory {
        val dns = TwidereDns.get(application)
        val cache = CacheSingleton.get(application)
        val conf = HttpClientFactory.HttpClientConfiguration(preferences)
        val builder = OkHttpClient.Builder()
        HttpClientFactory.initOkHttpClient(conf, builder, dns, cache)
        val userAgent = UserAgentUtils.getDefaultUserAgentStringSafe(application)
        return OkHttpDataSourceFactory(builder.build(), userAgent, null)
    }

    @Provides
    @Singleton
    fun cache(): Cache {
        return CacheSingleton.get(application)
    }

    @Provides
    @Singleton
    fun extractorsFactory(): ExtractorsFactory {
        return DefaultExtractorsFactory()
    }

    @Provides
    @Singleton
    fun jsonCache(): JsonCache {
        return JsonCache(getCacheDir("json", 100 * 1048576L))
    }

    @Provides
    @Singleton
    fun fileCache(): FileCache {
        return DiskLRUFileCache(getCacheDir("media", 100 * 1048576L))
    }

    @Provides
    @Singleton
    fun mastodonApplicationRegistry(): MastodonApplicationRegistry {
        return MastodonApplicationRegistry(application)
    }

    private fun getCacheDir(dirName: String, sizeInBytes: Long): File {
        return Utils.getExternalCacheDir(application, dirName, sizeInBytes)
                ?: Utils.getInternalCacheDir(application, dirName)
    }

    companion object : SingletonHolder<ApplicationModule, Application>(::ApplicationModule)

}
