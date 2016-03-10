package com.stormpath.sdk;

import com.squareup.moshi.Json;
import com.squareup.moshi.Moshi;
import com.stormpath.sdk.models.RegisterParams;
import com.stormpath.sdk.models.SessionTokens;
import com.stormpath.sdk.models.SocialProvidersResponse;
import com.stormpath.sdk.models.StormpathError;
import com.stormpath.sdk.models.UserProfile;
import com.stormpath.sdk.utils.StringUtils;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public class ApiManager {

    public static final Pattern ACCESS_TOKEN_COOKIE_PATTERN = Pattern.compile("access_token=(.*?);.*");

    public static final Pattern REFRESH_TOKEN_COOKIE_PATTERN = Pattern.compile("refresh_token=(.*?);.*");

    private final Platform platform;

    private final OkHttpClient okHttpClient;

    private final Executor callbackExecutor;

    private final StormpathConfiguration config;

    private final PreferenceStore preferenceStore;

    private final Moshi moshi = new Moshi.Builder().build();

    ApiManager(StormpathConfiguration config, Platform platform) {
        this.config = config;
        this.preferenceStore = platform.preferenceStore();
        this.callbackExecutor = platform.callbackExecutor();
        HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
            @Override
            public void log(String message) {
                Stormpath.logger().d(message);
            }
        });
        httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        this.okHttpClient = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(platform.httpExecutorService()))
                .addNetworkInterceptor(httpLoggingInterceptor)
                .build();
        this.platform = platform;
    }

    void login(String username, String password, StormpathCallback<Void> callback) {
        RequestBody formBody = new FormBody.Builder()
                .add("login", username)
                .add("password", password)
                .build();

        Request request = new Request.Builder()
                .url(config.loginUrl())
                .headers(buildStandardHeaders())
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    String sessionTokens[] = parseSessionTokens(response);
                    String accessToken = sessionTokens[0];
                    String refreshToken = sessionTokens[1];

                    if (StringUtils.isBlank(accessToken)) {
                        failureCallback(new RuntimeException("access_token was not found in response. See debug logs for details."));
                        return;
                    }

                    if (StringUtils.isBlank(refreshToken)) {
                        Stormpath.logger().e("There was no refresh_token in the response!");
                    }

                    preferenceStore.setAccessToken(accessToken);
                    preferenceStore.setRefreshToken(refreshToken);
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    public void register(RegisterParams registerParams, StormpathCallback<Void> callback) {
        RequestBody body = RequestBody
                .create(MediaType.parse("application/json"), moshi.adapter(RegisterParams.class).toJson(registerParams));

        Request request = new Request.Builder()
                .url(config.registerUrl())
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    String sessionTokens[] = parseSessionTokens(response);
                    String accessToken = sessionTokens[0];
                    String refreshToken = sessionTokens[1];

                    if (StringUtils.isNotBlank(accessToken)) {
                        preferenceStore.setAccessToken(accessToken);

                        if (StringUtils.isNotBlank(refreshToken)) {
                            preferenceStore.setRefreshToken(refreshToken);
                        }
                    } else {
                        Stormpath.logger().i("There was no access_token in the register cookies, if you want to skip the login after "
                                + "registration, enable the autologin in your Express app.");
                    }
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    public void refreshAccessToken(final StormpathCallback<Void> callback) {
        String refreshToken = preferenceStore.getRefreshToken();

        if (StringUtils.isBlank(refreshToken)) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(new StormpathError(platform.unknownErrorMessage(), new IllegalStateException(
                            "refresh_token was not found, did you forget to login? See debug logs for details.")));
                }
            });
            return;
        }

        RequestBody formBody = new FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build();

        Request request = new Request.Builder()
                .url(config.oauthUrl())
                .headers(buildStandardHeaders())
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    SessionTokens sessionTokens = moshi.adapter(SessionTokens.class).fromJson(response.body().source());
                    if (StringUtils.isBlank(sessionTokens.getAccessToken())) {
                        failureCallback(new RuntimeException("access_token was not found in response. See debug logs for details."));
                        return;
                    }

                    if (StringUtils.isBlank(sessionTokens.getRefreshToken())) {
                        Stormpath.logger().e("There was no refresh_token in the login response!");
                    }

                    preferenceStore.setAccessToken(sessionTokens.getAccessToken());
                    preferenceStore.setRefreshToken(sessionTokens.getRefreshToken());
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    public void getUserProfile(final StormpathCallback<UserProfile> callback) {
        String accessToken = preferenceStore.getAccessToken();

        if (StringUtils.isBlank(accessToken)) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(new StormpathError(platform.unknownErrorMessage(),
                            new IllegalStateException("access_token was not found, did you forget to login? See debug logs for details.")));
                }
            });
            return;
        }

        Request request = new Request.Builder()
                .url(config.userProfileUrl())
                .headers(buildStandardHeaders(accessToken))
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<UserProfile>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<UserProfile> callback) {
                try {
                    UserProfileResponse userProfileResponse = moshi.adapter(UserProfileResponse.class).fromJson(response.body().source());
                    successCallback(userProfileResponse.userProfile);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    public void resetPassword(String email, StormpathCallback<Void> callback) {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{\"email\":\"" + email + "\"}");

        Request request = new Request.Builder()
                .url(config.passwordResetUrl())
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                successCallback(null);
            }
        });
    }

    public void logout() {
        String accessToken = preferenceStore.getAccessToken();

        if (StringUtils.isBlank(accessToken)) {
            Stormpath.logger().e("access_token was not found, did you forget to login? See debug logs for details.");
            return;
        }

        Request request = new Request.Builder()
                .url(config.logoutUrl())
                .headers(buildStandardHeaders(accessToken))
                .get()
                .build();

        preferenceStore.clearAccessToken();
        preferenceStore.clearRefreshToken();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // great - the token was also deleted on the API side
            }

            @Override
            public void onFailure(Call call, IOException e) {
                // this shouldn't happen but if it does we can live with it
            }
        });
    }

    public void verifyEmail(String sptoken, StormpathCallback<Void> callback) {
        HttpUrl url = HttpUrl.parse(config.verifyEmailUrl()).newBuilder()
                .addQueryParameter("sptoken", sptoken)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .headers(buildStandardHeaders())
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                successCallback(null);
            }
        });
    }

    public void resendVerificationEmail(String email, StormpathCallback<Void> callback) {
        FormBody body = new FormBody.Builder()
                .add("login", email)
                .build();

        Request request = new Request.Builder()
                .url(config.verifyEmailUrl())
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                successCallback(null);
            }
        });
    }

    public void getSocialProviders(final StormpathCallback<SocialProvidersResponse> callback) {
        String accessToken = preferenceStore.getAccessToken();

        Request request = new Request.Builder()
                .url(config.socialProvidersUrl())
                .headers(buildStandardHeaders(accessToken))
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<SocialProvidersResponse>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<SocialProvidersResponse> callback) {
                try {
                    SocialProvidersResponse socialProvidersResponse = moshi.adapter(SocialProvidersResponse.class)
                            .fromJson(response.body().source());
                    successCallback(socialProvidersResponse);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    void socialLogin(String providerId, String accessToken, String code, StormpathCallback<Void> callback) {
        SocialLoginRequest socialLoginRequest = new SocialLoginRequest(providerId, accessToken, code);
        String bodyJson = moshi.adapter(SocialLoginRequest.class).toJson(socialLoginRequest);
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), bodyJson);

        Request request = new Request.Builder()
                .url(config.loginUrl())
                .headers(buildStandardHeaders())
                .post(body)
                .build();

        okHttpClient.newCall(request).enqueue(new OkHttpCallback<Void>(callback) {
            @Override
            protected void onSuccess(Response response, StormpathCallback<Void> callback) {
                try {
                    String sessionTokens[] = parseSessionTokens(response);
                    String accessToken = sessionTokens[0];
                    String refreshToken = sessionTokens[1];

                    if (StringUtils.isBlank(accessToken)) {
                        failureCallback(new RuntimeException("access_token was not found in response. See debug logs for details."));
                        return;
                    }

                    if (StringUtils.isBlank(refreshToken)) {
                        Stormpath.logger().e("There was no refresh_token in the response!");
                    }

                    preferenceStore.setAccessToken(accessToken);
                    preferenceStore.setRefreshToken(refreshToken);
                    successCallback(null);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        });
    }

    private String[] parseSessionTokens(Response response) {
        List<String> cookies = response.headers("Set-Cookie");

        String accessToken = null;
        String refreshToken = null;

        for (String cookieHeader : cookies) {
            Matcher accessTokenMatcher = ACCESS_TOKEN_COOKIE_PATTERN.matcher(cookieHeader);
            if (accessTokenMatcher.find()) {
                accessToken = accessTokenMatcher.group(1);
            }

            Matcher refreshTokenMatcher = REFRESH_TOKEN_COOKIE_PATTERN.matcher(cookieHeader);
            if (refreshTokenMatcher.find()) {
                refreshToken = refreshTokenMatcher.group(1);
            }
        }

        return new String[]{accessToken, refreshToken};
    }

    private Headers buildStandardHeaders() {
        return buildStandardHeaders(null);
    }

    private Headers buildStandardHeaders(String accessToken) {
        Headers.Builder builder = new Headers.Builder();
        builder.add("Accept", "application/json");
        if (StringUtils.isNotBlank(accessToken)) {
            builder.add("Authorization", "Bearer " + accessToken);
        }
        return builder.build();
    }

    private abstract class OkHttpCallback<T> implements Callback {

        private StormpathCallback<T> stormpathCallback;

        public OkHttpCallback(StormpathCallback<T> stormpathCallback) {
            this.stormpathCallback = stormpathCallback;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            if (e instanceof UnknownHostException || e instanceof SocketTimeoutException || e instanceof SocketException) {
                failureCallback(new StormpathError(platform.networkErrorMessage(), e));
            } else {
                failureCallback(e);
            }
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if (response.isSuccessful()) {
                onSuccess(response, stormpathCallback);
            } else {
                try {
                    StormpathError error = moshi.adapter(StormpathError.class).fromJson(response.body().source());
                    failureCallback(error);
                } catch (Throwable t) {
                    failureCallback(t);
                }
            }
        }

        protected abstract void onSuccess(Response response, StormpathCallback<T> callback);

        void successCallback(final T t) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    stormpathCallback.onSuccess(t);
                }
            });
        }

        void failureCallback(final Throwable t) {
            failureCallback(new StormpathError(platform.unknownErrorMessage(), t));
        }

        void failureCallback(final StormpathError error) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    stormpathCallback.onFailure(error);
                }
            });
        }
    }

    private static class UserProfileResponse implements Serializable {

        @Json(name = "account")
        private UserProfile userProfile;
    }

    private static class SocialLoginRequest implements Serializable {

        @Json(name = "providerData")
        private Map<String, String> providerData = new LinkedHashMap<>();

        public SocialLoginRequest(String providerId, String accessToken, String code) {
            providerData.put("providerId", providerId);
            if (StringUtils.isNotBlank(accessToken)) {
                providerData.put("accessToken", accessToken);
            }
            if (StringUtils.isNotBlank(code)) {
                providerData.put("code", code);
            }
        }
    }
}
