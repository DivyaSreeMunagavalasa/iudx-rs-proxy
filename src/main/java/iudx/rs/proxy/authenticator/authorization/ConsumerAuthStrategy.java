package iudx.rs.proxy.authenticator.authorization;

import static iudx.rs.proxy.authenticator.authorization.Method.GET;
import static iudx.rs.proxy.authenticator.authorization.Method.POST;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.core.json.JsonArray;
import iudx.rs.proxy.authenticator.model.JwtData;
import iudx.rs.proxy.common.Api;

public class ConsumerAuthStrategy implements AuthorizationStrategy {

  private static final Logger LOGGER = LogManager.getLogger(ConsumerAuthStrategy.class);

  static Map<String, List<AuthorizationRequest>> consumerAuthorizationRules = new HashMap<>();
  
  private final Api apis;
  private static volatile ConsumerAuthStrategy instance;
  private ConsumerAuthStrategy(Api apis) {
    this.apis=apis;
    buildPermissions(apis);
  }
  public static ConsumerAuthStrategy getInstance(Api api)
  {
    if (instance == null)
    {
      synchronized (ConsumerAuthStrategy.class)
      {
        if (instance == null)
        {
          instance = new ConsumerAuthStrategy(api);
        }
      }
    }
    return instance;
  }
  
  private void buildPermissions(Api api) {
    List<AuthorizationRequest> apiAccessList = new ArrayList<>();
    apiAccessList.add(new AuthorizationRequest(GET, apis.getTemporalEndpoint()));
    apiAccessList.add(new AuthorizationRequest(GET, apis.getConsumerAuditEndpoint()));
    apiAccessList.add(new AuthorizationRequest(GET, apis.getEntitiesEndpoint()));
    apiAccessList.add(new AuthorizationRequest(POST, apis.getPostEntitiesEndpoint()));
    apiAccessList.add(new AuthorizationRequest(POST, apis.getPostTemporalEndpoint()));

    consumerAuthorizationRules.put(IudxAccess.API.getAccess(), apiAccessList);

    // async access list
    List<AuthorizationRequest> asyncAccessList = new ArrayList<>();
    asyncAccessList.add(new AuthorizationRequest(GET, api.getAsyncSearchEndPoint()));
    asyncAccessList.add(new AuthorizationRequest(GET, api.getAsyncStatusEndpoint()));
    consumerAuthorizationRules.put(IudxAccess.ASYNC.getAccess(), asyncAccessList);
  }
  

  @Override
  public boolean isAuthorized(AuthorizationRequest authRequest, JwtData jwtData) {
    JsonArray access = jwtData.getCons() != null ? jwtData.getCons().getJsonArray("access") : null;
    boolean result = false;
    if (access == null) {
      return result;
    }
    String endpoint = authRequest.getApi();
    Method method = authRequest.getMethod();
    LOGGER.debug("authorization request for : " + endpoint + " with method : " + method.name());
    LOGGER.debug("allowed access : " + access);

    if (!result && access.contains(IudxAccess.API.getAccess())) {
      result = consumerAuthorizationRules.get(IudxAccess.API.getAccess()).contains(authRequest);
    }
    if (!result) {
      result = consumerAuthorizationRules.get(IudxAccess.ASYNC.getAccess()).contains(authRequest);
    }
    LOGGER.debug("result : " + result);
    return result;
  }

}
