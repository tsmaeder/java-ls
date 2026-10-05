/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

/**
 * Light conversion of raw javadoc comment text to Markdown.
 */
public final class JavadocMarkdown {

    private JavadocMarkdown() {}

    public static String toMarkdown(String rawJavadoc) {
        if (rawJavadoc == null || rawJavadoc.isBlank()) return "";
        String body = stripCommentMarkers(rawJavadoc);
        if (body.isBlank()) return "";
        body = replaceInlineTags(body);
        body = convertBlockTags(body);
        body = stripHtml(body);
        return body.trim();
    }

    private static String stripCommentMarkers(String raw) {
        String text = raw.trim();
        if (text.startsWith("/**")) text = text.substring(3);
        if (text.endsWith("*/")) text = text.substring(0, text.length() - 2);
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
                if (trimmed.startsWith(" ")) trimmed = trimmed.substring(1);
            }
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString();
    }

    private static String replaceInlineTags(String body) {
        StringBuilder out = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) == '{' && i + 1 < body.length() && body.charAt(i + 1) == '@') {
                int close = findTagClose(body, i + 2);
                if (close > i) {
                    out.append(renderInlineTag(body.substring(i + 2, close).trim()));
                    i = close + 1;
                    continue;
                }
            }
            out.append(body.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static int findTagClose(String body, int from) {
        int depth = 1;
        for (int i = from; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String renderInlineTag(String tag) {
        int sp = indexOfWhitespace(tag);
        String name = sp < 0 ? tag : tag.substring(0, sp);
        String arg = sp < 0 ? "" : tag.substring(sp).trim();
        return switch (name) {
            case "code", "literal" -> arg.isEmpty() ? "" : "`" + arg + "`";
            case "link", "linkplain" -> linkTarget(arg);
            default -> arg.isEmpty() ? name : arg;
        };
    }

    private static String linkTarget(String arg) {
        if (arg.isEmpty()) return "";
        int hash = arg.indexOf('#');
        String type = hash < 0 ? arg : arg.substring(0, hash);
        String member = hash < 0 ? "" : arg.substring(hash + 1);
        int sp = indexOfWhitespace(member.isEmpty() ? type : member);
        String label;
        if (sp >= 0) {
            label = (member.isEmpty() ? type : member).substring(sp).trim();
        } else if (!member.isEmpty()) {
            label = member;
        } else {
            label = type;
        }
        int cut = Math.max(label.lastIndexOf('.'), label.lastIndexOf('/'));
        if (cut >= 0) label = label.substring(cut + 1);
        return label;
    }

    private static String convertBlockTags(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder main = new StringBuilder();
        StringBuilder tags = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("@")) {
                appendBlockTag(tags, trimmed);
            } else if (tags.length() > 0) {
                tags.append(' ').append(trimmed);
            } else {
                if (main.length() > 0) main.append('\n');
                main.append(line);
            }
        }
        if (tags.length() == 0) return main.toString();
        if (main.length() > 0) main.append("\n\n");
        main.append(tags);
        return main.toString();
    }

    private static void appendBlockTag(StringBuilder tags, String line) {
        int sp = indexOfWhitespace(line);
        String name = sp < 0 ? line.substring(1) : line.substring(1, sp);
        String rest = sp < 0 ? "" : line.substring(sp).trim();
        if (tags.length() > 0) tags.append('\n');
        switch (name) {
            case "param" -> {
                int argSp = indexOfWhitespace(rest);
                if (argSp < 0) tags.append("- **@param** `").append(rest).append('`');
                else tags.append("- **@param** `").append(rest, 0, argSp).append("` ")
                        .append(rest.substring(argSp).trim());
            }
            case "return" -> tags.append("- **@return** ").append(rest);
            case "throws", "exception" -> {
                int argSp = indexOfWhitespace(rest);
                if (argSp < 0) tags.append("- **@throws** `").append(rest).append('`');
                else tags.append("- **@throws** `").append(rest, 0, argSp).append("` ")
                        .append(rest.substring(argSp).trim());
            }
            default -> tags.append("- **@").append(name).append("** ").append(rest);
        }
    }

    private static String stripHtml(String body) {
        String text = body;
        text = text.replaceAll("(?i)</?p\\s*/?>", "\n\n");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</?pre\\s*>", "\n```\n");
        text = text.replaceAll("(?i)<code>(.*?)</code>", "`$1`");
        text = text.replaceAll("(?i)</?b\\s*>", "**");
        text = text.replaceAll("(?i)</?strong\\s*>", "**");
        text = text.replaceAll("(?i)</?i\\s*>", "_");
        text = text.replaceAll("(?i)</?em\\s*>", "_");
        text = text.replaceAll("(?i)<[^>]+>", "");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("\n{3,}", "\n\n");
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
