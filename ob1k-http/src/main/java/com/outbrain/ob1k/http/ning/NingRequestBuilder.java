package com.outbrain.ob1k.http.ning;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.outbrain.ob1k.concurrent.ComposableFutures.fromError;
import static com.outbrain.ob1k.concurrent.ComposableFutures.fromValue;
import static com.outbrain.ob1k.http.utils.ComposableFutureAdapter.fromListenableFuture;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.outbrain.ob1k.concurrent.ComposableFuture;
import com.outbrain.ob1k.http.RequestBuilder;
import com.outbrain.ob1k.http.Response;
import com.outbrain.ob1k.http.common.ContentType;
import com.outbrain.ob1k.http.TypedResponse;
import com.outbrain.ob1k.http.common.Cookie;
import com.outbrain.ob1k.http.common.Header;
import com.outbrain.ob1k.http.common.Param;
import com.outbrain.ob1k.http.marshalling.MarshallingStrategy;
import com.outbrain.ob1k.http.utils.ComposableFutureAdapter.Provider;
import com.outbrain.ob1k.http.utils.UrlUtils;
import org.apache.commons.codec.EncoderException;
import rx.Observable;
import rx.subjects.PublishSubject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * @author marenzon
 */
public class NingRequestBuilder implements RequestBuilder {

  private final AsyncHttpClient asyncHttpClient;
  private final AsyncHttpClient.BoundRequestBuilder ningRequestBuilder;

  private MarshallingStrategy marshallingStrategy;
  private String requestUrl;
  private long responseMaxSize;
  private String charset = DEFAULT_CHARSET;
  private String bodyString;
  private byte[] bodyByteArray;
  private Object bodyObject;

  public NingRequestBuilder(final AsyncHttpClient asyncHttpClient, final AsyncHttpClient.BoundRequestBuilder ningRequestBuilder,
                            final String requestUrl, final long responseMaxSize, final MarshallingStrategy marshallingStrategy) {

    this.asyncHttpClient = checkNotNull(asyncHttpClient, "asyncHttpClient may not be null");
    this.ningRequestBuilder = checkNotNull(ningRequestBuilder, "ningRequestBuilder may not be null");
    this.requestUrl = checkNotNull(requestUrl, "requestUrl may not be null");
    this.responseMaxSize = responseMaxSize;
    this.marshallingStrategy = marshallingStrategy;
  }

  @Override
  public RequestBuilder setContentType(final ContentType contentType) {

    return setContentType(contentType.requestEncoding());
  }

  @Override
  public RequestBuilder setContentType(final String contentType) {

    ningRequestBuilder.setHeader(CONTENT_TYPE_HEADER, contentType);
    return this;
  }

  @Override
  public RequestBuilder setPathParam(final String param, final String value) throws EncoderException {

    requestUrl = UrlUtils.replacePathParam(requestUrl, param, value);
    ningRequestBuilder.setUrl(requestUrl);
    return this;
  }

  @Override
  public RequestBuilder setPathParam(final Param param) throws EncoderException {

    return setPathParam(param.getName(), param.getValue());
  }

  @Override
  public RequestBuilder setPathParams(final List<Param> params) throws EncoderException {

    for (final Param param : params) {
      setPathParam(param);
    }

    return this;
  }

  @Override
  public RequestBuilder setUserAgent(final String userAgent) {

    return addHeader(USER_AGENT_HEADER, userAgent);
  }

  @Override
  public RequestBuilder addHeader(final String name, final String value) {

    ningRequestBuilder.addHeader(name, value);
    return this;
  }

  @Override
  public RequestBuilder addHeader(final Header header) {

    return addHeader(header.getName(), header.getValue());
  }

  @Override
  public RequestBuilder addHeaders(final List<Header> headers) {

    headers.forEach(this::addHeader);
    return this;
  }

  @Override
  public RequestBuilder setBody(final String body) {

    this.bodyString = body;
    return this;
  }

  @Override
  public RequestBuilder setBody(final byte[] body) {

    this.bodyByteArray = body;
    return this;
  }

  @Override
  public RequestBuilder setBody(final Object body) {

    this.bodyObject = body;
    return this;
  }

  @Override
  public RequestBuilder setBodyEncoding(final String charset) {

    this.charset = charset;
    return this;
  }

  @Override
  public RequestBuilder withBasicAuth(final String username, final String password) {

    final Realm realm = new Realm.RealmBuilder().
      setPrincipal(username).
      setPassword(password).
      setUsePreemptiveAuth(true).
      setScheme(Realm.AuthScheme.BASIC).
      build();

    ningRequestBuilder.setRealm(realm);
    return this;
  }

  @Override
  public RequestBuilder addQueryParam(final String name, final String value) {

    ningRequestBuilder.addQueryParam(name, value);
    return this;
  }

  @Override
  public RequestBuilder addQueryParams(final Map<String, String> params) {

    for (final Map.Entry<String, String> param : params.entrySet()) {
      addQueryParam(param.getKey(), param.getValue());
    }

    return this;
  }

  @Override
  public RequestBuilder addQueryParam(final Param param) {

    return addQueryParam(param.getName(), param.getValue());
  }

  @Override
  public RequestBuilder addQueryParams(final List<Param> params) {

    params.forEach(this::addQueryParam);
    return this;
  }

  @Override
  public RequestBuilder setResponseMaxSize(final long responseMaxSize) {

    this.responseMaxSize = responseMaxSize;
    return this;
  }

  @Override
  public RequestBuilder addCookie(final Cookie cookie) {

    final com.ning.http.client.cookie.Cookie ningCookie = transformToNingCookie(cookie);
    ningRequestBuilder.addCookie(ningCookie);
    return this;
  }

  @Override
  public RequestBuilder setRequestTimeout(final int requestTimeout) {

    ningRequestBuilder.setRequestTimeout(requestTimeout);
    return this;
  }

  @Override
  public RequestBuilder setMarshallingStrategy(final MarshallingStrategy marshallingStrategy) {

    this.marshallingStrategy = checkNotNull(marshallingStrategy, "unmarshallingStrategy may not be null");
    return this;
  }

  @Override
  public ComposableFuture<Response> asResponse() {

    try {
      prepareRequestBody();
    } catch (final IOException e) {
      return fromError(e);
    }

    final ComposableFuture<com.ning.http.client.Response> responseFuture = executeAndTransformRequest();

    return responseFuture.flatMap(ningResponse -> {
      try {
        final Response response = new NingResponse<>(ningResponse, null, null);
        return fromValue(response);
      } catch (final IOException e) {
        return fromError(e);
      }
    });
  }

  @Override
  public Observable<Response> asStream() {

    try {
      prepareRequestBody();
    } catch (final IOException e) {
      return Observable.error(e);
    }

    setRequestTimeout(-1);

    final PublishSubject<Response> result = PublishSubject.create();
    final NingHttpStreamHandler handler = new NingHttpStreamHandler(responseMaxSize, result);

    ningRequestBuilder.execute(handler);
    return result;
  }

  @Override
  public <T> ComposableFuture<TypedResponse<T>> asTypedResponse(final Class<T> type) {

    return asTypedResponse((Type) type);
  }

  @Override
  public <T> Observable<TypedResponse<T>> asTypedStream(final Class<T> type) {

    return asTypedStream((Type) type);
  }

  @Override
  public <T> ComposableFuture<TypedResponse<T>> asTypedResponse(final Type type) {

    final ComposableFuture<com.ning.http.client.Response> responseFuture = executeAndTransformRequest();

    return responseFuture.flatMap(ningResponse -> {
      try {
        final TypedResponse<T> response = new NingResponse<>(ningResponse, type, marshallingStrategy);
        return fromValue(response);
      } catch (final IOException e) {
        return fromError(e);
      }
    });
  }

  @Override
  public <T> Observable<TypedResponse<T>> asTypedStream(final Type type) {

    try {
      prepareRequestBody();
    } catch (final IOException e) {
      return Observable.error(e);
    }

    setRequestTimeout(-1);

    final PublishSubject<TypedResponse<T>> result = PublishSubject.create();
    final NingHttpTypedStreamHandler<T> handler = new NingHttpTypedStreamHandler<>(responseMaxSize, result,
            marshallingStrategy, type);

    ningRequestBuilder.execute(handler);
    return result;
  }

  @Override
  public <T> ComposableFuture<T> asValue(final Class<T> type) {

    return asValue((Type) type);
  }

  @Override
  public <T> ComposableFuture<T> asValue(final Type type) {

    final ComposableFuture<TypedResponse<T>> responseFuture = asTypedResponse(type);

    return responseFuture.flatMap(typedResponse -> {
      try {
        return fromValue(typedResponse.getTypedBody());
      } catch (final IOException e) {
        return fromError(e);
      }
    });
  }

  @Override
  public <T> Observable<T> asStreamValue(final Class<T> type) {

    return asStreamValue((Type) type);
  }

  @Override
  public <T> Observable<T> asStreamValue(final Type type) {

    final Observable<TypedResponse<T>> responseObservable = asTypedStream(type);

    return responseObservable.flatMap(typedResponse -> {
      try {
        return Observable.just(typedResponse.getTypedBody());
      } catch (final IOException e) {
        return Observable.error(e);
      }
    });
  }

  private ComposableFuture<com.ning.http.client.Response> executeAndTransformRequest() {

    try {
      prepareRequestBody();
    } catch (final IOException e) {
      return fromError(e);
    }

    final Request ningRequest = ningRequestBuilder.build();
    final Provider<com.ning.http.client.Response> provider = new Provider<com.ning.http.client.Response>() {
      private boolean aborted = false;
      private long size;

      @Override
      public ListenableFuture<com.ning.http.client.Response> provide() {
        return asyncHttpClient.executeRequest(ningRequest, new AsyncCompletionHandler<com.ning.http.client.Response>() {
          @Override
          public com.ning.http.client.Response onCompleted(final com.ning.http.client.Response response) throws Exception {
            if (aborted) {
              throw new RuntimeException("Response size is bigger than the limit: " + responseMaxSize);
            }
            return response;
          }

          @Override
          public STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
            if (responseMaxSize > 0) {
              size += content.length();
              if (size > responseMaxSize) {
                aborted = true;
                return STATE.ABORT;
              }
            }
            return super.onBodyPartReceived(content);
          }
        });
      }
    };

    return fromListenableFuture(provider);
  }

  private com.ning.http.client.cookie.Cookie transformToNingCookie(final Cookie cookie) {

    return com.ning.http.client.cookie.Cookie.newValidCookie(cookie.getName(), cookie.getValue(), false,
      cookie.getDomain(), cookie.getPath(), cookie.getMaxAge(),
      cookie.isSecure(), cookie.isHttpOnly());
  }

  /**
   * Prepares the request body - setting the body, charset and
   * content length by body type
   *
   * @throws IOException
   */
  private void prepareRequestBody() throws IOException {

    if (bodyByteArray != null) {
      setByteArrayBody();
    } else if (bodyString != null) {
      setStringBody();
    } else if (bodyObject != null) {
      setTypedBody();
    }
  }

  private void setTypedBody() throws IOException {

    final byte[] body = marshallingStrategy.marshall(bodyObject);
    ningRequestBuilder.setBody(body);
    ningRequestBuilder.setContentLength(body.length);
    ningRequestBuilder.setBodyEncoding(charset);
  }

  private void setStringBody() throws UnsupportedEncodingException {

    ningRequestBuilder.setBody(bodyString);
    ningRequestBuilder.setContentLength(bodyString.getBytes(charset).length);
    ningRequestBuilder.setBodyEncoding(charset);
  }

  private void setByteArrayBody() {

    ningRequestBuilder.setBody(bodyByteArray);
    ningRequestBuilder.setContentLength(bodyByteArray.length);
    ningRequestBuilder.setBodyEncoding(charset);
  }
}