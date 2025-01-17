/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.cache

import java.io.IOException
import java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.util.concurrent.TimeUnit.MILLISECONDS
import okhttp3.Cache
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.RealCall
import okhttp3.internal.discard
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.http.RealResponseBody
import okhttp3.internal.http.promisesBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

/** Serves requests from the cache and writes responses to the cache.
 *
 * cache为客户设置的缓存，存在OkHttpClient.cache，默认为null
 *
 * */
class CacheInterceptor(internal val cache: Cache?) : Interceptor {

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val call = chain.call()
    //通过request从OkHttpClient.cache中获取缓存
    val cacheCandidate = cache?.get(chain.request())

    val now = System.currentTimeMillis()
    //创建一个缓存策略，用来确定怎么使用缓存
    val strategy = CacheStrategy.Factory(now, chain.request(), cacheCandidate).compute()
    //为空表示不使用网络，反之，则表示使用网络
    val networkRequest = strategy.networkRequest
    //为空表示不使用缓存，反之，则表示使用缓存
    val cacheResponse = strategy.cacheResponse
    //追踪网络与缓存的使用情况
    cache?.trackResponse(strategy)
    val listener = (call as? RealCall)?.eventListener ?: EventListener.NONE
    //有缓存但不适用，关闭它
    if (cacheCandidate != null && cacheResponse == null) {
      // The cache candidate wasn't applicable. Close it.
      cacheCandidate.body?.closeQuietly()
    }

    // If we're forbidden from using the network and the cache is insufficient, fail.
    //如果网络被禁止，但是缓存又是空的，构建一个code为504的response，并返回
    if (networkRequest == null && cacheResponse == null) {
      return Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(HTTP_GATEWAY_TIMEOUT)
          .message("Unsatisfiable Request (only-if-cached)")
          .body(EMPTY_RESPONSE)
          .sentRequestAtMillis(-1L)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build().also {
            listener.satisfactionFailure(call, it)
          }
    }

    // If we don't need the network, we're done.
    //如果我们禁用了网络不使用网络，且有缓存，直接根据缓存内容构建并返回response
    if (networkRequest == null) {
      return cacheResponse!!.newBuilder()
          .cacheResponse(stripBody(cacheResponse))
          .build().also {
            listener.cacheHit(call, it)
          }
    }
    //为缓存添加监听
    if (cacheResponse != null) {
      listener.cacheConditionalHit(call, cacheResponse)
    } else if (cache != null) {
      listener.cacheMiss(call)
    }

    var networkResponse: Response? = null
    try {
      //责任链往下处理，从服务器返回response 赋值给 networkResponse
      networkResponse = chain.proceed(networkRequest)
    } finally {
      // If we're crashing on I/O or otherwise, don't leak the cache body.
      //捕获I/O或其他异常，请求失败，networkResponse为空，且有缓存的时候，不暴露缓存内容。
      if (networkResponse == null && cacheCandidate != null) {
        cacheCandidate.body?.closeQuietly()
      }
    }

    // If we have a cache response too, then we're doing a conditional get.
    //如果有缓存
    if (cacheResponse != null) {
      //且网络返回response code为304的时候，使用缓存内容新构建一个Response返回。
      if (networkResponse?.code == HTTP_NOT_MODIFIED) {
        val response = cacheResponse.newBuilder()
            .headers(combine(cacheResponse.headers, networkResponse.headers))
            .sentRequestAtMillis(networkResponse.sentRequestAtMillis)
            .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis)
            .cacheResponse(stripBody(cacheResponse))
            .networkResponse(stripBody(networkResponse))
            .build()

        networkResponse.body!!.close()

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        cache!!.trackConditionalCacheHit()
        cache.update(cacheResponse, response)
        return response.also {
          listener.cacheHit(call, it)
        }
      } else {
        //否则关闭缓存响应体
        cacheResponse.body?.closeQuietly()
      }
    }

    //构建网络请求的response
    val response = networkResponse!!.newBuilder()
        .cacheResponse(stripBody(cacheResponse))
        .networkResponse(stripBody(networkResponse))
        .build()

    //如果cache不为null，即用户在OkHttpClient中配置了缓存，则将上一步新构建的网络请求response存到cache中
    if (cache != null) {
      //根据response的code,header以及CacheControl.noStore来判断是否可以缓存
      if (response.promisesBody() && CacheStrategy.isCacheable(response, networkRequest)) {
        // Offer this request to the cache.
          // 将该response存入缓存
        val cacheRequest = cache.put(response)
        return cacheWritingResponse(cacheRequest, response).also {
          if (cacheResponse != null) {
            // This will log a conditional cache miss only.
            listener.cacheMiss(call)
          }
        }
      }
      //根据请求方法来判断缓存是否有效，只对Get请求进行缓存，其它方法的请求则移除
      if (HttpMethod.invalidatesCache(networkRequest.method)) {
        try {
          //缓存无效，将该请求缓存从client缓存配置中移除
          cache.remove(networkRequest)
        } catch (_: IOException) {
          // The cache cannot be written.
        }
      }
    }

    return response
  }

  /**
   * Returns a new source that writes bytes to [cacheRequest] as they are read by the source
   * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
   * may never exhaust the source stream and therefore not complete the cached response.
   */
  @Throws(IOException::class)
  private fun cacheWritingResponse(cacheRequest: CacheRequest?, response: Response): Response {
    // Some apps return a null body; for compatibility we treat that like a null cache request.
    if (cacheRequest == null) return response
    val cacheBodyUnbuffered = cacheRequest.body()

    val source = response.body!!.source()
    val cacheBody = cacheBodyUnbuffered.buffer()

    val cacheWritingSource = object : Source {
      private var cacheRequestClosed = false

      @Throws(IOException::class)
      override fun read(sink: Buffer, byteCount: Long): Long {
        val bytesRead: Long
        try {
          bytesRead = source.read(sink, byteCount)
        } catch (e: IOException) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true
            cacheRequest.abort() // Failed to write a complete cache response.
          }
          throw e
        }

        if (bytesRead == -1L) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true
            cacheBody.close() // The cache response is complete!
          }
          return -1
        }

        sink.copyTo(cacheBody.buffer, sink.size - bytesRead, bytesRead)
        cacheBody.emitCompleteSegments()
        return bytesRead
      }

      override fun timeout(): Timeout = source.timeout()

      @Throws(IOException::class)
      override fun close() {
        if (!cacheRequestClosed &&
            !discard(ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
          cacheRequestClosed = true
          cacheRequest.abort()
        }
        source.close()
      }
    }

    val contentType = response.header("Content-Type")
    val contentLength = response.body.contentLength()
    return response.newBuilder()
        .body(RealResponseBody(contentType, contentLength, cacheWritingSource.buffer()))
        .build()
  }

  companion object {

    private fun stripBody(response: Response?): Response? {
      return if (response?.body != null) {
        response.newBuilder().body(null).build()
      } else {
        response
      }
    }

    /** Combines cached headers with a network headers as defined by RFC 7234, 4.3.4. */
    private fun combine(cachedHeaders: Headers, networkHeaders: Headers): Headers {
      val result = Headers.Builder()

      for (index in 0 until cachedHeaders.size) {
        val fieldName = cachedHeaders.name(index)
        val value = cachedHeaders.value(index)
        if ("Warning".equals(fieldName, ignoreCase = true) && value.startsWith("1")) {
          // Drop 100-level freshness warnings.
          continue
        }
        if (isContentSpecificHeader(fieldName) ||
            !isEndToEnd(fieldName) ||
            networkHeaders[fieldName] == null) {
          result.addLenient(fieldName, value)
        }
      }

      for (index in 0 until networkHeaders.size) {
        val fieldName = networkHeaders.name(index)
        if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
          result.addLenient(fieldName, networkHeaders.value(index))
        }
      }

      return result.build()
    }

    /**
     * Returns true if [fieldName] is an end-to-end HTTP header, as defined by RFC 2616,
     * 13.5.1.
     */
    private fun isEndToEnd(fieldName: String): Boolean {
      return !"Connection".equals(fieldName, ignoreCase = true) &&
          !"Keep-Alive".equals(fieldName, ignoreCase = true) &&
          !"Proxy-Authenticate".equals(fieldName, ignoreCase = true) &&
          !"Proxy-Authorization".equals(fieldName, ignoreCase = true) &&
          !"TE".equals(fieldName, ignoreCase = true) &&
          !"Trailers".equals(fieldName, ignoreCase = true) &&
          !"Transfer-Encoding".equals(fieldName, ignoreCase = true) &&
          !"Upgrade".equals(fieldName, ignoreCase = true)
    }

    /**
     * Returns true if [fieldName] is content specific and therefore should always be used
     * from cached headers.
     */
    private fun isContentSpecificHeader(fieldName: String): Boolean {
      return "Content-Length".equals(fieldName, ignoreCase = true) ||
          "Content-Encoding".equals(fieldName, ignoreCase = true) ||
          "Content-Type".equals(fieldName, ignoreCase = true)
    }
  }
}
