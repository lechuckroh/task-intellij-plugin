package lechuck.intellij.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RegexUtil {

    private RegexUtil() {}

    public static List<String> splitBySpacePreservingQuotes(String str) {
        if (str == null) {
            return List.of();
        }
        /* TODO Character classes should be preferred over reluctant quantifiers in regular expressions
            Found by SonarQube: Code smell Minor java:S5857
            .+? MUST be replaced by <[^>]++>
        */
        var matcher = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(str);
        var result = new ArrayList<String>();
        while (matcher.find()) {
            String item = matcher.group(1).replace("\"", "");
            result.add(item);
        }
        return result;
    }
}
