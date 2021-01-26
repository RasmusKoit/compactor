package eu.ialbhost.compactor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

@SuppressWarnings("unused")
public class Compactor extends JavaPlugin {
    /* Holds all MaterialCompactorInfo references */            /* Note: you can use diamond types (<>), */
    private final List<MaterialCompactorInfo> materialCompactorInfoList = new ArrayList<>(); /* because generic */
    /* signature matches field's signature,
     * doing this is supported from Java 7
     */

    /* Called when plugin gets enabled in server */
    @Override
    public void onEnable() {
        /* Register MaterialCompactorInfo class */
        ConfigurationSerialization.registerClass(MaterialCompactorInfo.class);

        /* Load/save default configuration */
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
    }

    /* Called when command is invoked on server (only commands registered on plugin.yml reach here */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("compact")) return false;

        /* Player only commands */
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (args.length == 0 && player.hasPermission("compact.use")) {
                compactInventory(player);
                player.sendMessage("All possible items have been compacted");
                return true;
            }
        }
        /* Does not need player to execute */
        if (args.length > 0) {
            String subCmd = args[0];
            switch (subCmd) {
                case "reload":
                    if (!sender.hasPermission("compact.reload")) return false;
                    this.reloadConfig();
                    sender.sendMessage("Compactor configuration reloaded");
                    return true;
                case "add":
                    if (!sender.hasPermission("compact.edit.add")) return false;

                    if (!(args.length == 4)) return false;
                    return editConfig(args, "add", sender);
                case "rm":
                    if (!sender.hasPermission("compact.edit.rm")) return false;
                    if (!(args.length == 4)) return false;
                    return editConfig(args, "rm", sender);
                default:
                    return false;
            }
        }
        return false;
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> tabHints = new ArrayList<>();

        switch (args.length) {
            case 1:
                String[] commandHints = new String[]{"add", "rm", "reload"};
                tabHints.addAll(Arrays.asList(commandHints));
                return tabHints;
            case 2:
            case 4:
                if (!args[0].equals("reload")) {
                    int materialIndex = ((args.length == 2) ? 1 : 3); // Hint for source and target materials
                    String[] materialHints = Arrays.stream(Material.values())
                            .map(Enum::toString)
                            .filter(x -> x.contains(args[materialIndex].toUpperCase()))
                            .toArray(String[]::new);
                    tabHints.addAll(Arrays.asList(materialHints));
                }
                return tabHints;
            default:
                return tabHints;
        }
    }

    private boolean editConfig(String[] args, String subcmd, CommandSender sender) {
        if (!(validateMaterialValues(args[1], args[2], args[3]))) return false;
        // User defined compact Object
        Material sourceMaterial = Material.matchMaterial(args[1]);
        int sourceCount = Integer.parseInt(args[2]);
        Material targetMaterial = Material.matchMaterial(args[3]);

        LinkedHashMap<String, Object> newMaterial = new LinkedHashMap<>();
        newMaterial.put("sourceMaterial", Objects.requireNonNull(sourceMaterial).toString());
        newMaterial.put("sourceCount", sourceCount);
        newMaterial.put("targetMaterial", Objects.requireNonNull(targetMaterial).toString());

        @SuppressWarnings("unchecked")
        ArrayList<LinkedHashMap<String, Object>> materials = new ArrayList<>(
                (Collection<? extends LinkedHashMap<String, Object>>)
                        Objects.requireNonNull(getConfig().getList("materials")));

        if (subcmd.equals("add")) {
            if (!(checkIfCmpExists(newMaterial, materials))) {
                materials.add(newMaterial);
            } else {
                sender.sendMessage("This configuration already exists!");
                return true;
            }
        } else if (subcmd.equals("rm")) {
            if (materials.contains(newMaterial)) {
                materials.remove(newMaterial);
            } else {
                sender.sendMessage("This configuration wasn't found");
                return true;
            }
        } else {
            return false;
        }

        getConfig().set("materials", materials);
        saveConfig();
        reloadConfig();
        sender.sendMessage("Compactor configuration updated!");
        return true;
    }

    private boolean checkIfCmpExists(LinkedHashMap<String, Object> searchable, ArrayList<LinkedHashMap<String, Object>> materials) {
        for (LinkedHashMap<String, Object> material : materials) {
            if (searchable.get("sourceMaterial").equals(material.get("sourceMaterial"))) return true;
        }
        return false;
    }

    private boolean validateMaterialValues(String sourceMaterial, String count, String targetMaterial) {
        if (Material.matchMaterial(sourceMaterial) == null) return false;
        if (Material.matchMaterial(targetMaterial) == null) return false;
        if (count == null) return false;
        try {
            Integer.parseInt(count);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }


    @Override
    public void reloadConfig() {
        super.reloadConfig();

        /* Reload MaterialCompactorInfo list */
        materialCompactorInfoList.clear();
        Objects.requireNonNull(getConfig().getList("materials")).forEach(material -> {
            @SuppressWarnings("unchecked") /* Shut the fuck up right now, let me do my thing... */
                    Map<String, Object> serializedInfo = (Map<String, Object>) material;
            materialCompactorInfoList.add(new MaterialCompactorInfo(serializedInfo));
        });
    }

    /* The holy inventory compactor method */
    private void compactInventory(Player player) {
        Inventory inventory = player.getInventory();

        /* Merged items. AtomicInteger is used here because... I am lazy and its incrementing methods are awesome */
        Map<Material, AtomicInteger> mergedItems = new HashMap<>();

        /* Iterate over inventory, and collect items to map (Using Java 8 functional API, woo) */
        StreamSupport.stream(inventory.spliterator(), false)
                .filter(Objects::nonNull) /* Filters out null ItemStacks, for sure */
                .filter(i -> i.getType() != Material.AIR) /* Filters out AIR ItemStacks */
                .filter(this::hasNoCustomData) /* Filters out ItemStacks with custom data, as they */
                .forEach(item -> {             /* cannot be stacked with others really */
                    /* Adds item to mergedItems map and increments its amount counter */
                    mergedItems.computeIfAbsent(item.getType(), k -> new AtomicInteger(0))
                            .addAndGet(item.getAmount());
                });

        /* Find compactable items */
        mergedItems.forEach((material, count) -> findForMaterial(material).ifPresent(info -> {
            if (inventory.containsAtLeast(new ItemStack(material), info.sourceCount)) {
                /* Set up item count check */
                AtomicInteger iteratedItems = new AtomicInteger(0);

                /* Get all ItemStacks (with slot id's) from inventory */
                HashMap<Integer, ? extends ItemStack> inventoryItems = inventory.all(material);

                /* Iterate over map */
                inventoryItems.forEach((slot, itemStack) -> {
                    /* Failable assertion, ideally shouldn't happen */
                    if (itemStack == null || itemStack.getType() != info.sourceMaterial) {
                        getLogger().warning(itemStack + ".getType() != " + info.sourceMaterial);
                        return;
                    }
                    iteratedItems.getAndAdd(itemStack.getAmount());
                    inventory.clear(slot);
                });

                /* Failable assertion, ideally shouldn't happen */
                if (count.get() != iteratedItems.get()) {
                    getLogger().warning(String.format(
                            "Player %s inventory compacting: count %s != iteratedItems %s",
                            player.getName(), count.get(), iteratedItems.get()
                    ));
                }

                int addAmount = count.get() / info.sourceCount; /* How many target blocks should be added */
                int overflowAmount = count.get() % info.sourceCount; /* How many source blocks should be restored */
                int droppedItems = 0; /* How many items got dropped */

                /* Add items */
                for (int i = 0; i < addAmount; i++) {
                    if (addItem(inventory, info.targetMaterial)) {
                        droppedItems++;
                    } else {
                        mergedItems.computeIfPresent(info.targetMaterial, (k, matCount) -> {
                            matCount.getAndIncrement();
                            return matCount;
                        });
                    }
                }

                /* And restore non-compacted items */
                for (int i = 0; i < overflowAmount; i++) {
                    if (addItem(inventory, info.sourceMaterial)) {
                        droppedItems++;
                    } else {
                        mergedItems.computeIfPresent(info.sourceMaterial, (k, matCount) -> {
                            matCount.getAndIncrement();
                            return matCount;
                        });
                    }
                }

                /* Warn player about dropped items */
                if (droppedItems > 0) {
                    player.sendMessage(droppedItems + " items got dropped on ground, because " +
                            "of insufficient space in inventory.");
                }
            }
        }));
    }

    /* Makes sure that given ItemStack doesn't have custom data */
    private boolean hasNoCustomData(ItemStack itemStack) {
        ItemMeta im = itemStack.getItemMeta();
        assert im != null;
        boolean has = im.hasDisplayName();
        has = has || im.hasEnchants();
        has = has || im.hasLore();
        has = has || im.isUnbreakable();
        return !has;
    }

    /* Finds MaterialCompactorInfo for given Material */
    private Optional<MaterialCompactorInfo> findForMaterial(Material material) {
        for (MaterialCompactorInfo info : materialCompactorInfoList) {
            if (info.sourceMaterial.equals(material))
                return Optional.of(info);
        }
        return Optional.empty();
    }

    /*
     * Adds new item to inventory (to existing ItemStack, if present or first empty slot
     * Returns true, if item adding to inventory succeeded. False if it had to be dropped on ground
     */
    private boolean addItem(Inventory inventory, Material material) {
        HashMap<Integer, ? extends ItemStack> inventoryItems = inventory.all(material);
        for (ItemStack itemStack : inventoryItems.values()) {
            /* This assertion shouldn't fail */
            if (itemStack.getType() != material)
                throw new RuntimeException("itemStack.getType() != material!");

            /* If ItemStack has less items than max stack size, increment item count */
            if (itemStack.getAmount() < inventory.getMaxStackSize()) {
                itemStack.setAmount(itemStack.getAmount() + 1);
                return false;
            }

            /* continue; // Do same thing with next ItemStack */
        }

        /* Create new ItemStack, if inventory has enough space */
        int firstEmpty = inventory.firstEmpty();
        if (firstEmpty != -1) {
            /* Add new item to inventory */
            inventory.setItem(firstEmpty, new ItemStack(material, 1));
        } else {
            /* This assertion shouldn't fail */
            if (!(inventory.getHolder() instanceof Player)) {
                throw new RuntimeException("inventory.getHolder() != Player");
            }

            /* Drop item on the ground */
            Player player = (Player) inventory.getHolder();
            Location playerLoc = player.getLocation();
            Objects.requireNonNull(playerLoc.getWorld()).dropItemNaturally(playerLoc, new ItemStack(material, 1));
            return true;
        }
        return false;
    }

    /* Configuration serializable [Material, Material, int] class */
    private static class MaterialCompactorInfo implements ConfigurationSerializable {
        /* Fields are final, because they shouldn't be mutable */
        final Material sourceMaterial;
        final int sourceCount;
        final Material targetMaterial;

        /* Constructor for given class */
        MaterialCompactorInfo(Map<String, Object> serialized) {
            this.sourceMaterial = Material.valueOf((String) serialized.get("sourceMaterial"));
            this.sourceCount = (Integer) serialized.get("sourceCount");
            this.targetMaterial = Material.valueOf((String) serialized.get("targetMaterial"));
        }

        /* Methods needed to implement Bukkit's ConfigurationSerializable interface */
        @Override
        public Map<String, Object> serialize() {
            return new HashMap<String, Object>() {{
                put("sourceMaterial", sourceMaterial);
                put("sourceCount", sourceCount);
                put("targetMaterial", targetMaterial);
            }};
        }

    }
}
