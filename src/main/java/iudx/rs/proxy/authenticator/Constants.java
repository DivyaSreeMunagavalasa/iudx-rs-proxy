package iudx.rs.proxy.authenticator;

import java.util.List;

public class Constants {

  public static final List<String> OPEN_ENDPOINTS =
      List.of("/temporal/entities", "/entities", "/consumer/audit", "/entityOperations/query","/async/search","/async/status");
  public static final long CACHE_TIMEOUT_AMOUNT = 30;
  public static final String CAT_SEARCH_PATH = "/search";
  public static final String AUTH_CERTIFICATE_PATH = "/cert";
  public static final String CAT_ITEM_PATH = "/item";
  public static final String JSON_USERID = "userid";
  public static final String JSON_IID = "iid";
  public static final String JSON_EXPIRY = "expiry";
  public static final String JSON_APD = "apd";
  public static final String ROLE = "role";
  public static final String DRL = "drl";
  public static final String DID = "did";
  public static final String DELEGATOR_ID = "delegatorId";
  public static final String JSON_CONS = "cons";
}
