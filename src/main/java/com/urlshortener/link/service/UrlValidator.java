package com.urlshortener.link.service;

import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UrlValidator {

    static final int MAX_LENGTH = 2048;
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final String ownHost;

    public UrlValidator(@Value("${app.base-url}") String baseUrl) {
        this.ownHost = URI.create(baseUrl).getHost().toLowerCase(Locale.ROOT);
    }

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("URL is required");
        }

        if (url.length() > MAX_LENGTH) {
            throw invalid("URL is longer than " + MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("URL is malformed");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalid("Only http and https URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("URL has no host");
        }

        host = host.toLowerCase(Locale.ROOT);
        if (host.equals(ownHost)) {
            throw invalid("URL points to this shortener");
        }

        boolean isLocalhost = host.equals("localhost") || host.endsWith(".localhost");
        if (isLocalhost || isPrivateIpLiteral(host)) {
            throw invalid("URL points to a private address");
        }
    }

    // Checks IP literals only, no DNS lookup. The server never fetches the target,
    // so a hostname that resolves to a private IP only harms the visitor's own
    // network.
    static boolean isPrivateIpLiteral(String host) {
        boolean ipv6 = host.startsWith("[") && host.endsWith("]");
        if (!ipv6 && !IPV4.matcher(host).matches()) {
            return false;
        }

        try {
            String literal = ipv6 ? host.substring(1, host.length() - 1) : host;
            InetAddress address = InetAddress.getByName(literal);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;

            return address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()
                    || uniqueLocalV6;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorType.URL_INVALID, reason);
    }
}
