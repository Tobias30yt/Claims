package de.tobias30.claims;

import org.bukkit.entity.Player;
import java.util.Map;

public final class Limits {
    private Limits() {}
    public static int of(Player player, String kind, int fallback) {
        String prefix = "claims." + kind + ".";
        if (player.hasPermission(prefix + "unlimited")) return Integer.MAX_VALUE;
        return resolve(player.getEffectivePermissions().stream()
            .collect(java.util.stream.Collectors.toMap(p -> p.getPermission(), p -> p.getValue(), (a, b) -> b)), prefix, fallback);
    }
    static int resolve(Map<String, Boolean> permissions, String prefix, int fallback) {
        int best = -1;
        for (var e : permissions.entrySet()) {
            if (!e.getValue() || !e.getKey().startsWith(prefix)) continue;
            String suffix = e.getKey().substring(prefix.length());
            if (!suffix.matches("[0-9]+")) continue;
            try { best = Math.max(best, Integer.parseInt(suffix)); } catch (NumberFormatException ignored) {}
        }
        return best < 0 ? Math.max(0, fallback) : best;
    }
    public static String display(int n) { return n == Integer.MAX_VALUE ? "Unlimited" : Integer.toString(n); }
}
