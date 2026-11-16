package io.scent;

/** Checked before the Spring context can create a DataSource, run Flyway or provision accounts. */
public final class IsolatedDatabase {
    private IsolatedDatabase() { }

    public static String require(String url) {
        if (url == null || !url.matches("jdbc:mysql://[^/?]+/[A-Za-z0-9_]+_it(?:\\?.*)?")) {
            throw new IllegalStateException("Integration tests require an explicit dedicated MySQL *_it URL");
        }
        return url;
    }
}
