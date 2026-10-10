package de.tobias30.claims;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;

/** Optional WorldGuard bridge, kept reflective so WorldGuard remains an optional dependency. */
final class WorldGuardHook {

    boolean installed() {
        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        return worldGuard != null && worldGuard.isEnabled();
    }

    boolean intersectsProtectedRegion(World world, int chunkX, int chunkZ) throws ReflectiveOperationException {
        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) return false;
        ClassLoader loader = worldGuard.getClass().getClassLoader();
        Class<?> wgClass = loader.loadClass("com.sk89q.worldguard.WorldGuard");
        Object wg = wgClass.getMethod("getInstance").invoke(null);
        Object platform = wgClass.getMethod("getPlatform").invoke(wg);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Class<?> adapter = loader.loadClass("com.sk89q.worldguard.bukkit.BukkitAdapter");
        Object wgWorld = findAdapter(adapter, "adapt", world);
        Method getManager = findCompatible(container.getClass(), "get", wgWorld);
        Object manager = getManager.invoke(container, wgWorld);
        if (manager == null) return false;

        Object regionsObject = manager.getClass().getMethod("getRegions").invoke(manager);
        if (!(regionsObject instanceof Map<?, ?> regions)) return false;
        int minX = chunkX << 4, minZ = chunkZ << 4;
        int maxX = minX + 15, maxZ = minZ + 15;
        for (var entry : regions.entrySet()) {
            Object region = entry.getValue();
            String id = String.valueOf(entry.getKey());
            if ("__global__".equalsIgnoreCase(id)) {
                if (globalBuildDenied(region, loader)) return true;
                continue;
            }
            Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
            Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            int regionMinX = ((Number) min.getClass().getMethod("x").invoke(min)).intValue();
            int regionMinZ = ((Number) min.getClass().getMethod("z").invoke(min)).intValue();
            int regionMaxX = ((Number) max.getClass().getMethod("x").invoke(max)).intValue();
            int regionMaxZ = ((Number) max.getClass().getMethod("z").invoke(max)).intValue();
            if (regionMinX <= maxX && regionMaxX >= minX && regionMinZ <= maxZ && regionMaxZ >= minZ) return true;
        }
        return false;
    }

    private boolean globalBuildDenied(Object region, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> flags = loader.loadClass("com.sk89q.worldguard.protection.flags.Flags");
        Object build = flags.getField("BUILD").get(null);
        Object state = findCompatible(region.getClass(), "getFlag", build).invoke(region, build);
        return state != null && "DENY".equals(String.valueOf(state));
    }

    private Object findAdapter(Class<?> adapter, String name, Object value) throws ReflectiveOperationException {
        return findCompatible(adapter, name, value).invoke(null, value);
    }

    private Method findCompatible(Class<?> owner, String name, Object value) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(value)) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "(" + value.getClass().getName() + ")");
    }
}
