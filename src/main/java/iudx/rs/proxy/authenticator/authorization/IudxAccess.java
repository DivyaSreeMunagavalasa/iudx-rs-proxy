package iudx.rs.proxy.authenticator.authorization;

import java.util.stream.Stream;

public enum IudxAccess {

  API("api");

  private final String access;

  IudxAccess(String access) {
    this.access = access;
  }

  public String getAccess() {
    return this.access;
  }

  public static IudxAccess fromAccess(final String access) {
    return Stream.of(values())
        .filter(v -> v.access.equalsIgnoreCase(access))
        .findAny()
        .orElse(null);
  }

}
