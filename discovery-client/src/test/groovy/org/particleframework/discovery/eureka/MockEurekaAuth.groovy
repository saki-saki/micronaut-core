/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.discovery.eureka

import org.particleframework.context.annotation.Requires
import org.particleframework.context.annotation.Value
import org.particleframework.core.async.publisher.Publishers
import org.particleframework.http.HttpHeaders
import org.particleframework.http.HttpRequest
import org.particleframework.http.HttpResponse
import org.particleframework.http.HttpStatus
import org.particleframework.http.MutableHttpResponse
import org.particleframework.http.annotation.Filter
import org.particleframework.http.filter.HttpServerFilter
import org.particleframework.http.filter.ServerFilterChain
import org.reactivestreams.Publisher

/**
 * @author graemerocher
 * @since 1.0
 */
@Filter('/eureka/**')
@Requires(property = 'test.eureka.userinfo')
class MockEurekaAuth implements HttpServerFilter{
    final String userInfo

    MockEurekaAuth(@Value('${test.eureka.userinfo}') String userInfo) {
        this.userInfo = userInfo
    }

    @Override
    Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        def authToken = request.getHeaders().get(HttpHeaders.AUTHORIZATION, String)
        if(authToken.isPresent()) {
            def token = authToken.get().substring("Basic ".length())
            def val = new String(Base64.decoder.decode(token))
            if(val.contains(userInfo)) {
                return chain.proceed(request)
            }
        }
        return Publishers.just(HttpResponse.status(HttpStatus.FORBIDDEN))
    }
}
