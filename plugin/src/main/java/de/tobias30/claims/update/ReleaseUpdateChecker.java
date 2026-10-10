package de.tobias30.claims.update;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Warns staff when the GitHub release page is ahead of the installed plugin. */
public final class ReleaseUpdateChecker {
    private static final URI RELEASES = URI.create("https://api.github.com/repos/Tobias30yt/Claims/releases?per_page=100");
    private static final Pattern TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ASSET = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+\\.jar)\\\"");
    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private ScheduledTask periodic;

    public ReleaseUpdateChecker(JavaPlugin plugin) { this.plugin = plugin; }
    public void start() {
        if (!plugin.getConfig().getBoolean("update-checker.enabled", true)) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> check());
        periodic = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> check(), 48, 48, TimeUnit.HOURS);
    }
    private void check() {
        try {
            HttpRequest request = HttpRequest.newBuilder(RELEASES).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Claims/" + plugin.getPluginMeta().getVersion()).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());
            Matcher tags = TAG.matcher(response.body()); List<String> releases = new ArrayList<>();
            while (tags.find()) releases.add(tags.group(1));
            if (releases.isEmpty()) throw new IllegalStateException("No release tags in GitHub response");
            String installed = plugin.getPluginMeta().getVersion();
            String latest = releases.get(0);
            int behind = (int) releases.stream().filter(tag -> compare(tag, installed) > 0).count();
            if (behind == 0) return;
            Matcher assets = ASSET.matcher(response.body());
            String jar = "https://github.com/Tobias30yt/Claims/releases/latest";
            if (assets.find()) jar = assets.group(1);
            String download = jar;
            String releaseUrl = "https://github.com/Tobias30yt/Claims/releases/tag/" + latest;
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> notifyStaff(behind, installed, latest, releaseUrl, download));
        } catch (Exception e) { plugin.getLogger().warning("Could not check GitHub for Claims updates: " + e.getMessage()); }
    }
    private void notifyStaff(int behind, String installed, String latest, String release, String jar) {
        String message = "Claims update available: you are " + behind + " release" + (behind == 1 ? "" : "s") + " behind (installed " + installed + ", latest " + latest + "). Download: " + release + " | JAR: " + jar;
        plugin.getLogger().warning(message);
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) if (player.isOp())
            player.getScheduler().run(plugin, task -> player.sendMessage(message), null);
    }
    private static int compare(String a, String b) { List<Integer> x = parts(a), y = parts(b); for (int i=0;i<Math.max(x.size(),y.size());i++){int l=i<x.size()?x.get(i):0,r=i<y.size()?y.get(i):0;if(l!=r)return Integer.compare(l,r);}return 0; }
    private static List<Integer> parts(String value) { Matcher matcher=Pattern.compile("\\d+").matcher(value); List<Integer> out=new ArrayList<>(); while(matcher.find()) try { out.add(Integer.parseInt(matcher.group())); } catch(NumberFormatException ignored) { out.add(Integer.MAX_VALUE); } return out; }
    public void shutdown() { if (periodic != null) periodic.cancel(); }
}
