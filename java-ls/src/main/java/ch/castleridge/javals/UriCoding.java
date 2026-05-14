package ch.castleridge.javals;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class UriCoding {
    public static String decode(String uri) {
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        while (!uri.equals(decoded)) {
            uri = decoded;
            decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        }
        return decoded;
    }
}