/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.http2.ConnectionShutdownException
import okio.buffer

/** This is the last interceptor in the chain. It makes a network call to the server. */
class CallServerInterceptor(private val forWebSocket: Boolean) : Interceptor {

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    val exchange = realChain.exchange!!
    val request = realChain.request
    val requestBody = request.body
    val sentRequestMillis = System.currentTimeMillis()

    var invokeStartEvent = true
    var responseBuilder: Response.Builder? = null
    var sendRequestException: IOException? = null
    try {
      //写入请求头
      exchange.writeRequestHeaders(request)

      //POST请求，先发送请求头，在获取到100继续状态后继续发送请求体
      //如果不是GET请求，并且请求体不为空
      if (HttpMethod.permitsRequestBody(request.method) && requestBody != null) {
        // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
        // Continue" response before transmitting the request body. If we don't get that, return
        // what we did get (such as a 4xx response) without ever transmitting the request body.
        //当请求头为"Expect: 100-continue"时，在发送请求体之前需要等待服务器返回"HTTP/1.1 100 Continue" 的response,
        //如果没有等到该response，就不发送请求体。
        if ("100-continue".equals(request.header("Expect"), ignoreCase = true)) {
          //刷新请求，即发送请求头
          exchange.flushRequest()
          //解析响应头
          responseBuilder = exchange.readResponseHeaders(expectContinue = true)
          exchange.responseHeadersStart()
          invokeStartEvent = false
        }
        //写入请求体
        if (responseBuilder == null) {
          if (requestBody.isDuplex()) {
            //如果请求体是双公体，就先发送请求头，稍后在发送请求体
            // Prepare a duplex body so that the application can send a request body later.
            exchange.flushRequest()
            val bufferedRequestBody = exchange.createRequestBody(request, true).buffer()
            //写入请求体
            requestBody.writeTo(bufferedRequestBody)
          } else {
            // Write the request body if the "Expect: 100-continue" expectation was met.
            //如果获取到了"Expect: 100-continue"响应，写入请求体
            val bufferedRequestBody = exchange.createRequestBody(request, false).buffer()
            requestBody.writeTo(bufferedRequestBody)
            bufferedRequestBody.close()
          }
        } else {
          exchange.noRequestBody()
          if (!exchange.connection.isMultiplexed) {
            // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
            // from being reused. Otherwise we're still obligated to transmit the request body to
            // leave the connection in a consistent state.
            exchange.noNewExchangesOnConnection()
          }
        }
      } else {
        exchange.noRequestBody()
      }

      if (requestBody == null || !requestBody.isDuplex()) {
        //请求结束，发送请求体
        exchange.finishRequest()
      }
    } catch (e: IOException) {
      if (e is ConnectionShutdownException) {
        throw e // No request was sent so there's no response to read.
      }
      if (!exchange.hasFailure) {
        throw e // Don't attempt to read the response; we failed to send the request.
      }
      sendRequestException = e
    }

    try {
      if (responseBuilder == null) {
        //读取响应头
        responseBuilder = exchange.readResponseHeaders(expectContinue = false)!!
        if (invokeStartEvent) {
          exchange.responseHeadersStart()
          invokeStartEvent = false
        }
      }
      //构建一个response
      var response = responseBuilder
          .request(request)
          .handshake(exchange.connection.handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build()
      var code = response.code
      if (code == 100) {
        // Server sent a 100-continue even though we did not request one. Try again to read the
        // actual response status.
        //服务器发送了一个100-continue，即使我们没有发送请求，再次尝试读取实际的响应状态。
        responseBuilder = exchange.readResponseHeaders(expectContinue = false)!!
        if (invokeStartEvent) {
          exchange.responseHeadersStart()
        }
        response = responseBuilder
            .request(request)
            .handshake(exchange.connection.handshake())
            .sentRequestAtMillis(sentRequestMillis)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
        code = response.code
      }

      exchange.responseHeadersEnd(response)

      response = if (forWebSocket && code == 101) {
        // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
        //连接正在升级，但我们需要确保拦截器看到一个非空响应体。
        response.newBuilder()
            .body(EMPTY_RESPONSE)
            .build()
      } else {
        response.newBuilder()
            .body(exchange.openResponseBody(response))
            .build()
      }
      if ("close".equals(response.request.header("Connection"), ignoreCase = true) ||
          "close".equals(response.header("Connection"), ignoreCase = true)) {
        exchange.noNewExchangesOnConnection()
      }
      //如果response code 为204 205
      if ((code == 204 || code == 205) && (response.body?.contentLength() ?: -1L) > 0L) {
        throw ProtocolException(
            "HTTP $code had non-zero Content-Length: ${response.body?.contentLength()}")
      }
      return response
    } catch (e: IOException) {
      if (sendRequestException != null) {
        sendRequestException.addSuppressed(e)
        throw sendRequestException
      }
      throw e
    }
  }
}
