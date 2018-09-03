/*
 *         Twidere - Twitter client for Android
 *
 * Copyright 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mariotaku.microblog.library.api.fanfou;

import org.mariotaku.microblog.library.MicroBlogException;
import org.mariotaku.microblog.library.model.Paging;
import org.mariotaku.microblog.library.model.microblog.ResponseList;
import org.mariotaku.microblog.library.model.microblog.User;
import org.mariotaku.restfu.annotation.method.GET;
import org.mariotaku.restfu.annotation.param.Query;

public interface UsersResources {

    @GET("/users/show.json")
    User showFanfouUser(@Query("id") String userId) throws MicroBlogException;

    @GET("/users/followers.json")
    ResponseList<User> getUsersFollowers(@Query("id") String id, @Query Paging paging) throws MicroBlogException;

    @GET("/users/friends.json")
    ResponseList<User> getUsersFriends(@Query("id") String id, @Query Paging paging) throws MicroBlogException;

}