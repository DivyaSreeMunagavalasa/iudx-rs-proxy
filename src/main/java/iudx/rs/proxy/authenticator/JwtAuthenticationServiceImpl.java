package iudx.rs.proxy.authenticator;

import static iudx.rs.proxy.authenticator.Constants.*;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import iudx.rs.proxy.apiserver.service.CatalogueService;
import iudx.rs.proxy.authenticator.authorization.AuthorizationContextFactory;
import iudx.rs.proxy.authenticator.authorization.AuthorizationRequest;
import iudx.rs.proxy.authenticator.authorization.AuthorizationStrategy;
import iudx.rs.proxy.authenticator.authorization.IudxRole;
import iudx.rs.proxy.authenticator.authorization.JwtAuthorization;
import iudx.rs.proxy.authenticator.authorization.Method;
import iudx.rs.proxy.authenticator.model.JwtData;
import iudx.rs.proxy.cache.CacheService;
import iudx.rs.proxy.cache.cacheImpl.CacheType;
import iudx.rs.proxy.common.Api;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JwtAuthenticationServiceImpl implements AuthenticationService {

  private static final Logger LOGGER = LogManager.getLogger(JwtAuthenticationServiceImpl.class);
  static WebClient catWebClient;
  final JWTAuth jwtAuth;
  final String host;
  final int port;
  final String path;
  final String audience;
  final CacheService cache;
  final Api apis;
  final String catBasePath;
  // resourceGroupCache will contain ACL info about all resource group in a resource server
  Cache<String, String> resourceGroupCache = CacheBuilder.newBuilder().maximumSize(1000)
      .expireAfterAccess(Constants.CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES).build();

  // resourceIdCache will contain info about resources available(& their ACL) in resource server.
  Cache<String, String> resourceIdCache = CacheBuilder.newBuilder().maximumSize(1000)
      .expireAfterAccess(Constants.CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES).build();


  JwtAuthenticationServiceImpl(Vertx vertx, final JWTAuth jwtAuth, final JsonObject config,
                               final CacheService cacheService, final Api apis) {
    this.jwtAuth = jwtAuth;
    this.audience = config.getString("audience");
    this.host = config.getString("catServerHost");
    this.port = config.getInteger("catServerPort");
    this.catBasePath = config.getString("dxCatalogueBasePath");
    this.path = catBasePath + CAT_SEARCH_PATH;
    this.apis = apis;
    WebClientOptions options = new WebClientOptions();
    options.setTrustAll(true).setVerifyHost(false).setSsl(true);
    catWebClient = WebClient.create(vertx, options);
    this.cache = cacheService;
  }

  @Override
  public AuthenticationService tokenIntrospect(JsonObject request, JsonObject authenticationInfo,
                                               JwtData jwtData,
                                               Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("tokenIntrospect() started");
    String id = authenticationInfo.getString("id");
    ResultContainer result = new ResultContainer();
    result.jwtData = jwtData;
    String endPoint = authenticationInfo.getString("apiEndpoint");
    boolean skipResourceIdCheck =
            endPoint.equalsIgnoreCase(apis.getAsyncStatusEndpoint());


    Future<Boolean> audienceFuture = isValidAudienceValue(jwtData);
    audienceFuture.compose(audienceHandler -> {
              if (!result.jwtData.getIss().equals(result.jwtData.getSub())) {
                return isRevokedClientToken(result.jwtData);
              } else {
                return Future.succeededFuture(true);
              }
            }).compose(revokeTokenHandler -> {
              if (!skipResourceIdCheck && !result.jwtData.getIss().equals(result.jwtData.getSub())) {
                return isOpenResource(id);
              } else {
                return Future.succeededFuture("OPEN");
              }
            }).compose(openResourceHandler -> {
              LOGGER.debug("isOpenResource messahe {}", openResourceHandler);
              result.isOpen = openResourceHandler.equalsIgnoreCase("OPEN");
              if (result.isOpen && checkOpenEndPoints(endPoint)) {
                JsonObject json = new JsonObject();
                json.put(JSON_USERID, result.jwtData.getSub());
                return Future.succeededFuture(true);
              } else if (!skipResourceIdCheck
                      && !result.isOpen) {
                return isValidId(result.jwtData, id);
              } else {
                return Future.succeededFuture(true);
              }
            })
            .compose(
                    validIdHandler -> {
                      if (result.jwtData.getIss().equals(result.jwtData.getSub())) {
                        JsonObject jsonResponse = new JsonObject();
                        jsonResponse.put(JSON_USERID, result.jwtData.getSub());
                        jsonResponse.put(
                                JSON_EXPIRY,
                                LocalDateTime.ofInstant(
                                                Instant.ofEpochSecond(
                                                        Long.parseLong(result.jwtData.getExp().toString())),
                                                ZoneId.systemDefault())
                                        .toString());
                        jsonResponse.put(ROLE, result.jwtData.getRole());
                        jsonResponse.put(DRL, result.jwtData.getDrl());
                        jsonResponse.put(DID, result.jwtData.getDid());
                        jsonResponse.put(JSON_APD, result.jwtData.getApd());
                        jsonResponse.put(JSON_CONS, result.jwtData.getCons());
                        return Future.succeededFuture(jsonResponse);
                      } else {
                        return validateAccess(result.jwtData, result.isOpen, authenticationInfo);
                      }
                    }).onSuccess(successHandler -> {
              handler.handle(Future.succeededFuture(successHandler));
            }).onFailure(failureHandler -> {
              LOGGER.error("error : " + failureHandler.getMessage());
              handler.handle(Future.failedFuture(failureHandler.getMessage()));
            });
    return this;
  }

  Future<String> isOpenResource(String id) {
    LOGGER.trace("isOpenResource() started");
    Promise<String> promise = Promise.promise();
    String ACL = resourceIdCache.getIfPresent(id);
    if (ACL != null) {
      LOGGER.debug("Cache Hit");
      promise.complete(ACL);
    } else {
      // cache miss
      LOGGER.debug("Cache miss calling cat server");
      String groupId = null;
      JsonObject jsonObject = CatalogueService.getCatalogueItemJson(id);
      if (jsonObject != null) {
        groupId =
            jsonObject.containsKey("resourceGroup") ? jsonObject.getString("resourceGroup") : id;
      } else {
        LOGGER.debug("failed : id not exists");
        return Future.failedFuture("Not Found");
      }

      // 1. check group accessPolicy.
      // 2. check resource exist, if exist set accessPolicy to group accessPolicy. else fail
      Future<String> groupACLFuture = getResourceAccessPolicy(id);
      groupACLFuture.compose(groupACLResult -> {
            String groupPolicy = groupACLResult;
            return isResourceExist(id);
          }).onSuccess(handler -> promise.complete(resourceIdCache.getIfPresent(id)))
          .onFailure(handler -> {
            LOGGER.error("cat response failed for Id : (" + id + ")" + handler.getCause());
            promise.fail("Not Found " + id);
          });
    }
    return promise.future();
  }

  Future<Boolean> isRevokedClientToken(JwtData jwtData) {
    LOGGER.trace("isRevokedClientToken started param : " + jwtData);
    Promise<Boolean> promise = Promise.promise();
    CacheType cacheType = CacheType.REVOKED_CLIENT;
    String subId = jwtData.getSub();
    JsonObject requestJson = new JsonObject().put("type", cacheType).put("key", subId);

    cache.get(requestJson, handler -> {
      if (handler.succeeded()) {
        JsonObject responseJson = handler.result();
        LOGGER.debug("responseJson : " + responseJson);
        String timestamp = responseJson.getString("value");

        LocalDateTime revokedAt = ZonedDateTime.parse(timestamp).toLocalDateTime();
        LocalDateTime jwtIssuedAt =
            (LocalDateTime.ofInstant(Instant.ofEpochSecond(jwtData.getIat()),
                ZoneId.systemDefault()));

        if (jwtIssuedAt.isBefore(revokedAt)) {
          LOGGER.error("Privileges for client are revoked.");
          JsonObject result = new JsonObject().put("401", "revoked token passes");
          promise.fail(result.toString());
        } else {
          promise.complete(true);
        }
      } else {
        // since no value in cache, this means client_id is valid and not revoked
        LOGGER.info("cache call result : [MISS] " + handler.cause());
        promise.complete(true);
      }
    });
    return promise.future();
  }

  Future<Boolean> isValidId(JwtData jwtData, String id) {
    Promise<Boolean> promise = Promise.promise();
    String jwtId = jwtData.getIid().split(":")[1];
    if (id.equalsIgnoreCase(jwtId)) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect id value in jwt");
      promise.fail("Incorrect id value in jwt");
    }

    return promise.future();
  }

  public Future<JsonObject> validateAccess(JwtData jwtData, boolean openResource,
                                           JsonObject authInfo) {
    LOGGER.trace("validateAccess() started");
    Promise<JsonObject> promise = Promise.promise();
    String jwtId = jwtData.getIid().split(":")[1];

    if (openResource && checkOpenEndPoints(authInfo.getString("apiEndpoint"))) {
      LOGGER.info("User access is allowed.");
      JsonObject jsonResponse = new JsonObject();
      jsonResponse.put(JSON_IID, jwtId);
      jsonResponse.put(JSON_USERID, jwtData.getSub());
      jsonResponse.put(JSON_APD, jwtData.getApd());
      jsonResponse.put(JSON_CONS, jwtData.getCons());
      jsonResponse.put(ROLE, jwtData.getRole());
      jsonResponse.put(DRL, jwtData.getDrl());
      jsonResponse.put(DID, jwtData.getDid());
      return Future.succeededFuture(jsonResponse);
    }

    Method method = Method.valueOf(authInfo.getString("method"));
    String api = authInfo.getString("apiEndpoint");
    AuthorizationRequest authRequest = new AuthorizationRequest(method, api);
    IudxRole role = IudxRole.fromRole(jwtData.getRole());
    AuthorizationStrategy authStrategy = AuthorizationContextFactory.create(role, apis);
    JwtAuthorization jwtAuthStrategy = new JwtAuthorization(authStrategy);
    LOGGER.info("auth strategy " + authStrategy.getClass().getSimpleName());
    LOGGER.info("endPoint : " + authInfo.getString("apiEndpoint"));
    if (jwtAuthStrategy.isAuthorized(authRequest, jwtData)) {
      LOGGER.info("User access is allowed.");
      JsonObject jsonResponse = new JsonObject();
      jsonResponse.put(JSON_USERID, jwtData.getSub());
      jsonResponse.put(JSON_IID, jwtId);
      jsonResponse.put(JSON_APD, jwtData.getApd());
      jsonResponse.put(JSON_CONS, jwtData.getCons());
      jsonResponse.put(JSON_EXPIRY, (LocalDateTime.ofInstant(
          Instant.ofEpochSecond(Long.parseLong(jwtData.getExp().toString())),
          ZoneId.systemDefault())).toString());
      jsonResponse.put(ROLE, jwtData.getRole());
      jsonResponse.put(DRL, jwtData.getDrl());
      jsonResponse.put(DID, jwtData.getDid());
      promise.complete(jsonResponse);
    } else {
      LOGGER.error("failed - no access provided to endpoint");
      JsonObject result = new JsonObject().put("401", "no access provided to endpoint");
      promise.fail(result.toString());
    }
    return promise.future();
  }

  private boolean checkOpenEndPoints(String endPoint) {
    for (String item : OPEN_ENDPOINTS) {
      if (endPoint.contains(item)) {
        return true;
      }
    }
    return false;
  }

  Future<Boolean> isValidAudienceValue(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();
    if (audience != null && audience.equalsIgnoreCase(jwtData.getAud())) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect audience value in jwt");
      promise.fail("Incorrect audience value in jwt");
    }
    return promise.future();
  }

  Future<Boolean> isResourceExist(String id) {
    LOGGER.trace("isResourceExist() started");
    Promise<Boolean> promise = Promise.promise();
    String resourceExist = resourceIdCache.getIfPresent(id);
    if (resourceExist != null) {
      LOGGER.info("Info : cache Hit");
      promise.complete(true);
    } else {
      LOGGER.info("Info : Cache miss : call cat server");
      catWebClient.get(port, host, path).addQueryParam("property", "[id]")
          .addQueryParam("value", "[[" + id + "]]").addQueryParam("filter", "[id,accessPolicy]")
          .expect(ResponsePredicate.JSON).send(responseHandler -> {
            if (responseHandler.failed()) {
              promise.fail("false");
            }
            HttpResponse<Buffer> response = responseHandler.result();
            JsonObject responseBody = response.bodyAsJsonObject();
            if (response.statusCode() != HttpStatus.SC_OK) {
              promise.fail("false");
            } else if (!responseBody.getString("type").equals("urn:dx:cat:Success")) {
              promise.fail("Not Found");
            } else if (responseBody.getInteger("totalHits") == 0) {
              LOGGER.error("Info: Resource ID invalid : Catalogue item Not Found");
              promise.fail("Not Found");
            } else {
              LOGGER.debug("is Exist response : " + responseBody);
              resourceIdCache.put(id, responseBody.getJsonArray("results").getJsonObject(0).getString("accessPolicy"));
              promise.complete(true);
            }
          });
    }
    return promise.future();
  }

  Future<String> getResourceAccessPolicy(String groupId) {
    LOGGER.trace("getGroupAccessPolicy() started");
    Promise<String> promise = Promise.promise();
    String groupACL = resourceGroupCache.getIfPresent(groupId);
    if (groupACL != null) {
      LOGGER.info("Info : cache Hit");
      promise.complete(groupACL);
    } else {
      LOGGER.info("Info : cache miss");
      catWebClient.get(port, host, path).addQueryParam("property", "[id]")
          .addQueryParam("value", "[[" + groupId + "]]").addQueryParam("filter", "[accessPolicy]")
          .expect(ResponsePredicate.JSON).send(httpResponseAsyncResult -> {
            if (httpResponseAsyncResult.failed()) {
              LOGGER.error(httpResponseAsyncResult.cause());
              promise.fail("Resource not found");
              return;
            }
            HttpResponse<Buffer> response = httpResponseAsyncResult.result();
            if (response.statusCode() != HttpStatus.SC_OK) {
              promise.fail("Resource not found");
              return;
            }
            LOGGER.debug(response.body());
            JsonObject responseBody = response.bodyAsJsonObject();
            if (!responseBody.getString("type").equals("urn:dx:cat:Success")) {
              promise.fail("Resource not found");
              return;
            }
            String resourceACL = "SECURE";
            try {
              LOGGER.debug("resp from cat : " + responseBody);
              resourceACL =
                  responseBody.getJsonArray("results").getJsonObject(0).getString("accessPolicy");
              resourceGroupCache.put(groupId, resourceACL);
              LOGGER.debug("Info: Group ID valid : Catalogue item Found");
              promise.complete(resourceACL);
            } catch (Exception ignored) {
              LOGGER.error("Info: Group ID invalid : Empty response in results from Catalogue",
                  ignored);
              promise.fail("Resource not found");
            }
          });
    }
    return promise.future();
  }

  @Override
  public AuthenticationService decodeJwt(String jwtToken, Handler<AsyncResult<JwtData>> handler) {
    TokenCredentials creds = new TokenCredentials(jwtToken);
    jwtAuth.authenticate(creds).onSuccess(user -> {
              JwtData jwtData = new JwtData(user.principal());
              jwtData.setExp(user.get("exp"));
              jwtData.setIat(user.get("iat"));
              handler.handle(Future.succeededFuture(jwtData));
            })
            .onFailure(err -> {
              LOGGER.debug("err.getMessage():: " + err);
              LOGGER.error("failed to decode/validate jwt token : " + err.getMessage());
              handler.handle(Future.failedFuture(err.getMessage()));
            });

    return this;
  }


  // class to contain intermediate data for token introspection
  final class ResultContainer {
    JwtData jwtData;
    boolean isOpen;
  }
}
