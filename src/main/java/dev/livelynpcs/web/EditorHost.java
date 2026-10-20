package dev.livelynpcs.web;

import com.sun.net.httpserver.HttpExchange;
import dev.livelynpcs.LivelyNpcs;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What the editor API needs from the server around it: logins, the main thread, players, worlds and
 * the game's registries. The plugin uses {@link Bukkit}; the tests stand in for it.
 */
interface EditorHost {

    /** The live editor session of this request (its Bearer token), or null. */
    WebServer.Session session(HttpExchange exchange);

    /**
     * Runs task on the server's main thread and waits for it. Throws ApiException(503) when the main
     * thread does not get to it in time; anything the task throws comes out as it is.
     */
    <T> T sync(Callable<T> task) throws Exception;

    /** The online player with this id, or null. Main thread. */
    Player player(UUID id);

    /** The name the server last knew this player by, or null. Main thread. */
    String playerName(UUID id);

    /** Whether a world with this name is loaded. Main thread. */
    boolean worldExists(String name);

    /** Every item id ("wheat"; another namespace's items with it, "mod:thing"), sorted, without air. */
    List<String> items();

    /** Mob types an NPC can be ("villager", ...), without "player". */
    Set<String> mobTypes();

    /** npcs.scale. */
    float scale();

    /** npcs.key-ticks. */
    int keyTicks();

    void warn(String message);

    /** The real one: Bukkit's scheduler, players, worlds and Paper's registries. */
    final class Server implements EditorHost {

        /** How long a request waits for the main thread before it gives up. */
        private static final long SYNC_SECONDS = 15;

        private final LivelyNpcs plugin;
        private final WebServer web;
        private volatile List<String> items;
        private volatile Set<String> mobs;

        Server(LivelyNpcs plugin, WebServer web) {
            this.plugin = plugin;
            this.web = web;
        }

        @Override
        public WebServer.Session session(HttpExchange exchange) {
            return web.session(exchange);
        }

        @Override
        public <T> T sync(Callable<T> task) throws Exception {
            if (Bukkit.isPrimaryThread()) return task.call();
            Future<T> future;
            try {
                future = Bukkit.getScheduler().callSyncMethod(plugin, task);
            } catch (RuntimeException e) {
                throw new ApiException(503, "The server is shutting down or reloading: try again in a moment.");
            }
            try {
                return future.get(SYNC_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(false);
                throw new ApiException(503, "The server is too busy to answer right now: try again in a moment.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "The server is shutting down: try again in a moment.");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw e;
            }
        }

        @Override
        public Player player(UUID id) {
            return Bukkit.getPlayer(id);
        }

        @Override
        public String playerName(UUID id) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(id);
            return p.getName();
        }

        @Override
        public boolean worldExists(String name) {
            return Bukkit.getWorld(name) != null;
        }

        @Override
        public List<String> items() {
            List<String> list = items;
            if (list == null) {
                TreeSet<String> ids = new TreeSet<>();
                Registry<org.bukkit.inventory.ItemType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM);
                registry.keyStream().forEach(key -> {
                    String id = name(key);
                    if (!id.equals("air")) ids.add(id);
                });
                list = List.copyOf(ids);
                items = list;
            }
            return list;
        }

        @Override
        public Set<String> mobTypes() {
            Set<String> set = mobs;
            if (set == null) {
                TreeSet<String> ids = new TreeSet<>();
                for (EntityType type : Registry.ENTITY_TYPE) {
                    if (type == EntityType.PLAYER || type == EntityType.UNKNOWN || !type.isAlive()) continue;
                    ids.add(name(type.getKey()));
                }
                set = Set.copyOf(ids);
                mobs = set;
            }
            return set;
        }

        private static String name(NamespacedKey key) {
            return key.getNamespace().equals(NamespacedKey.MINECRAFT) ? key.getKey() : key.toString().toLowerCase(Locale.ROOT);
        }

        @Override
        public float scale() {
            return plugin.settings().scale;
        }

        @Override
        public int keyTicks() {
            return plugin.settings().keyTicks;
        }

        @Override
        public void warn(String message) {
            plugin.getLogger().warning(message);
        }
    }
}
