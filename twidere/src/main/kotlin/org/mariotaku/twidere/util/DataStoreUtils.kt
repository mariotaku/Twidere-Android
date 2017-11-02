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

package org.mariotaku.twidere.util

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.BaseColumns
import android.support.annotation.WorkerThread
import android.text.TextUtils
import org.mariotaku.kpreferences.get
import org.mariotaku.ktextension.mapToArray
import org.mariotaku.library.objectcursor.ObjectCursor
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.mastodon.Mastodon
import org.mariotaku.microblog.library.twitter.model.Activity
import org.mariotaku.sqliteqb.library.*
import org.mariotaku.sqliteqb.library.Columns.Column
import org.mariotaku.sqliteqb.library.query.SQLSelectQuery
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.*
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.annotation.FilterScope
import org.mariotaku.twidere.constant.*
import org.mariotaku.twidere.extension.model.*
import org.mariotaku.twidere.extension.model.api.mastodon.toParcelable
import org.mariotaku.twidere.extension.model.api.toParcelable
import org.mariotaku.twidere.extension.model.tab.applyToSelection
import org.mariotaku.twidere.extension.queryCount
import org.mariotaku.twidere.extension.queryOne
import org.mariotaku.twidere.extension.queryReference
import org.mariotaku.twidere.extension.rawQueryReference
import org.mariotaku.twidere.model.*
import org.mariotaku.twidere.model.tab.extra.HomeTabExtras
import org.mariotaku.twidere.model.tab.extra.InteractionsTabExtras
import org.mariotaku.twidere.model.tab.extra.TabExtras
import org.mariotaku.twidere.model.util.AccountUtils
import org.mariotaku.twidere.provider.TwidereDataStore
import org.mariotaku.twidere.provider.TwidereDataStore.*
import org.mariotaku.twidere.provider.TwidereDataStore.Messages.Conversations
import org.mariotaku.twidere.util.content.ContentResolverUtils
import java.io.IOException
import java.util.*

object DataStoreUtils {

    val STATUSES_URIS = arrayOf(Statuses.HomeTimeline.CONTENT_URI, Statuses.Favorites.CONTENT_URI,
            Statuses.UserTimeline.CONTENT_URI, Statuses.UserMediaTimeline.CONTENT_URI,
            Statuses.ListTimeline.CONTENT_URI, Statuses.GroupTimeline.CONTENT_URI,
            Statuses.Public.CONTENT_URI, Statuses.NetworkPublic.CONTENT_URI)
    val CACHE_URIS = arrayOf(CachedUsers.CONTENT_URI, CachedStatuses.CONTENT_URI,
            CachedHashtags.CONTENT_URI, CachedTrends.Local.CONTENT_URI)
    val MESSAGES_URIS = arrayOf(Messages.CONTENT_URI, Conversations.CONTENT_URI)
    val ACTIVITIES_URIS = arrayOf(Activities.AboutMe.CONTENT_URI)
    val STATUSES_ACTIVITIES_URIS = STATUSES_URIS + ACTIVITIES_URIS

    private val tableMatcher = UriMatcher(UriMatcher.NO_MATCH)

    init {
        tableMatcher.addPath(Statuses.HomeTimeline.CONTENT_PATH, TableIds.HOME_TIMELINE)
        tableMatcher.addPath(Statuses.Public.CONTENT_PATH, TableIds.PUBLIC_TIMELINE)
        tableMatcher.addPath(Statuses.NetworkPublic.CONTENT_PATH, TableIds.NETWORK_PUBLIC_TIMELINE)

        tableMatcher.addPath(Statuses.Favorites.CONTENT_PATH, TableIds.FAVORITES)
        tableMatcher.addPath(Statuses.UserTimeline.CONTENT_PATH, TableIds.USER_TIMELINE)
        tableMatcher.addPath(Statuses.UserMediaTimeline.CONTENT_PATH, TableIds.USER_MEDIA_TIMELINE)
        tableMatcher.addPath(Statuses.ListTimeline.CONTENT_PATH, TableIds.LIST_TIMELINE)
        tableMatcher.addPath(Statuses.GroupTimeline.CONTENT_PATH, TableIds.GROUP_TIMELINE)
        tableMatcher.addPath(Statuses.SearchTimeline.CONTENT_PATH, TableIds.SEARCH_TIMELINE)
        tableMatcher.addPath(Statuses.MediaSearchTimeline.CONTENT_PATH, TableIds.MEDIA_SEARCH_TIMELINE)

        tableMatcher.addPath("${Statuses.Favorites.CONTENT_PATH}/#", TableIds.FAVORITES)
        tableMatcher.addPath("${Statuses.UserTimeline.CONTENT_PATH}/#", TableIds.USER_TIMELINE)
        tableMatcher.addPath("${Statuses.UserMediaTimeline.CONTENT_PATH}/#", TableIds.USER_MEDIA_TIMELINE)
        tableMatcher.addPath("${Statuses.ListTimeline.CONTENT_PATH}/#", TableIds.LIST_TIMELINE)
        tableMatcher.addPath("${Statuses.GroupTimeline.CONTENT_PATH}/#", TableIds.GROUP_TIMELINE)
        tableMatcher.addPath("${Statuses.SearchTimeline.CONTENT_PATH}/#", TableIds.SEARCH_TIMELINE)
        tableMatcher.addPath("${Statuses.MediaSearchTimeline.CONTENT_PATH}/#", TableIds.MEDIA_SEARCH_TIMELINE)

        tableMatcher.addPath(Activities.AboutMe.CONTENT_PATH, TableIds.ACTIVITIES_ABOUT_ME)
        tableMatcher.addPath(Drafts.CONTENT_PATH, TableIds.DRAFTS)
        tableMatcher.addPath(CachedUsers.CONTENT_PATH, TableIds.CACHED_USERS)
        tableMatcher.addPath(Filters.Users.CONTENT_PATH, TableIds.FILTERED_USERS)
        tableMatcher.addPath(Filters.Keywords.CONTENT_PATH, TableIds.FILTERED_KEYWORDS)
        tableMatcher.addPath(Filters.Sources.CONTENT_PATH, TableIds.FILTERED_SOURCES)
        tableMatcher.addPath(Filters.Links.CONTENT_PATH, TableIds.FILTERED_LINKS)
        tableMatcher.addPath(Filters.Subscriptions.CONTENT_PATH, TableIds.FILTERS_SUBSCRIPTIONS)
        tableMatcher.addPath(Messages.CONTENT_PATH, TableIds.MESSAGES)
        tableMatcher.addPath(Conversations.CONTENT_PATH, TableIds.MESSAGES_CONVERSATIONS)
        tableMatcher.addPath(CachedTrends.Local.CONTENT_PATH, TableIds.TRENDS_LOCAL)
        tableMatcher.addPath(Tabs.CONTENT_PATH, TableIds.TABS)
        tableMatcher.addPath(CachedStatuses.CONTENT_PATH, TableIds.CACHED_STATUSES)
        tableMatcher.addPath(CachedHashtags.CONTENT_PATH, TableIds.CACHED_HASHTAGS)
        tableMatcher.addPath(CachedRelationships.CONTENT_PATH, TableIds.CACHED_RELATIONSHIPS)
        tableMatcher.addPath(SavedSearches.CONTENT_PATH, TableIds.SAVED_SEARCHES)
        tableMatcher.addPath(SearchHistory.CONTENT_PATH, TableIds.SEARCH_HISTORY)

        tableMatcher.addPath(Permissions.CONTENT_PATH, TableIds.VIRTUAL_PERMISSIONS)
        tableMatcher.addPath("${CachedUsers.CONTENT_PATH_WITH_RELATIONSHIP}/*", TableIds.VIRTUAL_CACHED_USERS_WITH_RELATIONSHIP)
        tableMatcher.addPath("${CachedUsers.CONTENT_PATH_WITH_SCORE}/*", TableIds.VIRTUAL_CACHED_USERS_WITH_SCORE)
        tableMatcher.addPath(Drafts.CONTENT_PATH_UNSENT, TableIds.VIRTUAL_DRAFTS_UNSENT)
        tableMatcher.addPath(Drafts.CONTENT_PATH_NOTIFICATIONS, TableIds.VIRTUAL_DRAFTS_NOTIFICATIONS)
        tableMatcher.addPath("${Drafts.CONTENT_PATH_NOTIFICATIONS}/#", TableIds.VIRTUAL_DRAFTS_NOTIFICATIONS)
        tableMatcher.addPath(Suggestions.AutoComplete.CONTENT_PATH, TableIds.VIRTUAL_SUGGESTIONS_AUTO_COMPLETE)
        tableMatcher.addPath(Suggestions.Search.CONTENT_PATH, TableIds.VIRTUAL_SUGGESTIONS_SEARCH)
        tableMatcher.addPath(TwidereDataStore.CONTENT_PATH_DATABASE_PREPARE, TableIds.VIRTUAL_DATABASE_PREPARE)
        tableMatcher.addPath(TwidereDataStore.CONTENT_PATH_NULL, TableIds.VIRTUAL_NULL)
        tableMatcher.addPath(TwidereDataStore.CONTENT_PATH_EMPTY, TableIds.VIRTUAL_EMPTY)
        tableMatcher.addPath("${TwidereDataStore.CONTENT_PATH_RAW_QUERY}/*", TableIds.VIRTUAL_RAW_QUERY)
    }

    fun getNewestStatusIds(context: Context, uri: Uri, accountKeys: Array<UserKey?>): Array<String?> {
        return getStringFieldArray(context, uri, accountKeys, Statuses.ACCOUNT_KEY, Statuses.ID,
                OrderBy(SQLFunctions.MAX(Statuses.TIMESTAMP)), null, null)
    }

    fun getNewestMessageIds(context: Context, uri: Uri, accountKeys: Array<UserKey?>, outgoing: Boolean): Array<String?> {
        val having: Expression = Expression.equals(Messages.IS_OUTGOING, if (outgoing) 1 else 0)
        return getStringFieldArray(context, uri, accountKeys, Messages.ACCOUNT_KEY, Messages.MESSAGE_ID,
                OrderBy(SQLFunctions.MAX(Messages.LOCAL_TIMESTAMP)), having, null)
    }

    fun getOldestMessageIds(context: Context, uri: Uri, accountKeys: Array<UserKey?>, outgoing: Boolean): Array<String?> {
        if (accountKeys.all { it == null }) return arrayOfNulls(accountKeys.size)
        val having: Expression = Expression.equals(Messages.IS_OUTGOING, if (outgoing) 1 else 0)
        return getStringFieldArray(context, uri, accountKeys, Messages.ACCOUNT_KEY, Messages.MESSAGE_ID,
                OrderBy(SQLFunctions.MIN(Messages.LOCAL_TIMESTAMP)), having, null)
    }

    fun getOldestConversations(context: Context, uri: Uri, accountKeys: Array<UserKey?>): Array<ParcelableMessageConversation?> {
        if (accountKeys.all { it == null }) return arrayOfNulls(accountKeys.size)
        return getObjectFieldArray(context, uri, accountKeys, Conversations.ACCOUNT_KEY, Conversations.COLUMNS,
                OrderBy(SQLFunctions.MIN(Messages.LOCAL_TIMESTAMP)), null, null,
                { ObjectCursor.indicesFrom(it, ParcelableMessageConversation::class.java) },
                { arrayOfNulls<ParcelableMessageConversation>(it) })
    }

    fun getNewestConversations(context: Context, uri: Uri, accountKeys: Array<UserKey?>,
            extraWhere: Expression? = null, extraWhereArgs: Array<String>? = null): Array<ParcelableMessageConversation?> {
        if (accountKeys.all { it == null }) return arrayOfNulls(accountKeys.size)
        return getObjectFieldArray(context, uri, accountKeys, Conversations.ACCOUNT_KEY, Conversations.COLUMNS,
                OrderBy(SQLFunctions.MAX(Messages.LOCAL_TIMESTAMP)), extraWhere, extraWhereArgs,
                { ObjectCursor.indicesFrom(it, ParcelableMessageConversation::class.java) },
                { arrayOfNulls<ParcelableMessageConversation>(it) })
    }

    fun getNewestStatusSortIds(context: Context, uri: Uri, accountKeys: Array<UserKey?>): LongArray {
        return getLongFieldArray(context, uri, accountKeys, Statuses.ACCOUNT_KEY, Statuses.SORT_ID,
                OrderBy(SQLFunctions.MAX(Statuses.TIMESTAMP)), null, null)
    }


    fun getOldestStatusIds(context: Context, uri: Uri, accountKeys: Array<UserKey?>): Array<String?> {
        return getStringFieldArray(context, uri, accountKeys, Statuses.ACCOUNT_KEY, Statuses.ID,
                OrderBy(SQLFunctions.MIN(Statuses.TIMESTAMP)), null, null)
    }


    fun getOldestStatusSortIds(context: Context, uri: Uri, accountKeys: Array<UserKey?>): LongArray {
        return getLongFieldArray(context, uri, accountKeys, Statuses.ACCOUNT_KEY, Statuses.SORT_ID,
                OrderBy(SQLFunctions.MIN(Statuses.TIMESTAMP)), null, null)
    }

    fun getNewestActivityMaxPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>,
            extraWhere: Expression?, extraWhereArgs: Array<String>?): Array<String?> {
        return getStringFieldArray(context, uri, accountKeys, Activities.ACCOUNT_KEY,
                Activities.MAX_REQUEST_POSITION, OrderBy(SQLFunctions.MAX(Activities.TIMESTAMP)),
                extraWhere, extraWhereArgs)
    }

    fun getRefreshNewestActivityMaxPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>):
            Array<String?> {
        return getOfficialSeparatedIds(context, { keys, isOfficial ->
            val (where, whereArgs) = getIdsWhere(isOfficial)
            DataStoreUtils.getNewestActivityMaxPositions(context, uri, keys, where, whereArgs)
        }, { arr1, arr2 ->
            Array(accountKeys.size) { arr1[it] ?: arr2[it] }
        }, accountKeys)
    }

    fun getOldestActivityMaxPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>,
            extraWhere: Expression?, extraWhereArgs: Array<String>?): Array<String?> {
        return getStringFieldArray(context, uri, accountKeys, Activities.ACCOUNT_KEY,
                Activities.MAX_REQUEST_POSITION, OrderBy(SQLFunctions.MIN(Activities.TIMESTAMP)),
                extraWhere, extraWhereArgs)
    }

    fun getRefreshOldestActivityMaxPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>):
            Array<String?> {
        return getOfficialSeparatedIds(context, { keys, isOfficial ->
            val (where, whereArgs) = getIdsWhere(isOfficial)
            DataStoreUtils.getOldestActivityMaxPositions(context, uri, keys, where, whereArgs)
        }, { arr1, arr2 ->
            Array(accountKeys.size) { arr1[it] ?: arr2[it] }
        }, accountKeys)
    }

    fun getNewestActivityMaxSortPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>,
            extraWhere: Expression?, extraWhereArgs: Array<String>?): LongArray {
        return getLongFieldArray(context, uri, accountKeys, Activities.ACCOUNT_KEY,
                Activities.MAX_SORT_POSITION, OrderBy(SQLFunctions.MAX(Activities.TIMESTAMP)),
                extraWhere, extraWhereArgs)
    }

    fun getRefreshNewestActivityMaxSortPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>):
            LongArray {
        return getOfficialSeparatedIds(context, { keys, isOfficial ->
            val (where, whereArgs) = getIdsWhere(isOfficial)
            DataStoreUtils.getNewestActivityMaxSortPositions(context, uri, keys, where, whereArgs)
        }, { arr1, arr2 ->
            LongArray(accountKeys.size) { arr1[it].takeIf { it > 0 } ?: arr2[it] }
        }, accountKeys)
    }

    fun getOldestActivityMaxSortPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>,
            extraWhere: Expression?, extraWhereArgs: Array<String>?): LongArray {
        return getLongFieldArray(context, uri, accountKeys, Activities.ACCOUNT_KEY,
                Activities.MAX_SORT_POSITION, OrderBy(SQLFunctions.MIN(Activities.TIMESTAMP)),
                extraWhere, extraWhereArgs)
    }

    fun getRefreshOldestActivityMaxSortPositions(context: Context, uri: Uri, accountKeys: Array<UserKey?>):
            LongArray {
        return getOfficialSeparatedIds(context, { keys, isOfficial ->
            val (where, whereArgs) = getIdsWhere(isOfficial)
            DataStoreUtils.getOldestActivityMaxSortPositions(context, uri, keys, where, whereArgs)
        }, { arr1, arr2 ->
            LongArray(accountKeys.size) { arr1[it].takeIf { it > 0 } ?: arr2[it] }
        }, accountKeys)
    }

    fun getStatusCount(context: Context, uri: Uri, accountKey: UserKey): Int {
        val where = Expression.equalsArgs(AccountSupportColumns.ACCOUNT_KEY).sql
        val whereArgs = arrayOf(accountKey.toString())
        return context.contentResolver.queryCount(uri, where, whereArgs)
    }

    fun getActivitiesCount(context: Context, uri: Uri,
            accountKey: UserKey): Int {
        val where = Expression.equalsArgs(AccountSupportColumns.ACCOUNT_KEY).sql
        return context.contentResolver.queryCount(uri, where, arrayOf(accountKey.toString()))
    }

    fun getAccountDisplayName(context: Context, accountKey: UserKey, nameFirst: Boolean): String? {
        val name: String?
        if (nameFirst) {
            name = getAccountName(context, accountKey)
        } else {
            name = "@${getAccountScreenName(context, accountKey)}"
        }
        return name
    }

    fun getAccountName(context: Context, accountKey: UserKey): String? {
        val am = AccountManager.get(context)
        val account = AccountUtils.findByAccountKey(am, accountKey) ?: return null

        return account.getAccountUser(am).name
    }

    fun getAccountScreenName(context: Context, accountKey: UserKey): String? {
        val am = AccountManager.get(context)
        val account = AccountUtils.findByAccountKey(am, accountKey) ?: return null
        return account.getAccountUser(am).screen_name
    }

    fun getActivatedAccountKeys(context: Context): Array<UserKey> {
        val am = AccountManager.get(context)
        val keys = ArrayList<UserKey>()
        for (account in AccountUtils.getAccounts(am)) {
            if (account.isActivated(am)) {
                keys.add(account.getAccountKey(am))
            }
        }
        return keys.toTypedArray()
    }

    fun getStatusesCount(context: Context, preferences: SharedPreferences, uri: Uri,
            extraArgs: Bundle?, compareColumn: String, compare: Long, greaterThan: Boolean,
            accountKeys: Array<UserKey>?, @FilterScope filterScopes: Int): Int {
        val keys = accountKeys ?: getActivatedAccountKeys(context)

        val expressions = ArrayList<Expression>()
        val expressionArgs = ArrayList<String>()

        expressions.add(Expression.inArgs(Column(Statuses.ACCOUNT_KEY), keys.size))
        for (accountKey in keys) {
            expressionArgs.add(accountKey.toString())
        }

        if (greaterThan) {
            expressions.add(Expression.greaterThan(compareColumn, compare))
        } else {
            expressions.add(Expression.lesserThan(compareColumn, compare))
        }

        expressions.add(buildStatusFilterWhereClause(preferences, getTableNameByUri(uri)!!,
                null, filterScopes))

        if (extraArgs != null) {
            val extras = extraArgs.getParcelable<Parcelable>(EXTRA_EXTRAS)
            if (extras is HomeTabExtras) {
                extras.applyToSelection(expressions, expressionArgs)
            }
        }

        val selection = Expression.and(*expressions.toTypedArray())
        return context.contentResolver.queryCount(uri, selection.sql, expressionArgs.toTypedArray())
    }

    fun getActivitiesCount(context: Context, preferences: SharedPreferences, uri: Uri,
            compareColumn: String, compare: Long, greaterThan: Boolean,
            accountKeys: Array<UserKey>?, @FilterScope filterScopes: Int): Int {
        val keys = accountKeys ?: getActivatedAccountKeys(context)
        val selection = Expression.and(
                Expression.inArgs(Column(Activities.ACCOUNT_KEY), keys.size),
                if (greaterThan) {
                    Expression.greaterThan(compareColumn, compare)
                } else {
                    Expression.lesserThan(compareColumn, compare)
                },
                buildStatusFilterWhereClause(preferences, getTableNameByUri(uri)!!, null,
                        filterScopes)
        )
        val whereArgs = arrayListOf<String>()
        keys.mapTo(whereArgs) { it.toString() }
        return context.contentResolver.queryCount(uri, selection.sql, whereArgs.toTypedArray())
    }

    fun getActivitiesCount(context: Context, preferences: SharedPreferences, uri: Uri,
            extraWhere: Expression?, extraWhereArgs: Array<String>?,
            sinceColumn: String, since: Long, followingOnly: Boolean, accountKeys: Array<UserKey>?,
            @FilterScope filterScopes: Int): Int {
        val keys = (accountKeys ?: getActivatedAccountKeys(context)).mapToArray { it.toString() }
        val expressions = ArrayList<Expression>()
        expressions.add(Expression.inArgs(Column(Activities.ACCOUNT_KEY), keys.size))
        expressions.add(Expression.greaterThan(sinceColumn, since))
        expressions.add(buildStatusFilterWhereClause(preferences, getTableNameByUri(uri)!!, null,
                filterScopes))
        if (extraWhere != null) {
            expressions.add(extraWhere)
        }
        val selection = Expression.and(*expressions.toTypedArray())
        var selectionArgs = keys
        if (extraWhereArgs != null) {
            selectionArgs += extraWhereArgs
        }
        // If followingOnly option is on, we have to iterate over items
        val resolver = context.contentResolver
        if (followingOnly) {
            val projection = arrayOf(Activities.SOURCES)
            return resolver.queryReference(uri, projection, selection.sql, selectionArgs, null)?.use { (cur) ->
                var total = 0
                cur.moveToFirst()
                while (!cur.isAfterLast) {
                    val string = cur.getString(0)
                    if (TextUtils.isEmpty(string)) continue
                    var hasFollowing = false
                    try {
                        for (state in JsonSerializer.parseList(string, UserFollowState::class.java)) {
                            if (state.is_following) {
                                hasFollowing = true
                                break
                            }
                        }
                    } catch (e: IOException) {
                        continue
                    }

                    if (hasFollowing) {
                        total++
                    }
                    cur.moveToNext()
                }
                return@use total
            } ?: 0
        }
        return resolver.queryCount(uri, selection.sql, selectionArgs)
    }

    fun getTableId(uri: Uri): Int = tableMatcher.match(uri)

    fun getTableNameById(id: Int): String? = when (id) {
        TableIds.HOME_TIMELINE -> Statuses.HomeTimeline.TABLE_NAME
        TableIds.FAVORITES -> Statuses.Favorites.TABLE_NAME
        TableIds.USER_TIMELINE -> Statuses.UserTimeline.TABLE_NAME
        TableIds.USER_MEDIA_TIMELINE -> Statuses.UserMediaTimeline.TABLE_NAME
        TableIds.LIST_TIMELINE -> Statuses.ListTimeline.TABLE_NAME
        TableIds.GROUP_TIMELINE -> Statuses.GroupTimeline.TABLE_NAME
        TableIds.PUBLIC_TIMELINE -> Statuses.Public.TABLE_NAME
        TableIds.NETWORK_PUBLIC_TIMELINE -> Statuses.NetworkPublic.TABLE_NAME

        TableIds.ACTIVITIES_ABOUT_ME -> Activities.AboutMe.TABLE_NAME
        TableIds.DRAFTS -> Drafts.TABLE_NAME
        TableIds.FILTERED_USERS -> Filters.Users.TABLE_NAME
        TableIds.FILTERED_KEYWORDS -> Filters.Keywords.TABLE_NAME
        TableIds.FILTERED_SOURCES -> Filters.Sources.TABLE_NAME
        TableIds.FILTERED_LINKS -> Filters.Links.TABLE_NAME
        TableIds.FILTERS_SUBSCRIPTIONS -> Filters.Subscriptions.TABLE_NAME
        TableIds.MESSAGES -> Messages.TABLE_NAME
        TableIds.MESSAGES_CONVERSATIONS -> Conversations.TABLE_NAME
        TableIds.TRENDS_LOCAL -> CachedTrends.Local.TABLE_NAME
        TableIds.TABS -> Tabs.TABLE_NAME
        TableIds.CACHED_STATUSES -> CachedStatuses.TABLE_NAME
        TableIds.CACHED_USERS -> CachedUsers.TABLE_NAME
        TableIds.CACHED_HASHTAGS -> CachedHashtags.TABLE_NAME
        TableIds.CACHED_RELATIONSHIPS -> CachedRelationships.TABLE_NAME
        TableIds.SAVED_SEARCHES -> SavedSearches.TABLE_NAME
        TableIds.SEARCH_HISTORY -> SearchHistory.TABLE_NAME
        else -> null
    }

    fun getTableNameByUri(uri: Uri): String? = getTableNameById(getTableId(uri))

    fun buildStatusFilterWhereClause(preferences: SharedPreferences, table: String,
            extraSelection: Expression?, @FilterScope filterScopes: Int): Expression {

        fun ScopeMatchesExpression(scopeTable: String, scopeField: String) = Expression.or(
                Expression.equals("$scopeTable.$scopeField & ${FilterScope.MASK_SCOPE}", 0),
                Expression.notEquals("$scopeTable.$scopeField & $filterScopes", 0)
        )

        fun ContainsExpression(dataField: String, filterTable: String, filterField: String) =
                Expression.likeRaw(Column(Table(table), dataField), "'%'||$filterTable.$filterField||'%'")

        fun LineContainsExpression(dataField: String, filterTable: String, filterField: String) =
                Expression.likeRaw(Column(Table(table), dataField), "'\\%'||$filterTable.$filterField||'%\\'")

        fun LineMatchExpression(dataField: String, filterTable: String, filterField: String) =
                Expression.likeRaw(Column(Table(table), dataField), "'%\\'||$filterTable.$filterField||'\\%'")

        val filteredUsersWhere = Expression.and(
                ScopeMatchesExpression(Filters.Users.TABLE_NAME, Filters.Users.SCOPE),
                LineMatchExpression(Statuses.FILTER_USERS, Filters.Users.TABLE_NAME, Filters.Users.USER_KEY)
        )
        val filteredSourcesWhere = Expression.and(
                ScopeMatchesExpression(Filters.Sources.TABLE_NAME, Filters.Sources.SCOPE),
                LineMatchExpression(Statuses.FILTER_SOURCES, Filters.Sources.TABLE_NAME, Filters.Sources.VALUE)
        )
        val filteredTextKeywordsWhere = Expression.or(
                Expression.and(
                        Expression.or(
                                Expression.equals("${Filters.Keywords.TABLE_NAME}.${Filters.Keywords.SCOPE} & ${FilterScope.MASK_TARGET}", 0),
                                Expression.notEquals("${Filters.Keywords.TABLE_NAME}.${Filters.Keywords.SCOPE} & ${FilterScope.TARGET_TEXT}", 0)
                        ),
                        ScopeMatchesExpression(Filters.Keywords.TABLE_NAME, Filters.Keywords.SCOPE),
                        ContainsExpression(Statuses.FILTER_TEXTS, Filters.Keywords.TABLE_NAME, Filters.Keywords.VALUE)
                ),
                Expression.and(
                        Expression.notEquals("${Filters.Keywords.TABLE_NAME}.${Filters.Keywords.SCOPE} & ${FilterScope.TARGET_NAME}", 0),
                        ScopeMatchesExpression(Filters.Keywords.TABLE_NAME, Filters.Keywords.SCOPE),
                        LineMatchExpression(Statuses.FILTER_NAMES, Filters.Keywords.TABLE_NAME, Filters.Keywords.VALUE)
                ),
                Expression.and(
                        Expression.notEquals("${Filters.Keywords.TABLE_NAME}.${Filters.Keywords.SCOPE} & ${FilterScope.TARGET_DESCRIPTION}", 0),
                        ScopeMatchesExpression(Filters.Keywords.TABLE_NAME, Filters.Keywords.SCOPE),
                        ContainsExpression(Statuses.FILTER_DESCRIPTIONS, Filters.Keywords.TABLE_NAME, Filters.Keywords.VALUE)
                )
        )
        val filteredLinksWhere = Expression.and(
                ScopeMatchesExpression(Filters.Links.TABLE_NAME, Filters.Links.SCOPE),
                LineContainsExpression(Statuses.FILTER_LINKS, Filters.Links.TABLE_NAME, Filters.Links.VALUE)
        )
        val filteredIdsQueryBuilder = SQLQueryBuilder
                .select(Column(Table(table), Statuses._ID))
                .from(Tables(table, Filters.Users.TABLE_NAME))
                .where(filteredUsersWhere)
                .union()
                .select(Columns(Column(Table(table), Statuses._ID)))
                .from(Tables(table, Filters.Sources.TABLE_NAME))
                .where(filteredSourcesWhere)
                .union()
                .select(Columns(Column(Table(table), Statuses._ID)))
                .from(Tables(table, Filters.Keywords.TABLE_NAME))
                .where(filteredTextKeywordsWhere)
                .union()
                .select(Columns(Column(Table(table), Statuses._ID)))
                .from(Tables(table, Filters.Links.TABLE_NAME))
                .where(filteredLinksWhere)

        var filterFlags: Long = 0
        if (preferences[filterUnavailableQuoteStatusesKey]) {
            filterFlags = filterFlags or ParcelableStatus.FilterFlags.QUOTE_NOT_AVAILABLE
        }
        if (preferences[filterPossibilitySensitiveStatusesKey]) {
            filterFlags = filterFlags or ParcelableStatus.FilterFlags.POSSIBLY_SENSITIVE
        }

        val filterExpression = Expression.or(
                Expression.and(
                        Expression.equals("${Statuses.FILTER_FLAGS} & $filterFlags", 0),
                        Expression.notIn(Column(Table(table), Statuses._ID), filteredIdsQueryBuilder.build())
                ),
                Expression.equals(Column(Table(table), Statuses.IS_GAP), 1)
        )
        if (extraSelection != null) {
            return Expression.and(filterExpression, extraSelection)
        }
        return filterExpression
    }

    fun getAccountColors(context: Context, accountKeys: Array<UserKey>): IntArray {
        val am = AccountManager.get(context)
        val colors = IntArray(accountKeys.size)
        for (i in accountKeys.indices) {
            val account = AccountUtils.findByAccountKey(am, accountKeys[i])
            if (account != null) {
                colors[i] = account.getColor(am)
            }
        }
        return colors
    }

    fun findAccountKeyByScreenName(context: Context, screenName: String): UserKey? {
        val am = AccountManager.get(context)
        for (account in AccountUtils.getAccounts(am)) {
            val user = account.getAccountUser(am)
            if (screenName.equals(user.screen_name, ignoreCase = true)) {
                return user.key
            }
        }
        return null
    }

    fun getAccountKeys(context: Context): Array<UserKey> {
        val am = AccountManager.get(context)
        val accounts = AccountUtils.getAccounts(am)
        val keys = ArrayList<UserKey>(accounts.size)
        for (account in accounts) {
            val keyString = am.getUserData(account, ACCOUNT_USER_DATA_KEY) ?: continue
            keys.add(UserKey.valueOf(keyString))
        }
        return keys.toTypedArray()
    }

    fun findAccountKey(context: Context, accountId: String): UserKey? {
        val am = AccountManager.get(context)
        for (account in AccountUtils.getAccounts(am)) {
            val key = account.getAccountKey(am)
            if (accountId == key.id) {
                return key
            }
        }
        return null
    }

    fun hasAccount(context: Context): Boolean {
        return AccountUtils.getAccounts(AccountManager.get(context)).isNotEmpty()
    }

    @Synchronized
    fun cleanDatabasesByItemLimit(context: Context) {
        val resolver = context.contentResolver
        val preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val itemLimit = preferences[databaseItemLimitKey]

        for (accountKey in getAccountKeys(context)) {
            // Clean statuses.
            for (uri in STATUSES_URIS) {
                if (CachedStatuses.CONTENT_URI == uri) {
                    continue
                }
                val table = getTableNameByUri(uri)
                val qb = SQLSelectQuery.Builder()
                qb.select(Column(Statuses._ID))
                        .from(Tables(table))
                        .where(Expression.equalsArgs(Statuses.ACCOUNT_KEY))
                        .orderBy(OrderBy(Statuses.POSITION_KEY, false))
                        .limit(itemLimit)
                val where = Expression.and(
                        Expression.notIn(Column(Statuses._ID), qb.build()),
                        Expression.equalsArgs(Statuses.ACCOUNT_KEY)
                )
                val whereArgs = arrayOf(accountKey.toString(), accountKey.toString())
                resolver.delete(uri, where.sql, whereArgs)
            }
            for (uri in ACTIVITIES_URIS) {
                val table = getTableNameByUri(uri)
                val qb = SQLSelectQuery.Builder()
                qb.select(Column(Activities._ID))
                        .from(Tables(table))
                        .where(Expression.equalsArgs(Activities.ACCOUNT_KEY))
                        .orderBy(OrderBy(Activities.TIMESTAMP, false))
                        .limit(itemLimit)
                val where = Expression.and(
                        Expression.notIn(Column(Activities._ID), qb.build()),
                        Expression.equalsArgs(Activities.ACCOUNT_KEY)
                )
                val whereArgs = arrayOf(accountKey.toString(), accountKey.toString())
                resolver.delete(uri, where.sql, whereArgs)
            }
        }
        // Clean cached values.
        for (uri in CACHE_URIS) {
            val table = getTableNameByUri(uri) ?: continue
            val qb = SQLSelectQuery.Builder()
            qb.select(Column(BaseColumns._ID))
                    .from(Tables(table))
                    .orderBy(OrderBy(BaseColumns._ID, false))
                    .limit(itemLimit * 20)
            val where = Expression.notIn(Column(BaseColumns._ID), qb.build())
            resolver.delete(uri, where.sql, null)
        }
    }

    fun isFilteringUser(context: Context, userKey: UserKey): Boolean {
        return isFilteringUser(context, userKey.toString())
    }

    fun isFilteringUser(context: Context, userKey: String): Boolean {
        val cr = context.contentResolver
        val where = Expression.equalsArgs(Filters.Users.USER_KEY)
        return cr.queryCount(Filters.Users.CONTENT_URI, where.sql, arrayOf(userKey)) > 0
    }

    private fun <T> getObjectFieldArray(context: Context, uri: Uri, keys: Array<UserKey?>,
            keyField: String, valueFields: Array<String>, sortExpression: OrderBy?, extraWhere: Expression?,
            extraWhereArgs: Array<String>?, createIndices: (Cursor) -> ObjectCursor.CursorIndices<T>,
            createArray: (Int) -> Array<T?>): Array<T?> {
        return getFieldsArray(context, uri, keys, keyField, valueFields, sortExpression,
                extraWhere, extraWhereArgs, object : FieldArrayCreator<Array<T?>, ObjectCursor.CursorIndices<T>> {
            override fun newArray(size: Int): Array<T?> {
                return createArray(size)
            }

            override fun newIndex(cur: Cursor): ObjectCursor.CursorIndices<T> {
                return createIndices(cur)
            }

            override fun assign(array: Array<T?>, arrayIdx: Int, cur: Cursor, colIdx: ObjectCursor.CursorIndices<T>) {
                array[arrayIdx] = colIdx.newObject(cur)
            }
        })
    }

    private fun getStringFieldArray(context: Context, uri: Uri, keys: Array<UserKey?>,
            keyField: String, valueField: String, sortExpression: OrderBy?, extraWhere: Expression?,
            extraWhereArgs: Array<String>?): Array<String?> {
        return getFieldsArray(context, uri, keys, keyField, arrayOf(valueField), sortExpression,
                extraWhere, extraWhereArgs, object : FieldArrayCreator<Array<String?>, Int> {
            override fun newArray(size: Int): Array<String?> {
                return arrayOfNulls(size)
            }

            override fun newIndex(cur: Cursor): Int {
                return cur.getColumnIndex(valueField)
            }

            override fun assign(array: Array<String?>, arrayIdx: Int, cur: Cursor, colIdx: Int) {
                array[arrayIdx] = cur.getString(colIdx)
            }
        })
    }

    private fun getLongFieldArray(context: Context, uri: Uri, keys: Array<UserKey?>,
            keyField: String, valueField: String, sortExpression: OrderBy?, extraWhere: Expression?,
            extraWhereArgs: Array<String>?): LongArray {
        return getFieldsArray(context, uri, keys, keyField, arrayOf(valueField), sortExpression,
                extraWhere, extraWhereArgs, object : FieldArrayCreator<LongArray, Int> {
            override fun newArray(size: Int): LongArray {
                return LongArray(size)
            }

            override fun newIndex(cur: Cursor): Int {
                return cur.getColumnIndex(valueField)
            }

            override fun assign(array: LongArray, arrayIdx: Int, cur: Cursor, colIdx: Int) {
                array[arrayIdx] = cur.getLong(colIdx)
            }
        })
    }

    private fun <T, I> getFieldsArray(
            context: Context, uri: Uri,
            keys: Array<UserKey?>, keyField: String,
            valueFields: Array<String>, sortExpression: OrderBy?,
            extraWhere: Expression?, extraWhereArgs: Array<String>?,
            creator: FieldArrayCreator<T, I>
    ): T {
        val resolver = context.contentResolver
        val resultArray = creator.newArray(keys.size)
        val nonNullKeys = keys.mapNotNull { it?.toString() }.toTypedArray()
        val tableName = getTableNameByUri(uri) ?: throw NullPointerException()
        val having = Expression.inArgs(keyField, nonNullKeys.size)
        val bindingArgs: Array<String>
        if (extraWhereArgs != null) {
            bindingArgs = extraWhereArgs + nonNullKeys
        } else {
            bindingArgs = nonNullKeys
        }
        val builder = SQLQueryBuilder.select(Columns(keyField, *valueFields))
        builder.from(Table(tableName))
        if (extraWhere != null) {
            builder.where(extraWhere)
        }
        builder.groupBy(Column(keyField))
        builder.having(having)
        if (sortExpression != null) {
            builder.orderBy(sortExpression)
        }
        resolver.rawQueryReference(builder.buildSQL(), bindingArgs)?.use { (cur) ->
            cur.moveToFirst()
            val colIdx = creator.newIndex(cur)
            while (!cur.isAfterLast) {
                val keyString = cur.getString(cur.getColumnIndex(keyField))
                if (keyString != null) {
                    val accountKey = UserKey.valueOf(keyString)
                    val arrayIdx = keys.indexOfFirst {
                        accountKey == it
                    }
                    if (arrayIdx >= 0) {
                        creator.assign(resultArray, arrayIdx, cur, colIdx)
                    }
                }
                cur.moveToNext()
            }
        }
        return resultArray
    }

    fun deleteStatus(cr: ContentResolver, accountKey: UserKey,
            statusId: String, status: ParcelableStatus?) {

        val host = accountKey.host
        val deleteWhere: String
        val updateWhere: String
        val deleteWhereArgs: Array<String>
        val updateWhereArgs: Array<String>
        if (host != null) {
            deleteWhere = Expression.and(
                    Expression.likeRaw(Column(Statuses.ACCOUNT_KEY), "'%@'||?"),
                    Expression.or(
                            Expression.equalsArgs(Statuses.ID),
                            Expression.equalsArgs(Statuses.RETWEET_ID)
                    )).sql
            deleteWhereArgs = arrayOf(host, statusId, statusId)
            updateWhere = Expression.and(
                    Expression.likeRaw(Column(Statuses.ACCOUNT_KEY), "'%@'||?"),
                    Expression.equalsArgs(Statuses.MY_RETWEET_ID)
            ).sql
            updateWhereArgs = arrayOf(host, statusId)
        } else {
            deleteWhere = Expression.or(
                    Expression.equalsArgs(Statuses.ID),
                    Expression.equalsArgs(Statuses.RETWEET_ID)
            ).sql
            deleteWhereArgs = arrayOf(statusId, statusId)
            updateWhere = Expression.equalsArgs(Statuses.MY_RETWEET_ID).sql
            updateWhereArgs = arrayOf(statusId)
        }
        for (uri in STATUSES_ACTIVITIES_URIS) {
            cr.delete(uri, deleteWhere, deleteWhereArgs)
            if (status != null) {
                val values = ContentValues()
                values.putNull(Statuses.MY_RETWEET_ID)
                values.put(Statuses.RETWEET_COUNT, status.retweet_count - 1)
                cr.update(uri, values, updateWhere, updateWhereArgs)
            }
        }
    }


    fun prepareDatabase(context: Context) {
        val cr = context.contentResolver
        cr.queryReference(TwidereDataStore.CONTENT_URI_DATABASE_PREPARE, null, null,
                null, null).use {
            // Just try to initialize database
        }
    }

    internal interface FieldArrayCreator<T, I> {
        fun newArray(size: Int): T

        fun newIndex(cur: Cursor): I

        fun assign(array: T, arrayIdx: Int, cur: Cursor, colIdx: I)
    }

    fun getInteractionsCount(context: Context, preferences: SharedPreferences, extraArgs: Bundle?,
            accountKeys: Array<UserKey>, since: Long, sinceColumn: String,
            @FilterScope filterScopes: Int): Int {
        var extraWhere: Expression? = null
        var extraWhereArgs: Array<String>? = null
        var followingOnly = false
        if (extraArgs != null) {
            val extras = extraArgs.getParcelable<TabExtras>(IntentConstants.EXTRA_EXTRAS)
            if (extras is InteractionsTabExtras) {
                if (extras.isMentionsOnly) {
                    extraWhere = Expression.inArgs(Activities.ACTION, 3)
                    extraWhereArgs = arrayOf(Activity.Action.MENTION, Activity.Action.REPLY, Activity.Action.QUOTE)
                }
                if (extras.isMyFollowingOnly) {
                    followingOnly = true
                }
            }
        }
        return getActivitiesCount(context, preferences, Activities.AboutMe.CONTENT_URI, extraWhere,
                extraWhereArgs, sinceColumn, since, followingOnly, accountKeys, filterScopes)
    }

    fun addToFilter(context: Context, users: Collection<ParcelableUser>, filterAnywhere: Boolean) {
        val cr = context.contentResolver

        try {
            val baseCreator = ObjectCursor.valuesCreatorFrom(FiltersData.BaseItem::class.java)
            val userCreator = ObjectCursor.valuesCreatorFrom(FiltersData.UserItem::class.java)
            val userValues = ArrayList<ContentValues>()
            val keywordValues = ArrayList<ContentValues>()
            val linkValues = ArrayList<ContentValues>()
            for (user in users) {
                val userItem = FiltersData.UserItem()
                userItem.userKey = user.key
                userItem.screenName = user.screen_name
                userItem.name = user.name
                userValues.add(userCreator.create(userItem))

                val keywordItem = FiltersData.BaseItem()
                keywordItem.value = "@" + user.screen_name
                keywordItem.userKey = user.key
                keywordValues.add(baseCreator.create(keywordItem))

                // Insert user link (without scheme) to links
                val linkItem = FiltersData.BaseItem()
                val userLink = LinkCreator.getUserWebLink(user)
                val linkWithoutScheme = userLink.toString().substringAfter("://")
                linkItem.value = linkWithoutScheme
                linkItem.userKey = user.key
                linkValues.add(baseCreator.create(linkItem))
            }

            ContentResolverUtils.bulkInsert(cr, Filters.Users.CONTENT_URI, userValues)
            if (filterAnywhere) {
                // Insert to filtered users
                ContentResolverUtils.bulkInsert(cr, Filters.Keywords.CONTENT_URI, keywordValues)
                // Insert user mention to keywords
                ContentResolverUtils.bulkInsert(cr, Filters.Links.CONTENT_URI, linkValues)
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

    }

    fun removeFromFilter(context: Context, users: Collection<ParcelableUser>) {
        val cr = context.contentResolver
        // Delete from filtered users
        val userKeyValues = users.map { it.key.toString() }
        ContentResolverUtils.bulkDelete(cr, Filters.Users.CONTENT_URI, Filters.Users.USER_KEY,
                false, userKeyValues, null, null)
        ContentResolverUtils.bulkDelete(cr, Filters.Keywords.CONTENT_URI, Filters.Keywords.USER_KEY,
                false, userKeyValues, null, null)
        ContentResolverUtils.bulkDelete(cr, Filters.Links.CONTENT_URI, Filters.Links.USER_KEY,
                false, userKeyValues, null, null)
    }

    @WorkerThread
    fun findStatusInDatabases(context: Context,
            accountKey: UserKey,
            statusId: String): ParcelableStatus? {
        val resolver = context.contentResolver
        val where = Expression.and(Expression.equalsArgs(Statuses.ACCOUNT_KEY),
                Expression.equalsArgs(Statuses.ID)).sql
        val whereArgs = arrayOf(accountKey.toString(), statusId)
        for (uri in DataStoreUtils.STATUSES_URIS) {
            val status = resolver.queryOne(uri, Statuses.COLUMNS, where, whereArgs, null,
                    ParcelableStatus::class.java)
            if (status != null) return status
        }
        return null
    }


    @WorkerThread
    @Throws(MicroBlogException::class)
    fun findStatus(context: Context, accountKey: UserKey, statusId: String): ParcelableStatus {
        val cached = findStatusInDatabases(context, accountKey, statusId)
        val profileImageSize = context.getString(R.string.profile_image_size)
        if (cached != null) return cached
        val details = AccountUtils.getAccountDetails(AccountManager.get(context), accountKey,
                true) ?: throw MicroBlogException("No account")
        val status = when (details.type) {
            AccountType.MASTODON -> {
                val mastodon = details.newMicroBlogInstance(context, Mastodon::class.java)
                mastodon.fetchStatus(statusId).toParcelable(details)
            }
            else -> {
                val microBlog = details.newMicroBlogInstance(context, MicroBlog::class.java)
                microBlog.showStatus(statusId).toParcelable(details, profileImageSize)
            }
        }
        val where = Expression.and(Expression.equalsArgs(Statuses.ACCOUNT_KEY),
                Expression.equalsArgs(Statuses.ID)).sql
        val whereArgs = arrayOf(accountKey.toString(), statusId)
        val resolver = context.contentResolver
        resolver.delete(CachedStatuses.CONTENT_URI, where, whereArgs)
        resolver.insert(CachedStatuses.CONTENT_URI, ObjectCursor.valuesCreatorFrom(ParcelableStatus::class.java).create(status))
        return status
    }

    @WorkerThread
    fun findMessageConversation(context: Context, accountKey: UserKey, conversationId: String): ParcelableMessageConversation? {
        val resolver = context.contentResolver
        val where = Expression.and(Expression.equalsArgs(Conversations.ACCOUNT_KEY),
                Expression.equalsArgs(Conversations.CONVERSATION_ID)).sql
        val whereArgs = arrayOf(accountKey.toString(), conversationId)
        return resolver.queryOne(Conversations.CONTENT_URI, Conversations.COLUMNS, where, whereArgs,
                null, ParcelableMessageConversation::class.java)
    }


    private fun getIdsWhere(official: Boolean): Pair<Expression?, Array<String>?> {
        if (official) return Pair(null, null)
        return Pair(Expression.inArgs(Activities.ACTION, Activity.Action.MENTION_ACTIONS.size)
                , Activity.Action.MENTION_ACTIONS)
    }

    private fun <T> getOfficialSeparatedIds(context: Context, getFromDatabase: (Array<UserKey?>, Boolean) -> T,
            mergeResult: (T, T) -> T, accountKeys: Array<UserKey?>): T {
        val officialKeys = Array(accountKeys.size) {
            val key = accountKeys[it] ?: return@Array null
            if (AccountUtils.isOfficial(context, key)) {
                return@Array key
            }
            return@Array null
        }
        val notOfficialKeys = Array(accountKeys.size) {
            val key = accountKeys[it] ?: return@Array null
            if (AccountUtils.isOfficial(context, key)) {
                return@Array null
            }
            return@Array key
        }

        val officialMaxPositions = getFromDatabase(officialKeys, true)
        val notOfficialMaxPositions = getFromDatabase(notOfficialKeys, false)
        return mergeResult(officialMaxPositions, notOfficialMaxPositions)
    }

    private fun UriMatcher.addPath(path: String, code: Int) {
        addURI(TwidereDataStore.AUTHORITY, path, code)
    }
}
