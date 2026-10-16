package com.tangran.tangrantech.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;

/**
 * 颜色工具：兼容糖然科技内容里出现的三种十六进制写法 {@code &#RRGGBB}、{@code {#RRGGBB}}、
 * {@code <#RRGGBB>}，以及标准 {@code &a/&l/...} 颜色/格式码。
 */
public final class Colors {

    private Colors() {}

    private static final Pattern HEX_BRACE = Pattern.compile("\\{#([A-Fa-f0-9]{6})}");
    private static final Pattern HEX_AMP = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern HEX_ANGLE = Pattern.compile("<#([A-Fa-f0-9]{6})>");

    /** 把一段带 {@code &} / hex 占位符的文本转为原版 § 文本。 */
    public static String c(String s) {
        if (s == null) return null;
        String t = replaceHex(HEX_BRACE, s);
        t = replaceHex(HEX_AMP, t);
        t = replaceHex(HEX_ANGLE, t);
        return ChatColor.translateAlternateColorCodes('&', t);
    }

    public static List<String> c(List<String> list) {
        if (list == null) return null;
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) out.add(c(s));
        return out;
    }

    private static final Pattern SECTION_HEX = Pattern.compile("§x(§[0-9a-fA-F]){6}");
    private static final Pattern SECTION_SIMPLE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /**
     * 去掉 {@code §} 颜色码（含 {@code §x§r§r§g§g§b§b} 十六进制形式）。
     *
     * <p>用于「按显示名判断是不是同一个物品」这类比较——原 JS 脚本里对应的就是
     * {@code zkStrip} / {@code ChatColor.stripColor}。</p>
     */
    public static String strip(String s) {
        if (s == null) return "";
        String t = SECTION_HEX.matcher(s).replaceAll("");
        return SECTION_SIMPLE.matcher(t).replaceAll("");
    }

    private static String replaceHex(Pattern p, String input) {
        Matcher m = p.matcher(input);
        if (!m.find()) return input;
        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) m.appendReplacement(sb, toBukkitHex(m.group(1)));
        m.appendTail(sb);
        return sb.toString();
    }

    private static String toBukkitHex(String hex) {
        StringBuilder b = new StringBuilder("§x");
        for (int i = 0; i < hex.length(); i++) b.append('§').append(Character.toLowerCase(hex.charAt(i)));
        return b.toString();
    }
}
