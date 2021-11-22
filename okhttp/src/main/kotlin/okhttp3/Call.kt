/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import java.io.IOException
import okio.Timeout

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 * 请求调用接口，表示这个请求已经准备好可以执行，可以被取消。
 * 表示单个请求/响应对(流)对象，只能执行一次。
 */
interface Call : Cloneable {
  /** Returns the original request that initiated this call.
   *  返回发起此调用的原始请求。
   */
  fun request(): Request

  /**
   * 同步请求，立即执行。
   * Invokes the request immediately, and blocks until the response can be processed or is in error.
   *
   * To avoid leaking resources callers should close the [Response] which in turn will close the
   * underlying [ResponseBody].
   *
   * ```java
   * // ensure the response (and underlying response body) is closed
   * try (Response response = client.newCall(request).execute()) {
   *   ...
   * }
   * ```
   *
   * The caller may read the response body with the response's [Response.body] method. To avoid
   * leaking resources callers must [close the response body][ResponseBody] or the response.
   *
   * Note that transport-layer success (receiving a HTTP response code, headers and body) does not
   * necessarily indicate application-layer success: `response` may still indicate an unhappy HTTP
   * response code like 404 or 500.
   *
   * 抛出两种异常：请求失败抛出IOException，如果在执行过一回的前提下再次执行抛出IllegalStateException；
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   *     problem or timeout. Because networks can fail during an exchange, it is possible that the
   *     remote server accepted the request before the failure.
   * @throws IllegalStateException when the call has already been executed.
   */
  @Throws(IOException::class)
  fun execute(): Response

  /**
   * 异步请求，将请求安排在将来的某个时间点执行。
   * Schedules the request to be executed at some point in the future.
   *
   * The [dispatcher][OkHttpClient.dispatcher] defines when the request will run: usually
   * immediately unless there are several other requests currently being executed.
   *
   * This client will later call back `responseCallback` with either an HTTP response or a failure
   * exception.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  fun enqueue(responseCallback: Callback)

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled.
   *
   * 取消请求。已经完成的请求不能被取消。
   */
  fun cancel()

  /**
   * Returns true if this call has been either [executed][execute] or [enqueued][enqueue]. It is an
   * error to execute a call more than once.
   * 是否被执行
   */
  fun isExecuted(): Boolean

  /**
   * 是否被取消
   */
  fun isCanceled(): Boolean

  /**
   * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
   * body, server processing, and reading the response body. If the call requires redirects or
   * retries all must complete within one timeout period.
   *
   * 一个完整Call请求流程的超时时间配置，默认选自[OkHttpClient.Builder.callTimeout]
   * Configure the client's default timeout with [OkHttpClient.Builder.callTimeout].
   */
  fun timeout(): Timeout

  /**
   * Create a new, identical call to this one which can be enqueued or executed even if this call
   * has already been.
   * 克隆这个call，创建一个新的，相同的Call.
   */
  public override fun clone(): Call

  /**
   * 利用工厂模式来创建Call对象
   */
  fun interface Factory {
    fun newCall(request: Request): Call
  }
}
