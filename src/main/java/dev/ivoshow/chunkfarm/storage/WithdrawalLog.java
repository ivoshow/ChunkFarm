package dev.ivoshow.chunkfarm.storage;

import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * Append-only record of everything that leaves a warehouse.
 *
 * <p>The warehouse is a counter, so a duplication bug leaves no item trail to follow
 * afterwards — the number simply fails to go down. This is the trail: who took what,
 * from which farm, and what the counter read before and after. It is the difference
 * between "someone says there is a dupe" and being able to prove where.
 *
 * <p>Writes happen off the main thread and never throw into the caller: losing a log
 * line must never break a withdrawal.
 */
public final class WithdrawalLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final Logger logger;
    private volatile boolean enabled;
    private volatile boolean broken;

    public WithdrawalLog(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @param before the counter before the withdrawal, so a line that does not add up
     *               is visible without cross-referencing anything
     */
    public void record(Player player, ChunkFarm farm, ItemKey key, long amount, long before) {
        if (!this.enabled || this.broken || amount <= 0) {
            return;
        }
        String line = String.join(" ",
                LocalDateTime.now().format(STAMP),
                player.getName(),
                player.getUniqueId().toString(),
                "world=" + farm.pos().world(),
                "chunk=" + farm.pos(),
                "owner=" + farm.ownerName(),
                key.serialize(),
                "-" + amount,
                "restante=" + (before - amount)) + System.lineSeparator();
        append(line);
    }

    /** Anything that removes a farm and the items with it deserves a line too. */
    public void recordFarmRemoved(String actor, ChunkFarm farm, String reason) {
        if (!this.enabled || this.broken) {
            return;
        }
        append(String.join(" ",
                LocalDateTime.now().format(STAMP),
                actor,
                "REMOVEU-FAZENDA",
                "chunk=" + farm.pos(),
                "owner=" + farm.ownerName(),
                "itens=" + farm.warehouse().total(),
                "motivo=" + reason) + System.lineSeparator());
    }

    private void append(String line) {
        try {
            Files.createDirectories(this.file.getParent());
            Files.write(this.file, List.of(line.stripTrailing()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // Complain once, then stay quiet rather than spam on every withdrawal.
            this.broken = true;
            this.logger.warning("Registro de saques desativado, nao foi possivel escrever em "
                    + this.file + ": " + ex.getMessage());
        }
    }
}
