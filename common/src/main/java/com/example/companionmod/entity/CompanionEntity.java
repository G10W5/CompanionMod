package com.example.companionmod.entity;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.ai.CompanionBuildGoal;
import com.example.companionmod.entity.ai.CompanionFarmGoal;
import com.example.companionmod.entity.ai.CompanionFollowGoal;
import com.example.companionmod.entity.ai.CompanionFishGoal;
import com.example.companionmod.entity.ai.CompanionPatrolGoal;
import com.example.companionmod.entity.ai.CompanionPickupGoal;
import com.example.companionmod.entity.ai.CompanionPlantGoal;
import com.example.companionmod.entity.ai.CompanionSeekBlockGoal;
import com.example.companionmod.entity.ai.CompanionSitGoal;
import com.example.companionmod.entity.ai.CompanionTargetGoal;
import com.example.companionmod.entity.ai.CompanionWanderGoal;
import com.example.companionmod.entity.plan.CompanionPlanner;
import com.example.companionmod.entity.task.MineTask;
import com.example.companionmod.inventory.CompanionInventoryMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import com.example.companionmod.world.CompanionRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A player-shaped companion owned by one player.
 * State: MoveMode (what its legs do) x Stance (what it fights). Orders arrive through {@link #applyCommand}.
 * Everything gameplay-related runs server-side; synced data (MoveMode/Stance/Owner/Skin/Feminine/Speech/Injured)
 * is read by the client renderer, HUD and goals.
 *
 * <p>Inventory: 26 slots (4 armor, 4 tools, 18 storage) in one {@link SimpleContainer}. Armor and the first
 * tool are mirrored onto the equipment slots so they render and actually protect/heal... protect the companion.
 *
 * <p>Downed state: lethal damage converts into an injured crawl (Mojang's swimming pose). The companion cannot
 * act; the player revives it by holding left mouse button on it for 3 seconds. It bleeds out after 60 s.
 */
public class CompanionEntity extends PathfinderMob implements com.example.companionmod.entity.plan.PlanActions {
    public enum MoveMode { FOLLOW, STAY, SIT, PATROL, WANDER }
    /** DEFENSIVE: fight what threatens owner/self. GUARD: also attack any hostile near the owner. PASSIVE: never start a fight. */
    public enum Stance { DEFENSIVE, GUARD, PASSIVE }

    private static final EntityDataAccessor<Integer> DATA_MOVE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STANCE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_OWNER = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_FEMININE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PICKUP = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    /** Short progress text for the radial menu ("Wood 6/16"); empty when idle. Updated by the server every half second. */
    private static final EntityDataAccessor<String> DATA_TASK = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SPEECH = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SPEECH_ID = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_INJURED = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_REVIVE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_INJURED_LEFT = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);

    /** Aliases the language model is likely to produce -> real block ids or family ids. */
    private static final Map<String, String> BLOCK_ALIASES = Map.ofEntries(
            Map.entry("diamond", "diamond_ore"), Map.entry("diamonds", "diamond_ore"),
            Map.entry("coal", "coal_ore"), Map.entry("iron", "iron_ore"),
            Map.entry("gold", "gold_ore"), Map.entry("copper", "copper_ore"),
            Map.entry("redstone", "redstone_ore"), Map.entry("lapis", "lapis_ore"),
            Map.entry("emerald", "emerald_ore"), Map.entry("quartz", "nether_quartz_ore"),
            Map.entry("netherite", "ancient_debris"), Map.entry("debris", "ancient_debris"),
            Map.entry("ancient_debris", "ancient_debris"), Map.entry("diamond_block", "diamond_block"),
            Map.entry("iron_block", "iron_block"), Map.entry("gold_block", "gold_block"),
            Map.entry("rock", "stone"), Map.entry("rocks", "stone"), Map.entry("cobble", "cobblestone"),
            Map.entry("soil", "dirt"), Map.entry("timber", "log"), Map.entry("lumber", "log"),
            Map.entry("tree", "log"), Map.entry("trees", "log"), Map.entry("trunk", "log"),
            Map.entry("wood_planks", "planks"), Map.entry("wooden_planks", "planks"));
    /** Not one block but a whole family: any log/wood, any planks, any leaves, any wool. */
    private static final Set<String> FAMILY_IDS = Set.of("log", "wood", "planks", "plank", "leaves", "wool");

    public static final EquipmentSlot[] ARMOR_SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    // Storage layout: 0-3 armor, 4-7 tools, 8-25 main (18 slots).
    public static final int SIZE = 26, ARMOR_START = 0, TOOL_START = 4, MAIN_START = 8, MAIN_SIZE = 18;

    private UUID ownerUuid;
    private LivingEntity forcedTarget;
    private int forcedTargetUntil;
    private String lastSpokenText = "";
    private long lastSpokenTime = 0;
    private int lastSpokenTick = -10000;

    private BlockPos anchor;                 // patrol / wander centre
    private int patrolIndex;

    // "go and find/mine this block" job (server side, saved with the entity)
    private final MineTask mineTask = new MineTask();
    // Assigned in registerGoals() - NOT a field initializer: registerGoals runs from the Mob constructor,
    // before this subclass's field initializers, so an initialized field would still be null there (this-escape).
    private com.example.companionmod.entity.ai.CompanionSeekBlockGoal seekGoal;
    // "pick this up from the ground" order (server side)
    private String pickupFilter = "";
    private boolean pickupRequested;

    // Inventory: armor row, tool row, main storage.
    private final SimpleContainer storage = new SimpleContainer(SIZE);

    // Downed / revive state.
    private boolean forceDeath;              // set when the revive timer runs out: this death is real
    private DamageSource injurySource;
    private long lastRevivePulse;
    private float reviveProgress;
    private int injuredLeftTicks;
    private static final int REVIVE_SECONDS = 60;
    private static final float REVIVE_TICKS = 3.0f * 20.0f;   // 3 s of holding left mouse

    // AI plan executor (the model never runs anything itself; it only returns validated plan data).
    private final CompanionPlanner planner = new CompanionPlanner();
    private boolean applyingPlanStep;
    private boolean registryChecked;

    // job state (time-boxed / schematic-driven behaviours)
    private long farmUntil, fishUntil, plantUntil;
    private String plantFilter = "";
    private String buildSchematic = "";
    private int selectedToolSlot = 0;          // which tool slot is held in the main hand
    private int targetSinceTick;               // when the current automatic target was acquired
    private long lastOuchTick;                 // hurt chatter throttle
    private String lastPlanJson = "";          // last plan, so "continue" can resume it

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanFloat(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 2.0)   // weapon modifiers add on top
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 0.0);
    }

    // ------------------------------------------------------------------ goals

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new CompanionSitGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));   // villager-style: open wooden doors on the path, close behind
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.25, true));
        seekGoal = new com.example.companionmod.entity.ai.CompanionSeekBlockGoal(this);
        goalSelector.addGoal(2, seekGoal);                           // explicit "find diamonds" pre-empts walking
        goalSelector.addGoal(3, new CompanionPickupGoal(this));      // explicit "pick this up" pre-empts walking
        goalSelector.addGoal(4, new CompanionFarmGoal(this));
        goalSelector.addGoal(4, new CompanionPlantGoal(this));        // time-boxed jobs (farm / fish / build)
        goalSelector.addGoal(4, new CompanionFishGoal(this));
        goalSelector.addGoal(4, new CompanionBuildGoal(this));
        goalSelector.addGoal(4, new CompanionPatrolGoal(this));
        goalSelector.addGoal(4, new CompanionWanderGoal(this));
        goalSelector.addGoal(5, new CompanionFollowGoal(this, 1.1, 4.0, 2.0, 24.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new CompanionTargetGoal(this));
    }

    // ------------------------------------------------------------------ synced + saved state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOVE, MoveMode.FOLLOW.ordinal());
        builder.define(DATA_STANCE, Stance.DEFENSIVE.ordinal());
        builder.define(DATA_OWNER, "");
        builder.define(DATA_SKIN, "");
        builder.define(DATA_FEMININE, 0);
        builder.define(DATA_PICKUP, 0);
        builder.define(DATA_TASK, "");
        builder.define(DATA_SPEECH, "");
        builder.define(DATA_SPEECH_ID, 0);
        builder.define(DATA_INJURED, 0);
        builder.define(DATA_REVIVE, 0.0f);
        builder.define(DATA_INJURED_LEFT, 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) { // 26.1-CHECK: ValueOutput/ValueInput replaced CompoundTag in 1.21.6
        super.addAdditionalSaveData(out);
        out.putInt("MoveMode", moveMode().ordinal());
        out.putInt("Stance", stance().ordinal());
        if (ownerUuid != null) out.putString("Owner", ownerUuid.toString());
        out.putString("Skin", skin());
        out.putBoolean("Feminine", isFeminine());
        out.putBoolean("Pickup", isPickupEnabled());
        out.putString("PickupFilter", pickupFilter);
        mineTask.save(out);
        out.store("Storage", ItemStack.OPTIONAL_CODEC.listOf(), storage.getItems());
        out.putBoolean("Injured", isInjured());
        out.putInt("InjuredLeft", injuredLeftTicks);
        out.putFloat("Revive", reviveProgress);
        if (anchor != null) out.putIntArray("Anchor", new int[]{anchor.getX(), anchor.getY(), anchor.getZ()});
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setMoveMode(MoveMode.values()[Math.floorMod(in.getIntOr("MoveMode", 0), MoveMode.values().length)]);
        setStance(Stance.values()[Math.floorMod(in.getIntOr("Stance", 0), Stance.values().length)]);
        String o = in.getStringOr("Owner", "");
        if (!o.isEmpty()) {
            try { setOwner(UUID.fromString(o)); } catch (IllegalArgumentException ignored) { }
        }
        setSkin(in.getStringOr("Skin", ""));
        setFeminine(in.getBooleanOr("Feminine", false));
        setPickupEnabled(in.getBooleanOr("Pickup", false));
        pickupFilter = in.getStringOr("PickupFilter", "");
        mineTask.load(in);
        in.read("Storage", ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(list -> {
            for (int i = 0; i < Math.min(list.size(), storage.getContainerSize()); i++) {
                storage.setItem(i, list.get(i));
            }
        });
        boolean injured = in.getBooleanOr("Injured", false);
        injuredLeftTicks = in.getIntOr("InjuredLeft", 0);
        reviveProgress = in.getFloatOr("Revive", 0.0f);
        if (injured) {
            entityData.set(DATA_INJURED, 1);
            entityData.set(DATA_REVIVE, reviveProgress);
            entityData.set(DATA_INJURED_LEFT, injuredLeftTicks);
            setPose(Pose.SWIMMING);
        }
        int[] a = in.getIntArray("Anchor").orElse(new int[0]);
        if (a.length == 3) anchor = new BlockPos(a[0], a[1], a[2]);
        refreshEquipment();
    }

    public MoveMode moveMode() { return MoveMode.values()[entityData.get(DATA_MOVE)]; }
    public Stance stance() { return Stance.values()[entityData.get(DATA_STANCE)]; }
    public void setMoveMode(MoveMode m) { entityData.set(DATA_MOVE, m.ordinal()); }
    public void setStance(Stance s) { entityData.set(DATA_STANCE, s.ordinal()); }
    public boolean isSitting() { return moveMode() == MoveMode.SIT; }

    public String skin() { return entityData.get(DATA_SKIN); }
    /** Accepts "", "owner", "default" or a plain Minecraft name; anything else is ignored (every nearby client would look it up). */
    public void setSkin(String skin) {
        String s = skin == null ? "" : skin.trim();
        boolean ok = s.isEmpty() || s.equalsIgnoreCase("owner") || s.equalsIgnoreCase("default") || s.matches("[A-Za-z0-9_]{1,16}");
        if (ok) entityData.set(DATA_SKIN, s);
    }
    public boolean isFeminine() { return entityData.get(DATA_FEMININE) != 0; }
    public void setFeminine(boolean value) { entityData.set(DATA_FEMININE, value ? 1 : 0); }
    public boolean isPickupEnabled() { return entityData.get(DATA_PICKUP) != 0; }
    public void setPickupEnabled(boolean value) { entityData.set(DATA_PICKUP, value ? 1 : 0); }

    public String speech() { return entityData.get(DATA_SPEECH); }
    public int speechId() { return entityData.get(DATA_SPEECH_ID); }

    public boolean isInjured() { return entityData.get(DATA_INJURED) != 0; }
    public float reviveProgress() { return entityData.get(DATA_REVIVE); }
    public int injuredLeftTicks() { return entityData.get(DATA_INJURED_LEFT); }

    public MineTask mineTask() { return mineTask; }

    public long farmUntil() { return farmUntil; }
    public long plantUntil() { return plantUntil; }
    public String plantFilter() { return plantFilter; }
    public void endPlant() { plantUntil = 0; plantFilter = ""; }
    public String taskText() { return entityData.get(DATA_TASK); }
    /** Called by work goals whenever they make real progress, so a timed AI plan step is not cut off mid-job. */
    public void noteMiningProgress() { planner.extendDeadline(tickCount, 600); }
    public long fishUntil() { return fishUntil; }
    public String buildSchematic() { return buildSchematic; }
    /** Clears the current build job (called by the build goal when it finishes). */
    public void clearBuild() { buildSchematic = ""; }

    public String seekBlock() { return mineTask.exists() ? mineTask.blockId : ""; }
    /** Starts (or restarts) a mining job; a blank id cancels it. */
    public void setSeekBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) mineTask.finish();
        else {
            boolean nether = level().dimension() == net.minecraft.world.level.Level.NETHER;
            mineTask.begin(blockId, nether, getDirection().get2DDataValue());
        }
    }
    public void clearSeekBlock() { mineTask.finish(); }

    public String pickupFilter() { return pickupFilter == null ? "" : pickupFilter; }
    public boolean isPickupRequested() { return pickupRequested; }

    public BlockPos anchor() { return anchor == null ? blockPosition() : anchor; }
    public void setAnchor(BlockPos pos) { anchor = pos; }
    public int patrolIndex() { return patrolIndex; }
    public void setPatrolIndex(int i) { patrolIndex = i; }

    public SimpleContainer storage() { return storage; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwner(UUID uuid) { ownerUuid = uuid; entityData.set(DATA_OWNER, uuid == null ? "" : uuid.toString()); }
    /** Works on both sides (the synced string), used by the client to find "my" companion. */
    public boolean isOwnedBy(String uuidString) { return !uuidString.isEmpty() && uuidString.equals(entityData.get(DATA_OWNER)); }
    public Player getOwner() { return ownerUuid == null ? null : level().getPlayerByUUID(ownerUuid); }
    public boolean isOwner(Player p) { return ownerUuid != null && ownerUuid.equals(p.getUUID()); }

    // ------------------------------------------------------------------ orders

    public void applyCommand(CompanionCommand cmd, ServerPlayer from) {
        applyCommand(cmd, from, "");
    }

    public void applyCommand(CompanionCommand cmd, ServerPlayer from, String target) {
        if (!applyingPlanStep && cmd.interruptsPlanning()) {
            // One explicit intent at a time: a manual order pauses the AI plan (STOP forgets it entirely).
            planner.notePlayerOrder(tickCount);
        }
        switch (cmd) {
            case FOLLOW -> { clearSeekBlock(); setMoveMode(MoveMode.FOLLOW); }
            case STAY, WAIT -> { clearSeekBlock(); setMoveMode(MoveMode.STAY); setAnchor(blockPosition()); getNavigation().stop(); }
            case SIT -> { clearSeekBlock(); setMoveMode(MoveMode.SIT); getNavigation().stop(); setTarget(null); }
            case STAND -> { if (isSitting()) setMoveMode(MoveMode.FOLLOW); }
            case PATROL -> {
                clearSeekBlock();
                setMoveMode(MoveMode.PATROL);
                setAnchor(from == null ? blockPosition() : from.blockPosition());
                setPatrolIndex(0);
            }
            case WANDER, EXPLORE, SCOUT -> {
                clearSeekBlock();
                setMoveMode(MoveMode.WANDER);
                setAnchor(from == null ? blockPosition() : from.blockPosition());
            }
            case ATTACK, FIGHT, HUNT -> {
                clearSeekBlock();
                if (isSitting()) setMoveMode(MoveMode.FOLLOW);
                if (stance() == Stance.PASSIVE) setStance(Stance.DEFENSIVE);
                LivingEntity t = findTarget(from, target);
                if (t == null) t = from == null ? null : pickAttackTarget(from);
                if (t != null) { forcedTarget = t; forcedTargetUntil = tickCount + 600; setTarget(t); }
            }
            case GUARD, DEFEND -> { if (isSitting()) setMoveMode(MoveMode.FOLLOW); setStance(Stance.GUARD); }
            case DEFENSIVE -> { setStance(Stance.DEFENSIVE); forcedTarget = null; setTarget(null); }
            case PASSIVE -> { setStance(Stance.PASSIVE); forcedTarget = null; setTarget(null); getNavigation().stop(); }
            case COME -> {
                clearSeekBlock();
                if (isSitting()) setMoveMode(MoveMode.FOLLOW);
                if (from != null) {
                    // A "come" that is part of a plan (bringing the loot back) walks like a person; only a spoken "come" warps.
                    if (applyingPlanStep && from.level() == level() && distanceToSqr(from) < 48 * 48) setMoveMode(MoveMode.FOLLOW);
                    else teleportNear(from);
                }
            }
            case RETREAT, FLEE -> {
                clearSeekBlock();
                setStance(Stance.PASSIVE);
                forcedTarget = null;
                setTarget(null);
                if (from != null) teleportNear(from);
            }
            case STOP -> { hardStop(); speak("Stopped."); }
            case INVENTORY -> { if (from != null) openInventory(from); }
            case HEAL -> {
                heal(6.0f);
                Player owner = getOwner();
                if (owner != null && owner.distanceToSqr(this) < 144) owner.heal(4.0f);
            }
            case EQUIP -> equipByName(target == null ? "" : target);
            // "pick up this bone" -> walk to the dropped item and store it. "collect wood" (a block, and nothing
            // matching is lying around) -> go mine it instead. Never opens the inventory.
            case PICKUP, LOOT, TAKE -> {
                String filter = target == null ? "" : target.trim();
                String id = normalizeBlockId(filter);
                if (!id.isBlank() && !isPlantId(id) && !anyMatchingDrops(filter)) {   // saplings/flowers are items to pick up, never blocks to break
                    clearSeekBlock();
                    setSeekBlock(id);
                    setAnchor(blockPosition());
                    getNavigation().stop();
                    speak("Looking for " + prettyBlockId(id) + ".");
                } else requestPickup(filter);
            }
            case DROP, GIVE -> dropOneItem();
            // "find diamonds / coal" -> walk to the nearest matching block and mine it.
            case SEEK_BLOCK, MINE, FIND, SEEK, SEARCH -> {
                String id = normalizeBlockId(target);
                if (id.isBlank()) {
                    // Not a block (or no target): fall back to scouting around instead of doing nothing.
                    setMoveMode(MoveMode.WANDER);
                    setAnchor(blockPosition());
                    speak(target == null || target.isBlank()
                            ? "What should I look for?"
                            : "I don't know where to find " + target + ". I'll scout around.");
                } else {
                    clearSeekBlock();
                    setSeekBlock(id);
                    setAnchor(blockPosition());
                    getNavigation().stop();
                    speak("Looking for " + prettyBlockId(id) + ".");
                }
            }
            case SET_NAME -> {
                String n = sanitizeName(target);
                if (!n.isBlank()) {
                    setCustomName(Component.literal(n));
                    setCustomNameVisible(false);
                }
            }
            case SET_SKIN -> setSkin(target == null ? "" : target.trim());
            case FEMININE -> setFeminine(true);
            case MASCULINE -> setFeminine(false);
            case REVIVE -> {
                // Must be the owner and actually next to the body (the client sends pulses while holding left click).
                if (from != null && isOwner(from) && from.distanceToSqr(this) <= 49.0) revivePulse();
            }
            case PLAN -> { lastPlanJson = target == null ? "" : target; planner.accept(target, this, tickCount); }
            case CONTINUE -> {
                // Resume whatever was interrupted: a paused AI plan first, then a stuck/paused mining job,
                // then (last resort) the last plan JSON the model gave us.
                if (planner.resume(tickCount)) speak("Continuing the plan.");
                else if (mineTask().running() || mineTask().resume()) speak("Back to work.");
                else if (!lastPlanJson.isBlank() && planner.accept(lastPlanJson, this, tickCount)) speak("Restarting the plan.");
                else speak("There is nothing to continue.");
            }
            case PLANT -> {
                clearSeekBlock();
                plantFilter = target == null ? "" : target.toLowerCase(Locale.ROOT).trim().replace("minecraft:", "")
                        .replace(' ', '_').replace("saplings", "sapling");
                plantUntil = level().getGameTime() + 1200;
                setAnchor(blockPosition());
                getNavigation().stop();
            }
            case FARM -> { farmUntil = level().getGameTime() + 600; setAnchor(blockPosition()); speak("Farming."); }
            case FISH -> { fishUntil = level().getGameTime() + 600; setAnchor(blockPosition()); speak("Going fishing."); }
            case BUILD -> {
                String name = target == null ? "" : target.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
                if (name.isEmpty()) speak("Build what? Give me a schematic name.");
                else { buildSchematic = name; setAnchor(blockPosition()); speak("Building " + name + "."); }
            }
            case REMEMBER, FORGET -> { /* handled client-side by CompanionMemory */ }
            default -> { }
        }
    }

    /** Opens the companion's 26-slot inventory (armor / tools / storage) in a custom screen. */
    public void openInventory(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new CompanionInventoryMenu(id, inv, this),
                Component.literal(getName().getString() + "'s Inventory")));
    }

    /**
     * Emergency stop: cancel every kind of queued or time-boxed work, clear combat, and hold this spot.
     * Unlike STAY, this also forgets the last plan, so CONTINUE cannot bring the cancelled work back.
     */
    public void hardStop() {
        planner.cancel();
        lastPlanJson = "";
        clearSeekBlock();
        finishPickup(false);
        farmUntil = 0;
        plantUntil = 0;
        fishUntil = 0;
        clearBuild();
        forcedTarget = null;
        forcedTargetUntil = 0;
        setStance(Stance.PASSIVE);
        setTarget(null);
        getNavigation().stop();
        setMoveMode(MoveMode.STAY);
        setAnchor(blockPosition());
    }

    /**
     * Floating subtitle above the companion's head. The text is synced (never the entity's real name),
     * so the client renderer can animate it rising and fading while the name tag stays untouched.
     */
    public void speak(String text) {
        if (!(level() instanceof ServerLevel)) return;
        if (text == null || text.isBlank()) return;
        this.lastSpokenText = text;
        this.lastSpokenTime = System.currentTimeMillis();
        this.lastSpokenTick = tickCount;
        entityData.set(DATA_SPEECH, text);
        entityData.set(DATA_SPEECH_ID, entityData.get(DATA_SPEECH_ID) + 1);
    }

    public String lastSpokenText() { return lastSpokenText; }

    // ------------------------------------------------------------------ inventory

    /** True when the dropped item is what the player asked for (empty filter = anything). */
    public boolean matchesPickup(ItemEntity item) {
        return item != null && itemMatchesFilter(pickupFilter(), item.getItem());
    }

    /** Explicit "pick this up" order: walk to the matching dropped item instead of opening the inventory. */
    public void requestPickup(String filter) {
        pickupFilter = filter == null ? "" : filter.trim();
        pickupRequested = true;
        setPickupEnabled(true);
        getNavigation().stop();
    }

    /** Ends an explicit pickup order; {@code storedAny} controls whether the companion confirms out loud. */
    public void finishPickup(boolean storedAny) {
        if (pickupRequested && storedAny) {
            speak(pickupFilter.isBlank() ? "Picked up the items."
                    : "Picked up " + pickupFilter.replace('_', ' ') + ".");
        }
        pickupRequested = false;
        pickupFilter = "";
        setPickupEnabled(false);
    }

    /** True when a dropped item matching the words is on the ground nearby (so "collect" should loot, not mine). */
    private boolean anyMatchingDrops(String filter) {
        if (filter == null || filter.isBlank()) return true;      // bare "pick up" = ground sweep
        for (ItemEntity e : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(16),
                x -> x.isAlive() && !x.getItem().isEmpty())) {
            if (itemMatchesFilter(filter, e.getItem())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ lenient item matching (voice = wheel)

    /**
     * One shared, STT-forgiving matcher for every item filter so a voice order ("pick up golden carrots")
     * finds exactly what the radial wheel's unfiltered pick finds. Lowercase, spaces/dashes become
     * separators, filler words ("pick up some the...") are dropped, plural endings are relaxed, and each
     * remaining word must appear somewhere in the item id ("golden_carrot") or hover name ("Golden Carrot").
     */
    public static boolean itemMatchesFilter(String filter, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (filter == null || filter.isBlank()) return true;
        List<String> tokens = filterTokens(filter);
        if (tokens.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!wordIn(id, token) && !wordIn(name, token)) return false;
        }
        return true;
    }

    /** Lowercase word tokens of a filter, with filler/command words stripped wherever they appear. */
    private static List<String> filterTokens(String filter) {
        List<String> out = new ArrayList<>();
        for (String raw : filter.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isBlank() || FILLER_WORDS.contains(raw)) continue;   // "pick up some golden carrots" -> "golden carrots"
            out.add(raw);
        }
        return out;
    }

    private static final Set<String> FILLER_WORDS = Set.of(
            "a", "an", "the", "some", "any", "all", "that", "this", "those", "these", "it", "its", "my", "me",
            "pick", "up", "please", "get", "got", "grab", "take", "takes", "bring", "collect", "loot");

    /** Words that mean "anything usable" when they stand alone. */
    private static final Set<String> FAMILY_WORDS = Set.of(
            "weapon", "tool", "gear", "equipment", "something", "item");

    /** Whole-word or contained match, tolerant of plural endings ("carrots" finds "golden_carrot"). */
    private static boolean wordIn(String haystack, String token) {
        if (haystack.isBlank() || token.isBlank()) return false;
        if (haystack.contains(token)) return true;
        if (token.endsWith("ies") && token.length() > 4
                && haystack.contains(token.substring(0, token.length() - 3) + "y")) return true; // berries -> berry
        if (token.endsWith("s") && token.length() > 3
                && haystack.contains(token.substring(0, token.length() - 1))) return true;       // carrots -> carrot
        return false;
    }

    /**
     * Stores a stack in its real category: matching armor, then the one matching
     * pickaxe/axe/shovel/sword slot, then general storage. Never displaces gear.
     */
    public ItemStack store(ItemStack stack) {
        if (stack.isEmpty()) return stack;
        ItemStack rest = stack.copy();
        EquipmentSlot armorSlot = armorSlotFor(rest);
        if (armorSlot != null) {
            int idx = ARMOR_START + armorSlotIndex(armorSlot);
            if (storage.getItem(idx).isEmpty()) {
                storage.setItem(idx, rest.split(rest.getCount()));
                storage.setChanged();
                refreshEquipment();
                return ItemStack.EMPTY;
            }
        }
        int toolSlot = CompanionTools.dedicatedSlot(rest);
        if (toolSlot >= 0 && storage.getItem(toolSlot).isEmpty()) {
            storage.setItem(toolSlot, rest.split(rest.getCount()));
            storage.setChanged();
            refreshEquipment();
            return ItemStack.EMPTY;
        }
        ItemStack left = insertMain(rest);
        storage.setChanged();
        refreshEquipment();
        return left;
    }

    private ItemStack insertMain(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = MAIN_START; i < MAIN_START + MAIN_SIZE && !rest.isEmpty(); i++) {
            ItemStack in = storage.getItem(i);
            if (in.isEmpty()) {
                int count = Math.min(rest.getCount(), rest.getMaxStackSize());
                storage.setItem(i, rest.split(count));
            } else if (ItemStack.isSameItemSameComponents(in, rest)) {
                int space = Math.min(in.getMaxStackSize(), storage.getMaxStackSize()) - in.getCount();
                if (space > 0) {
                    int count = Math.min(space, rest.getCount());
                    in.grow(count);
                    rest.shrink(count);
                }
            }
        }
        return rest;
    }

    /** Sweeps up dropped items around the companion (used by LOOT and the seek goal's mining). */
    public void collectNearbyItems() {
        if (!(level() instanceof ServerLevel)) return;
        List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(10),
                e -> e.isAlive() && !e.getItem().isEmpty() && matchesPickup(e));
        for (ItemEntity item : items) storeItem(item);
    }

    /** Stores a single dropped item; returns true when the whole stack fitted. */
    public boolean storeItem(ItemEntity item) {
        if (item == null || item.getItem().isEmpty()) return false;
        ItemStack rest = store(item.getItem());
        if (rest.isEmpty()) { item.discard(); return true; }
        item.setItem(rest);
        return false;
    }

    private void dropOneItem() {
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack one = storage.removeItem(i, 1);
            ItemEntity drop = new ItemEntity(level(), getX(), getY() + 1.1, getZ(), one);
            drop.setDeltaMovement(random.nextGaussian() * 0.08, 0.25, random.nextGaussian() * 0.08);
            level().addFreshEntity(drop);
            return;
        }
    }

    /** Migrates legacy/misplaced items without deleting, dropping or overwriting anything. */
    private void normalizeToolSlots() {
        for (int offset = 0; offset < 4; offset++) {
            int slot = TOOL_START + offset;
            ItemStack stack = storage.getItem(slot);
            if (stack.isEmpty() || CompanionTools.dedicatedSlot(stack) == slot) continue;
            int correct = CompanionTools.dedicatedSlot(stack);
            if (correct >= 0 && storage.getItem(correct).isEmpty()) {
                storage.setItem(slot, ItemStack.EMPTY);   // move into its free dedicated slot
                storage.setItem(correct, stack);
            }
            // otherwise leave it where the player put it: tool slots are universal now
        }
        storage.setChanged();
    }

    /** Mirrors armor and the deliberately selected tool onto real equipment slots. */
    public void refreshEquipment() {
        if (level().isClientSide()) return;
        normalizeToolSlots();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            EquipmentSlot slot = ARMOR_SLOTS[i];
            ItemStack want = storage.getItem(ARMOR_START + i);
            ItemStack current = getItemBySlot(slot);
            if (!want.isEmpty() && isValidArmor(want, slot)) {
                if (!ItemStack.matches(current, want)) setItemSlot(slot, want.copy());
            } else if (!current.isEmpty()) setItemSlot(slot, ItemStack.EMPTY);
        }
        // Tool slots are universal now: hold whatever the player put there, regardless of item family.
        if (selectedToolSlot >= 0 && selectedToolSlot < 4 && storage.getItem(TOOL_START + selectedToolSlot).isEmpty()) {
            selectedToolSlot = -1;
        }
        if (selectedToolSlot < 0) {
            for (int i = 0; i < 4; i++) {
                if (!storage.getItem(TOOL_START + i).isEmpty()) { selectedToolSlot = i; break; }
            }
        }
        ItemStack tool = selectedToolSlot >= 0 ? storage.getItem(TOOL_START + selectedToolSlot) : ItemStack.EMPTY;
        if (!ItemStack.matches(getMainHandItem(), tool)) setItemSlot(EquipmentSlot.MAINHAND, tool.copy());
    }

    private static int armorSlotIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        Equippable e = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        if (e == null) return null;
        return switch (e.slot()) {
            case HEAD, CHEST, LEGS, FEET -> e.slot();
            default -> null;
        };
    }

    private static boolean isValidArmor(ItemStack stack, EquipmentSlot slot) {
        Equippable e = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return e != null && e.slot() == slot;
    }

    private static boolean isTool(ItemStack stack) {
        return CompanionTools.isDedicatedTool(stack);
    }

    // ------------------------------------------------------------------ injured / revive

    /** Lethal damage converts into the downed state instead of death (first time only). */
    private void injure(DamageSource source) {
        this.injurySource = source;
        this.injuredLeftTicks = 20 * REVIVE_SECONDS;
        this.reviveProgress = 0f;
        this.lastRevivePulse = 0;
        entityData.set(DATA_INJURED, 1);
        entityData.set(DATA_REVIVE, 0f);
        entityData.set(DATA_INJURED_LEFT, injuredLeftTicks);
        setHealth(1.0f);
        forcedTarget = null;
        setTarget(null);
        clearSeekBlock();
        pickupRequested = false;
        pickupFilter = "";
        setPickupEnabled(false);
        if (isSitting()) setMoveMode(MoveMode.STAY);
        getNavigation().stop();
        speak("I'm down! Hold left click on me to revive me!");
        makeMobsForgetMe();
    }

    /** Called from the REVIVE order while the player holds left mouse button on the companion. */
    public void revivePulse() {
        if (!isInjured()) return;
        lastRevivePulse = System.currentTimeMillis();
    }

    /** Revives the companion (used by the revive command for testing and by the revive timer). */
    public void revive() {
        entityData.set(DATA_INJURED, 0);
        entityData.set(DATA_REVIVE, 0f);
        entityData.set(DATA_INJURED_LEFT, 0);
        reviveProgress = 0f;
        setHealth(getMaxHealth());
        setPose(Pose.STANDING);
        speak("Thanks, I'm back on my feet!");
    }

    /** The 60 s revive window ran out: drop the gear and really die. */
    private void bleedOut() {
        speak("I'm fading...");
        dropStorageAndClear();
        forceDeath = true;
        die(injurySource != null ? injurySource : level().damageSources().generic());
    }

    public boolean isReviving() {
        return isInjured() && System.currentTimeMillis() - lastRevivePulse < 700;
    }

    // ------------------------------------------------------------------ PlanActions (AI plan execution)

    /** Accepts a plan (JSON from the client or a subcommand). Returns true if a plan was started. */
    public boolean plan(String json) {
        return planner.accept(json, this, tickCount);
    }

    @Override public Player planOwner() { return getOwner(); }

    @Override
    public void planCommand(CompanionCommand cmd, String arg) {
        applyingPlanStep = true;
        try {
            ServerPlayer owner = getOwner() instanceof ServerPlayer sp ? sp : null;
            applyCommand(cmd, owner, arg);
        } finally {
            applyingPlanStep = false;
        }
    }

    @Override public void planSay(String text) { speak(text); }
    @Override public boolean planSeekActive() { return !seekBlock().isBlank(); }
    @Override public boolean planPlantActive() { return plantUntil > level().getGameTime(); }
    @Override public boolean planPickupActive() { return isPickupRequested() || isPickupEnabled(); }
    @Override public boolean planFighting() { LivingEntity t = getTarget(); return t != null && t.isAlive(); }
    @Override public boolean planPaused() { return isInjured(); }
    @Override public boolean planPlayerHold() { return isSitting() || moveMode() == MoveMode.STAY; }

    // ------------------------------------------------------------------ targeting (called by CompanionTargetGoal)

    public boolean isValidTarget(LivingEntity e, Player owner) {
        return e != this && e != owner && e.isAlive() && e instanceof Enemy && !(e instanceof CompanionEntity);
    }

    /** Resolves "attack this creeper" style orders. Accepts a mob name, entity id, or "nearest". */
    private LivingEntity findTarget(Player owner, String rawTarget) {
        if (owner == null) return null;
        String wanted = rawTarget == null ? "" : rawTarget.toLowerCase(Locale.ROOT).trim()
                .replace("the ", "").replace("a ", "").replace("an ", "").trim();
        if (wanted.isEmpty() || wanted.equals("nearest") || wanted.equals("closest") || wanted.equals("enemy"))
            return pickAttackTarget(owner);

        double radius = 40.0;
        List<LivingEntity> candidates = level().getEntitiesOfClass(LivingEntity.class,
                owner.getBoundingBox().inflate(radius), e -> e != this && e != owner && e.isAlive());
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            String id = EntityType.getKey(e.getType()).getPath();
            String typeName = e.getType().getDescription().getString().toLowerCase(Locale.ROOT);
            String customName = e.getName().getString().toLowerCase(Locale.ROOT);
            boolean match = wanted.equals(id) || wanted.equals(typeName)
                    || typeName.contains(wanted) || id.contains(wanted) || customName.contains(wanted);
            if (!match) continue;
            double d = owner.distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Picks what to fight right now, or null. Order: explicit order > who hurt owner > what owner hit > who hurt me > nearby threats. */
    public LivingEntity chooseTarget() {
        Player owner = getOwner();
        if (isSitting() || stance() == Stance.PASSIVE || isInjured()) return null;
        if (forcedTarget != null && (!forcedTarget.isAlive() || tickCount > forcedTargetUntil)) forcedTarget = null;
        if (forcedTarget != null) return forcedTarget;
        if (owner != null) {
            LivingEntity a = owner.getLastHurtByMob();
            if (a != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 200 && isValidTarget(a, owner) && visibleOrAggro(a, owner)) return a;
            LivingEntity b = owner.getLastHurtMob();
            if (b != null && owner.tickCount - owner.getLastHurtMobTimestamp() < 200 && isValidTarget(b, owner) && visibleOrAggro(b, owner)) return b;
        }
        LivingEntity c = getLastHurtByMob();
        if (c != null && c.isAlive() && c != owner && !(c instanceof CompanionEntity) && !(c instanceof Player) && visibleOrAggro(c, owner)) return c;

        boolean mining = mineTask().running();
        // While a mining job is running, only an adjacent threat interrupts it (a far zombie must not stall the dig).
        double r = mining ? 4 : (stance() == Stance.GUARD ? 16 : 6);
        var centre = (owner != null && moveMode() == MoveMode.FOLLOW) ? owner : this;
        List<LivingEntity> near = level().getEntitiesOfClass(LivingEntity.class, centre.getBoundingBox().inflate(r),
                e -> isValidTarget(e, owner) && distanceToSqr(e) < (mining ? 8 * 8 : 28 * 28));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : near) {
            Mob asMob = e instanceof Mob m ? m : null;
            // DEFENSIVE only reacts to mobs that are actually coming for us or the owner
            if (stance() == Stance.DEFENSIVE && asMob != null && asMob.getTarget() != owner && asMob.getTarget() != this) continue;
            // No attacking through walls: a mob we cannot see only counts if it is already fighting the owner,
            // or (when aggressive) fighting the companion itself. Everything else must be visible. Closer wins.
            if (!getSensing().hasLineOfSight(e)) {
                boolean aggroOnOwner = owner != null && asMob != null && asMob.getTarget() == owner;
                boolean aggroOnSelf = asMob != null && asMob.getTarget() == this;
                if (!aggroOnOwner && !(aggroOnSelf && stance() == Stance.GUARD)) continue;
            }
            double d = distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Wall-hack gate: a mob we cannot see only counts when it is already fighting the companion or the owner. */
    private boolean visibleOrAggro(LivingEntity e, Player owner) {
        if (getSensing().hasLineOfSight(e)) return true;
        if (e instanceof Mob m) return m.getTarget() == this || (owner != null && m.getTarget() == owner);
        return false;
    }

    /** True for the target the player explicitly ordered (kept through walls while it stays reachable). */
    public boolean isForced(LivingEntity e) { return e != null && e == forcedTarget; }

    /** Enemy the owner is looking at, else the enemy nearest to the owner. */
    private LivingEntity pickAttackTarget(Player owner) {
        List<LivingEntity> enemies = level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(24), e -> isValidTarget(e, owner));
        Vec3 eye = owner.getEyePosition(), look = owner.getLookAngle();
        LivingEntity best = null;
        double bestDot = 0.93;
        for (LivingEntity e : enemies) {
            double dot = look.dot(e.getEyePosition().subtract(eye).normalize());
            if (dot > bestDot) { bestDot = dot; best = e; }
        }
        if (best != null) return best;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : enemies) { double d = owner.distanceToSqr(e); if (d < bestD) { bestD = d; best = e; } }
        return best;
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && target != getTarget()) {
            targetSinceTick = tickCount;
            if (!level().isClientSide()) equipBestWeapon();
        }
        super.setTarget(target);
    }

    private void equipBestWeapon() {
        int sword = CompanionTools.bestSwordIndex(storage);
        if (sword >= 0) selectTool(sword);
    }

    /** Moves a storage tool into only its matching row slot, swapping safely if occupied. */
    private void selectTool(int sourceSlot) {
        if (sourceSlot < 0) return;
        ItemStack selected = storage.getItem(sourceSlot);
        int dedicated = CompanionTools.dedicatedSlot(selected);
        if (dedicated < 0) return;
        if (sourceSlot != dedicated) {
            ItemStack displaced = storage.getItem(dedicated);
            storage.setItem(dedicated, selected.copy());
            storage.setItem(sourceSlot, displaced.copy());
        }
        selectedToolSlot = dedicated - TOOL_START;
        storage.setChanged();
        refreshEquipment();
    }

    /**
     * Hostile mobs only smell players by default. Every so often the companion claims any monster that has no
     * target yet and can see it, so zombies etc. proactively come hunting instead of ignoring it. Their own
     * retargeting still wins whenever a player (or a fight) is available, and a downed/resting companion is left alone.
     */
    private void aggroMonsters() {
        if (isInjured() || isSitting() || stance() == Stance.PASSIVE) return;
        if (!(level() instanceof ServerLevel level)) return;
        int claimed = 0;
        for (Monster m : level.getEntitiesOfClass(Monster.class, getBoundingBox().inflate(16), e -> e.isAlive())) {
            if (m.getTarget() != null || !m.getSensing().hasLineOfSight(this)) continue;
            m.setTarget(this);
            if (++claimed >= 6) break;            // never pull a whole cave onto the companion in one pass
        }
    }

    /** Uses block tags plus actual destroy speed, then immediately syncs the held stack. */
    public void equipBestToolFor(net.minecraft.world.level.block.state.BlockState state) {
        int bestSlot = CompanionTools.bestToolIndex(storage, state);
        if (bestSlot < 0) {
            selectedToolSlot = -1;
            refreshEquipment();
            return;
        }
        selectTool(bestSlot);
    }

    /** Places a torch from the bag at a free adjacent floor cell when it is too dark. */
    public boolean placeTorch() {
        if (!(level() instanceof ServerLevel level)) return false;
        if (level.getMaxLocalRawBrightness(blockPosition()) >= 9) return false;   // bright enough already
        int slot = -1;
        for (int i = MAIN_START; i < MAIN_START + MAIN_SIZE; i++) {
            if (storage.getItem(i).getItem() == net.minecraft.world.item.Items.TORCH) { slot = i; break; }
        }
        if (slot < 0) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos torchPos = blockPosition().offset(dx, 0, dz);
                BlockPos floor = torchPos.below();
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
                if (!level.getBlockState(torchPos).isAir() || !level.getBlockState(torchPos.above()).isAir()) continue;
                level.setBlock(torchPos, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
                storage.removeItem(slot, 1);
                return true;
            }
        }
        return false;
    }

    /** "equip pickaxe" -> holds the best matching item: the dedicated tool row first, otherwise the general bag (moved into a free tool slot). Token matching, because STT output is fuzzy. */
    private void equipByName(String raw) {
        String want = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", " ").trim();
        List<String> tokens = new ArrayList<>();
        for (String w : want.split(" ")) {
            if (w.isBlank() || w.equals("my") || w.equals("the") || w.equals("a") || w.equals("an")
                    || w.equals("some") || w.equals("any")) continue;
            tokens.add(w);
        }
        boolean catchAll = tokens.isEmpty() || (tokens.size() == 1 && FAMILY_WORDS.contains(tokens.get(0)));
        int slot = pickEquippable(TOOL_START, 4, tokens, catchAll);
        // With universal slots a usable item may sit in general storage: bring it into a free tool slot first.
        if (slot < 0 && !catchAll) {
            int inBag = pickEquippable(MAIN_START, MAIN_SIZE, tokens, false);
            if (inBag >= 0) {
                for (int i = 0; i < 4; i++) {
                    int target = TOOL_START + i;
                    if (storage.getItem(target).isEmpty()) {
                        storage.setItem(target, storage.removeItem(inBag, 1));
                        storage.setChanged();
                        slot = target;
                        break;
                    }
                }
            }
        }
        if (slot >= 0) {
            selectedToolSlot = slot - TOOL_START;
            refreshEquipment();
            speak("Equipped the " + storage.getItem(slot).getHoverName().getString() + ".");
        } else {
            sayNoTool(want);
        }
    }

    /** Scores every candidate: an exact word match beats a substring match; synonyms keep fuzzy STT working. */
    private int pickEquippable(int from, int count, List<String> tokens, boolean catchAll) {
        int best = -1, bestScore = 0;
        for (int i = 0; i < count; i++) {
            ItemStack s = storage.getItem(from + i);
            if (s.isEmpty()) continue;
            if (catchAll) return from + i;                  // generic "equip": first non-empty slot
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            String name = s.getHoverName().getString().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String t : tokens) {
                int sc = Math.max(idWordScore(id, t), nameWordScore(name, t));
                if (sc == 0) sc = synonymIn(id, t) ? 1 : 0;
                score = Math.max(score, sc);
            }
            if (score > bestScore) { bestScore = score; best = from + i; }
        }
        return bestScore > 0 ? best : -1;
    }

    /** 3 when the token is a whole word in the id ("axe" matches "iron_axe" but not "pickaxe"), 1 on a bare substring. */
    private static int idWordScore(String haystack, String token) {
        for (String w : haystack.split("[^a-z0-9]+")) if (w.equals(token)) return 3;
        return haystack.contains(token) ? 1 : 0;
    }

    /** Same for the displayed name; a name word match scores slightly under an id word match. */
    private static int nameWordScore(String haystack, String token) {
        for (String w : haystack.split("[^a-z0-9]+")) if (w.equals(token)) return 2;
        return haystack.contains(token) ? 1 : 0;
    }

    /** Family keywords: "axe" shouldn't grab a pickaxe, but "weapon" covers swords, axes and tridents. */
    private static boolean synonymIn(String id, String token) {
        if (token.startsWith("pick")) return id.contains("pickaxe");
        if (token.equals("shovel") || token.equals("spade")) return id.contains("shovel");
        if (token.equals("hatchet")) return id.contains("axe");
        if (token.equals("weapon") || token.equals("blade") || token.equals("arms"))
            return id.contains("sword") || id.contains("axe") || id.contains("trident") || id.contains("mace");
        if (token.equals("hoe") || token.equals("hoes")) return id.contains("hoe");
        return false;
    }

    private void sayNoTool(String want) {
        speak(want.isBlank() ? "My bag has no tools." : "I don't have " + want.replace('_', ' ') + " in my bag.");
    }

    // ------------------------------------------------------------------ block seeking helpers

    /** Converts "some wood", "diamonds", "oak planks"... into a block id (or family id), or "" when unknown. */
    public static String normalizeBlockId(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT).trim().replace(' ', '_').replace('-', '_');
        s = s.replaceAll("^(some|any|all|a|an|the|me|my|of|_)+", "");
        if (s.startsWith("minecraft:")) s = s.substring(10);
        s = BLOCK_ALIASES.getOrDefault(s, s);
        if (isBlock(s)) return s;
        if (s.endsWith("s") && s.length() > 3) {                 // plurals: stones -> stone, logs -> log
            String t = s.substring(0, s.length() - 1);
            if (isBlock(t) || FAMILY_IDS.contains(t)) return t;
        }
        return FAMILY_IDS.contains(s) ? s : "";
    }

    private static boolean isBlock(String id) {
        if (id == null || id.isEmpty()) return false;
        Identifier loc = Identifier.tryParse(id);
        return loc != null && BuiltInRegistries.BLOCK.containsKey(loc);
    }

    public static String prettyBlockId(String id) {
        if (id == null || id.isBlank()) return "that block";
        String s = id;
        if (s.startsWith("minecraft:")) s = s.substring(10);
        return switch (s) {
            case "log", "wood" -> "wood";
            case "planks", "plank" -> "planks";
            case "leaves" -> "leaves";
            case "wool" -> "wool";
            default -> {
                if (s.startsWith("deepslate_")) s = s.substring(10);
                yield s.replace('_', ' ');
            }
        };
    }

    // ------------------------------------------------------------------ movement helpers

    /** Puts the companion on a free, solid spot near {@code to}. Falls back to the exact owner position. */
    public void teleportNear(LivingEntity to) {
        Vec3 from = position();
        BlockPos base = to.blockPosition();
        for (int i = 0; i < 16; i++) {
            int dx = random.nextIntBetweenInclusive(-3, 3), dz = random.nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            BlockPos p = base.offset(dx, 0, dz);
            if (!level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), Direction.UP)) continue;
            if (!level().noCollision(this, getBoundingBox().move(p.getX() + 0.5 - getX(), p.getY() - getY(), p.getZ() + 0.5 - getZ()))) continue;
            setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            afterTeleport(from);
            return;
        }
        setPos(to.getX(), to.getY(), to.getZ());
        afterTeleport(from);
    }

    /** Shared tail of a teleport: stop pathing, reset fall damage, and play the enderman poof at both ends. */
    private void afterTeleport(Vec3 from) {
        getNavigation().stop();
        resetFallDistance();
        if (!(level() instanceof ServerLevel server)) return;
        Vec3 to = position();
        server.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9f, 1.0f);
        server.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9f, 1.2f);
        server.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 0.9, from.z, 24, 0.3, 0.6, 0.3, 0.15);
        server.sendParticles(ParticleTypes.PORTAL, to.x, to.y + 0.9, to.z, 24, 0.3, 0.6, 0.3, 0.15);
    }

    // ------------------------------------------------------------------ vanilla hooks

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (!registryChecked) {
            registryChecked = true;
            if (!claimRegistry()) return;       // stale duplicate: it dropped its bag and removed itself
        }
        if (tickCount % 10 == 0) refreshTaskText();
        if (isSitting()) {
            getNavigation().stop();
            if (tickCount % 40 == 0) heal(1.0f);               // resting heals
        }
        // Clear the synced subtitle after 6 seconds (the client animation itself lasts ~5 s).
        // Keep the text token alive for the whole TTS line. The client hides it from
        // actual playback progress; clearing it after six seconds truncated longer audio.
        if (!speech().isBlank() && tickCount - lastSpokenTick > 600) {
            entityData.set(DATA_SPEECH, "");
        }
        if (tickCount % 20 == 0) refreshEquipment();
        if (tickCount % 40 == 0) aggroMonsters();   // hostile mobs should see the companion as a target
        // Drop an automatic target we cannot reach (so mining etc. resumes); player-forced targets persist.
        LivingEntity tgt = getTarget();
        if (tgt != null && tgt != forcedTarget && tgt.isAlive() && tickCount - targetSinceTick > 200
                && getNavigation().isDone() && distanceToSqr(tgt) > 16 * 16) {
            setTarget(null);
        }
        if (isInjured()) {
            getNavigation().stop();
            setTarget(null);
            setPose(Pose.SWIMMING);                            // Mojang's crawl animation (swimming pose on land)
            if (tickCount % 10 == 0) makeMobsForgetMe();       // a downed body is not a target
            boolean reviving = isReviving();
            if (reviving) {
                reviveProgress = Math.min(1f, reviveProgress + 1f / REVIVE_TICKS);
                entityData.set(DATA_REVIVE, reviveProgress);
                if (reviveProgress >= 1f) { revive(); return; }
            } else {
                reviveProgress = Math.max(0f, reviveProgress - 1f / (REVIVE_TICKS * 2));
                entityData.set(DATA_REVIVE, reviveProgress);
                if (--injuredLeftTicks <= 0) { bleedOut(); return; }
                if (injuredLeftTicks == 200) speak("I'm fading... revive me!");
            }
            if (tickCount % 10 == 0) entityData.set(DATA_INJURED_LEFT, injuredLeftTicks);
        }
        if (!level().isClientSide() && planner.active()) planner.tick(this, tickCount);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { // 26.1-CHECK: hurt -> hurtServer since 1.21.2
        if (isInjured()) {
            // Downed bodies take no normal damage, but /kill and the void must still be able to finish them.
            if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
            return super.hurtServer(level, source, amount);
        }
        // Friendly fire enabled: the owner can hurt the companion just like another player can.
        // Lethal damage still uses the normal downed/revive flow (and a downed companion is invulnerable).
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            playSound(net.minecraft.sounds.SoundEvents.GENERIC_HURT, 1.0f, 1.0f);
            if (tickCount - lastOuchTick > 60) {
                lastOuchTick = tickCount;
                speak(new String[]{"Ouch!", "Watch it!", "Hey, that hurt!", "Ow!", "Easy there!"}[random.nextInt(5)]);
            }
        }
        if (hurt && isSitting()) setMoveMode(MoveMode.STAY);   // stand up and defend yourself
        return hurt;
    }

    /** First lethal hit converts into the injured crawl instead of death. */
    @Override
    public void die(DamageSource source) {
        if (!forceDeath && !isInjured()) { injure(source); return; }
        if (!forceDeath) { dropStorageAndClear(); forceDeath = true; }   // killed while downed (/kill, void): the bag must not vanish
        if (level() instanceof ServerLevel sl && ownerUuid != null) CompanionRegistry.clear(sl.getServer(), ownerUuid, getUUID());
        super.die(source);
    }

    // Right-clicking the model is intentionally disabled: all orders come from the radial menu or voice.

    @Override
    public boolean removeWhenFarAway(double distSq) { return false; }

    // ------------------------------------------------------------------ task text, plants

    private void refreshTaskText() {
        long now = level().getGameTime();
        String s = "";
        if (mineTask.exists()) {
            String p = prettyBlockId(mineTask.blockId).replace(" ore", "");
            s = Character.toUpperCase(p.charAt(0)) + p.substring(1) + " " + mineTask.mined + "/" + mineTask.want;
            if (mineTask.paused) s += " || say 'continue'";
            else {
                String st = seekGoal.stateName();
                if (st != null && !st.isBlank()) s += " - " + st;
            }
        } else if (plantUntil > now) s = "Planting";
        else if (farmUntil > now) s = "Farming";
        else if (fishUntil > now) s = "Fishing";
        else if (!buildSchematic.isBlank()) s = "Building";
        if (!s.equals(entityData.get(DATA_TASK))) entityData.set(DATA_TASK, s);
    }

    private static boolean isPlantId(String id) {
        if (id.endsWith("_sapling") || id.endsWith("_propagule")) return true;
        Identifier loc = Identifier.tryParse(id);
        if (loc == null) return false;
        return BuiltInRegistries.BLOCK.getOptional(loc).map(b -> {
            BlockState st = b.defaultBlockState();
            return st.is(net.minecraft.tags.BlockTags.SAPLINGS) || st.is(net.minecraft.tags.BlockTags.FLOWERS)
                    || st.is(net.minecraft.tags.BlockTags.CROPS);
        }).orElse(false);
    }

    // ------------------------------------------------------------------ identity, registry, downed handling

    /** Owner UUID from the synced data: valid on BOTH sides (the {@code ownerUuid} field is server-only). */
    public UUID syncedOwner() {
        String s = entityData.get(DATA_OWNER);
        if (s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static String sanitizeName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\p{Cntrl}", "").trim();
        return s.length() > 32 ? s.substring(0, 32).trim() : s;
    }

    /**
     * One companion per owner: the world's registry says which UUID is the real one. A copy that was left in an
     * unloaded chunk when the shrine made a new companion drops its bag and removes itself the moment it loads.
     * @return false when this entity just removed itself
     */
    private boolean claimRegistry() {
        if (ownerUuid == null || !(level() instanceof ServerLevel sl)) return true;
        UUID registered = CompanionRegistry.get(sl.getServer(), ownerUuid);
        if (registered == null) { CompanionRegistry.set(sl.getServer(), ownerUuid, getUUID()); return true; }
        if (registered.equals(getUUID())) return true;
        dropStorageAndClear();
        discard();
        return false;
    }

    /** Mobs that were already chasing the body (or remember it as their attacker) let go of it. */
    private void makeMobsForgetMe() {
        if (!(level() instanceof ServerLevel sl)) return;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(32), e -> e != this)) {
            if (m.getTarget() == this) m.setTarget(null);
            if (m.getLastHurtByMob() == this) m.setLastHurtByMob(null);
            // hasMemoryValue is null-safe; getMemory() itself throws for brains without ATTACK_TARGET
            // (cows, chickens, most passive mobs), which crashed the server when the companion went down.
            if (m.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).ifPresent(t -> {
                    if (t == this) m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                });
            }
        }
    }

    /** Drops the whole bag and the mirrored gear on the ground, and empties them so nothing can be dropped twice. */
    private void dropStorageAndClear() {
        Containers.dropContents(level(), this, storage);
        storage.clearContent();
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        for (EquipmentSlot slot : ARMOR_SLOTS) setItemSlot(slot, ItemStack.EMPTY);
    }

    /** Used when the shrine has to recreate the companion (other dimension): nothing the player gave it is lost. */
    public void copyStateFrom(CompanionEntity other) {
        for (int i = 0; i < SIZE; i++) storage.setItem(i, other.storage.getItem(i).copy());
        if (other.hasCustomName()) setCustomName(other.getCustomName());
        setSkin(other.skin());
        setFeminine(other.isFeminine());
        setStance(other.stance());
        refreshEquipment();
    }

    /** A downed companion is not a valid target for any mob. */
    @Override
    public boolean canBeSeenAsEnemy() { return !isInjured() && super.canBeSeenAsEnemy(); }

    @Override
    public boolean canBeSeenByAnyone() { return !isInjured() && super.canBeSeenByAnyone(); }
}
