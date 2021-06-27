package lechuck.intellij.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RegexUtil {

    public static List<String> splitBySpacePreservingQuotes(String str) {
        var matcher = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(str);
        var result = new ArrayList<String>();
        while (matcher.find()) {
            String item = matcher.group(1).replace("\"", "");
            result.add(item);
        }
        return result;
    }
}
