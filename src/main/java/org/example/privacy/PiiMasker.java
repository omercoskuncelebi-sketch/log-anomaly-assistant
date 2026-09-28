package org.example.privacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PiiMasker {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)([a-z0-9._%+-]+)@([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*)"
    );
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d))\\.(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(?![\\d.])"
    );
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:::([0-9a-f]{1,4})?|(?:[0-9a-f]{1,4}:){1,7}:[0-9a-f]{0,4}|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4})(?![0-9a-f:])"
    );

    public String mask(String value) {
        if (value == null) {
            return null;
        }

        return maskClientIps(maskEmails(value));
    }

    public String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        return maskEmails(email);
    }

    public String maskClientIp(String clientIp) {
        if (clientIp == null) {
            return null;
        }
        return maskClientIps(clientIp);
    }

    private String maskEmails(String value) {
        Matcher matcher = EMAIL_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String localPart = matcher.group(1);
            String masked = localPart.charAt(0) + "***@" + matcher.group(2);
            matcher.appendReplacement(result, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String maskClientIps(String value) {
        Matcher matcher = IPV4_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + ".xxx.xxx"));
        }
        matcher.appendTail(result);

        return IPV6_PATTERN.matcher(result).replaceAll("[ipv6-masked]");
    }
}
